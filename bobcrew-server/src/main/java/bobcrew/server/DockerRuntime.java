package bobcrew.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.BlkioStatEntry;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The server's only connection to Docker: creates, attaches to, inspects and removes the containers that
 * run the agents. Every container it creates carries {@link #LABEL_MANAGED} and {@link #LABEL_INSTANCE},
 * which is how containers left behind by an earlier run of this server (one killed with -9, say) are
 * recognised and removed at startup.
 *
 * <p>Containers get no host directories, no host environment and no capabilities; they run as a fixed
 * non-root user. Images are never pulled: one that is not on the Docker host is refused, so what can run is
 * decided by whoever manages that host.
 */
@Component
class DockerRuntime implements Closeable {

    /** The agent's working directory inside every container. */
    static final String WORKSPACE = "/opt/workspace";

    static final String LABEL_MANAGED = "bobcrew.managed";

    static final String LABEL_INSTANCE = "bobcrew.instance";

    static final String LABEL_SESSION = "bobcrew.session";

    static final String LABEL_OWNER = "bobcrew.owner";

    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}");

    private static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}");

    private static final Logger log = LoggerFactory.getLogger(DockerRuntime.class);

    /** What a session's container is made of. */
    record ContainerSpec(String sessionId, String owner, String image, Map<String, String> env) {

        @Override
        public String toString() {
            // the values may be secrets: names only
            return "ContainerSpec[session=" + sessionId + ", owner=" + owner + ", image=" + image + ", env="
                    + env.keySet() + "]";
        }
    }

    /** A file read out of a container; closing it closes the stream from Docker. */
    record ContainerFile(String name, long size, InputStream content) implements Closeable {

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    private final DockerClient client;

    private final BobCrewProperties.Docker settings;

    private final BobCrewProperties.Agent agent;

    DockerRuntime(BobCrewProperties properties) {
        this.settings = properties.docker();
        this.agent = properties.agent();
        if (!INSTANCE_ID.matcher(settings.instanceId()).matches()) {
            throw new IllegalStateException("bobcrew.docker.instance-id must be letters, digits, '.', '_' or '-'");
        }
        String user = settings.user() == null ? "" : settings.user().strip();
        String uid = user.contains(":") ? user.substring(0, user.indexOf(':')) : user;
        if (uid.isEmpty() || uid.equals("0") || uid.equals("root")) {
            throw new IllegalStateException("bobcrew.docker.user must name a non-root user, as uid:gid");
        }
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(settings.host())
            .withApiVersion(settings.apiVersion())
            .build();
        ApacheDockerHttpClient http = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            // one long-lived attach connection per session, plus short calls
            .maxConnections(properties.maxSessions() * 2 + 20)
            .connectionTimeout(Duration.ofSeconds(10))
            .build();
        this.client = DockerClientImpl.getInstance(config, http);
        try {
            client.pingCmd().exec();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot reach the Docker daemon at " + settings.host() + ": "
                    + e.getMessage(), e);
        }
        log.info("Docker daemon at {} (instance id {}, default image {})", settings.host(), settings.instanceId(),
                settings.defaultImage());
        removeOrphans();
    }

    String defaultImage() {
        return settings.defaultImage();
    }

    /**
     * Removes every container labelled as this server instance's: at startup they can only be left over
     * from an earlier run, whose sessions are gone.
     */
    void removeOrphans() {
        List<Container> orphans = client.listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(Map.of(LABEL_MANAGED, "true", LABEL_INSTANCE, settings.instanceId()))
            .exec();
        for (Container orphan : orphans) {
            log.info("Removing container {} ({}) left by an earlier run", shortId(orphan.getId()),
                    orphan.getLabels() == null ? "?" : orphan.getLabels().get(LABEL_SESSION));
            removeQuietly(orphan.getId());
        }
    }

    /**
     * Creates (but does not start) the container for a session.
     * @throws ApiException 400 if the image is not on the Docker host, 502 if Docker refuses
     */
    String create(ContainerSpec spec) {
        if (!IMAGE.matcher(spec.image()).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid image name");
        }
        try {
            client.inspectImageCmd(spec.image()).exec();
        } catch (NotFoundException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The image " + spec.image()
                    + " is not present on the server; images are never pulled (see scripts/push-images.sh)");
        }
        List<String> env = new ArrayList<>();
        spec.env().forEach((name, value) -> env.add(name + "=" + value));
        if (agent.apiKey() != null && !agent.apiKey().isBlank()) {
            env.add("BOB_API_KEY=" + agent.apiKey());
            env.add("BOBSHELL_API_KEY=" + agent.apiKey());
        }
        env.add("BOBCREW_SESSION_ID=" + spec.sessionId());
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED, "true");
        labels.put(LABEL_INSTANCE, settings.instanceId());
        labels.put(LABEL_SESSION, spec.sessionId());
        labels.put(LABEL_OWNER, spec.owner());
        HostConfig host = HostConfig.newHostConfig()
            .withInit(true)
            .withCapDrop(Capability.ALL)
            .withSecurityOpts(List.of("no-new-privileges"))
            .withMemory(settings.memoryLimit().toBytes())
            .withNanoCPUs((long) (settings.cpus() * 1_000_000_000L))
            .withPidsLimit(settings.pidsLimit())
            .withNetworkMode(settings.network())
            .withAutoRemove(false);
        try {
            CreateContainerResponse created = client.createContainerCmd(spec.image())
                .withName("bobcrew-" + settings.instanceId() + "-" + spec.sessionId())
                .withCmd(agent.command())
                .withEnv(env)
                .withLabels(labels)
                .withUser(settings.user())
                .withWorkingDir(WORKSPACE)
                .withTty(false)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withStdinOpen(true)
                // the agent's input ends when the server detaches: an ACP agent then exits
                .withStdInOnce(true)
                .withHostConfig(host)
                .exec();
            return created.getId();
        } catch (RuntimeException e) {
            log.warn("Creating a container from {} failed: {}", spec.image(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Docker could not create the container: " + e.getMessage());
        }
    }

    /**
     * Attaches to a created container's standard streams, waiting until the attachment is in place, so that
     * nothing the container writes after {@link #start} is missed.
     * @param stdin read by Docker and written to the container's standard input until it ends
     * @param frames receives the container's output, demultiplexed (the container has no TTY)
     */
    <T extends ResultCallback<Frame>> T attach(String containerId, InputStream stdin, T frames) {
        T callback = client.attachContainerCmd(containerId)
            .withStdIn(stdin)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .exec(frames);
        return callback;
    }

    void start(String containerId) {
        client.startContainerCmd(containerId).exec();
    }

    /** The container's exit code, or null if it is still running or gone. */
    Long exitCode(String containerId) {
        try {
            InspectContainerResponse.ContainerState state = client.inspectContainerCmd(containerId).exec().getState();
            return Boolean.TRUE.equals(state.getRunning()) ? null : state.getExitCodeLong();
        } catch (NotFoundException e) {
            return null;
        }
    }

    /** Stops the container (it gets {@code stop-timeout} to exit) and removes it with its anonymous volumes. */
    void remove(String containerId) {
        try {
            client.stopContainerCmd(containerId).withTimeout((int) Math.max(0, settings.stopTimeout().toSeconds())).exec();
        } catch (NotFoundException | NotModifiedException e) {
            // already gone, or already stopped
        } catch (RuntimeException e) {
            log.debug("Stopping container {} failed: {}", shortId(containerId), e.toString());
        }
        removeQuietly(containerId);
    }

    private void removeQuietly(String containerId) {
        try {
            client.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (NotFoundException e) {
            // already gone
        } catch (RuntimeException e) {
            log.warn("Removing container {} failed: {}", shortId(containerId), e.toString());
        }
    }

    /**
     * Deletes everything inside the container's workspace, hidden files included, and keeps the directory.
     * {@code rm} removes symbolic links without following them.
     * @throws ApiException 500 if the command fails
     */
    void wipeWorkspace(String containerId) {
        int exit = exec(containerId, Duration.ofMinutes(2), "sh", "-c",
                "rm -rf -- " + WORKSPACE + "/* " + WORKSPACE + "/.[!.]* " + WORKSPACE + "/..?*");
        if (exit != 0) {
            log.warn("Wiping the workspace of container {} exited with {}", shortId(containerId), exit);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The workspace could not be wiped completely; see the server log");
        }
    }

    private int exec(String containerId, Duration timeout, String... command) {
        try {
            ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
                .withCmd(command)
                .withUser(settings.user())
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
            ResultCallback.Adapter<Frame> output = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    log.debug("exec {}: {}", shortId(containerId), frame);
                }
            };
            if (!client.execStartCmd(exec.getId()).exec(output).awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return -1;
            }
            // the output can end a moment before Docker records the exit code
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                InspectExecResponse inspected = client.inspectExecCmd(exec.getId()).exec();
                if (!Boolean.TRUE.equals(inspected.isRunning()) && inspected.getExitCodeLong() != null) {
                    return inspected.getExitCodeLong().intValue();
                }
                if (System.nanoTime() > deadline) {
                    return -1;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (RuntimeException e) {
            log.warn("Running a command in container {} failed: {}", shortId(containerId), e.toString());
            return -1;
        }
    }

    /**
     * Opens one regular file of the container. {@code path} must already be checked by {@link ContainerPaths}.
     * Anything that is not a regular file, a symbolic link included, is refused, so that a link cannot reach
     * files outside the workspace.
     * @throws ApiException 404 if there is no such file, 400 if it is not a regular file
     */
    ContainerFile readFile(String containerId, String path) {
        InputStream archive;
        try {
            archive = client.copyArchiveFromContainerCmd(containerId, path).exec();
        } catch (NotFoundException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        }
        boolean handedOver = false;
        try {
            TarArchiveInputStream tar = new TarArchiveInputStream(archive);
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
            }
            if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The requested path is not a file");
            }
            String name = entry.getName();
            int slash = name.lastIndexOf('/');
            ContainerFile file = new ContainerFile(slash >= 0 ? name.substring(slash + 1) : name, entry.getSize(), tar);
            handedOver = true;
            return file;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The file could not be read from the container");
        } finally {
            if (!handedOver) {
                try {
                    archive.close();
                } catch (IOException e) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * A snapshot of the container's resource use. Docker takes two samples for it, so it takes about a
     * second or two.
     * @throws ApiException 502 if Docker gives no figures
     */
    ContainerMetrics metrics(String containerId) {
        InspectContainerResponse inspected;
        try {
            inspected = client.inspectContainerCmd(containerId).exec();
        } catch (NotFoundException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "The session's container is gone");
        }
        AtomicReference<Statistics> sample = new AtomicReference<>();
        try (ResultCallback.Adapter<Statistics> callback = client.statsCmd(containerId)
            .withNoStream(true)
            .exec(new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Statistics statistics) {
                    sample.compareAndSet(null, statistics);
                }
            })) {
            callback.awaitCompletion(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.debug("Reading the stats of container {} failed: {}", shortId(containerId), e.toString());
        }
        Statistics stats = sample.get();
        if (stats == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Docker returned no statistics for the session's container");
        }
        InspectContainerResponse.ContainerState state = inspected.getState();
        Instant startedAt = parseInstant(state.getStartedAt());
        return new ContainerMetrics(shortId(containerId), inspected.getConfig().getImage(), state.getStatus(),
                startedAt, startedAt == null ? null : Duration.between(startedAt, Instant.now()).toSeconds(),
                cpuPercent(stats), memoryUsage(stats.getMemoryStats()),
                stats.getMemoryStats() == null ? null : stats.getMemoryStats().getLimit(),
                stats.getPidsStats() == null ? null : stats.getPidsStats().getCurrent(),
                sumNetwork(stats, true), sumNetwork(stats, false), sumBlkio(stats, "read"), sumBlkio(stats, "write"));
    }

    private static Double cpuPercent(Statistics stats) {
        CpuStatsConfig now = stats.getCpuStats();
        CpuStatsConfig before = stats.getPreCpuStats();
        if (now == null || before == null || now.getCpuUsage() == null || before.getCpuUsage() == null
                || now.getSystemCpuUsage() == null || before.getSystemCpuUsage() == null
                || now.getCpuUsage().getTotalUsage() == null || before.getCpuUsage().getTotalUsage() == null) {
            return null;
        }
        double cpuDelta = now.getCpuUsage().getTotalUsage() - before.getCpuUsage().getTotalUsage();
        double systemDelta = now.getSystemCpuUsage() - before.getSystemCpuUsage();
        if (systemDelta <= 0 || cpuDelta < 0) {
            return 0.0;
        }
        long cpus = now.getOnlineCpus() != null ? now.getOnlineCpus()
                : now.getCpuUsage().getPercpuUsage() != null ? now.getCpuUsage().getPercpuUsage().size() : 1;
        return Math.round(cpuDelta / systemDelta * cpus * 1000.0) / 10.0;
    }

    /** Memory in use as {@code docker stats} shows it: the page cache that can be dropped is not counted. */
    private static Long memoryUsage(MemoryStatsConfig memory) {
        if (memory == null || memory.getUsage() == null) {
            return null;
        }
        long usage = memory.getUsage();
        if (memory.getStats() != null) {
            Long inactive = memory.getStats().getInactiveFile() != null ? memory.getStats().getInactiveFile()
                    : memory.getStats().getTotalInactiveFile();
            if (inactive != null && inactive < usage) {
                usage -= inactive;
            }
        }
        return usage;
    }

    private static Long sumNetwork(Statistics stats, boolean received) {
        if (stats.getNetworks() == null) {
            return null;
        }
        return stats.getNetworks().values().stream()
            .map(net -> received ? net.getRxBytes() : net.getTxBytes())
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .sum();
    }

    private static Long sumBlkio(Statistics stats, String op) {
        if (stats.getBlkioStats() == null || stats.getBlkioStats().getIoServiceBytesRecursive() == null) {
            return null;
        }
        return stats.getBlkioStats().getIoServiceBytesRecursive().stream()
            .filter(entry -> entry.getOp() != null && entry.getOp().equalsIgnoreCase(op))
            .map(BlkioStatEntry::getValue)
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .sum();
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank() || text.startsWith("0001-")) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String shortId(String containerId) {
        return containerId == null ? null : containerId.substring(0, Math.min(12, containerId.length()));
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
