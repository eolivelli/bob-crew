package bobcrew.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * One agent container and the ACP session running in it: starts the container, sends prompts,
 * records everything the agent reports in the session's {@link EventLog}, and holds the agent's
 * permission requests until a user answers them.
 */
final class AgentSession {

    enum Status {
        /** The agent's container is being started. */
        STARTING,
        /** Ready for a prompt. */
        IDLE,
        /** A prompt turn is in progress. */
        RUNNING,
        /** The agent's container ended by itself or could not be started. */
        FAILED,
        /** Closed on request. */
        CLOSED
    }

    /** What clients see of a session. */
    record Info(String id, String owner, List<String> sharedWith, String title, String image, String containerId,
            List<String> secrets, GitRepository repository,
            List<String> capabilities, Resources resources, Status status, String agent, String currentMode, List<Mode> modes, Instant createdAt, Instant updatedAt,
            long lastSeq) {
    }

    /** A file a user put in the workspace. */
    record Upload(String path, long size) {
    }

    record Mode(String id, String name) {
    }

    /** A user's answer to a permission request; a null option means the request was cancelled. */
    private record Decision(String optionId, String user) {
    }

    private record PendingPermission(CompletableFuture<Decision> answer, Set<String> optionIds) {
    }

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    private static final Logger agentStderr = LoggerFactory.getLogger("bobcrew.agent.stderr");

    private final String id;

    private final String owner;

    private final String title;

    private final DockerRuntime.ContainerSpec spec;

    private final GitRepository repository;

    private final DockerRuntime docker;

    private final BobCrewProperties properties;

    private final EventLog events;

    private final Instant createdAt = Instant.now();

    private final Map<String, PendingPermission> pendingPermissions = new ConcurrentHashMap<>();

    private volatile Instant updatedAt = createdAt;

    private volatile Status status = Status.STARTING;

    private volatile AcpSyncClient client;

    private volatile String containerId;

    private volatile String acpSessionId;

    private volatile String agentName;

    private volatile String currentMode;

    private volatile List<Mode> modes = List.of();

    private volatile boolean firstPrompt = true;

    /** Users other than the owner who may use the session; see {@link SessionAccessPolicy}. */
    private volatile Set<String> sharedWith = Set.of();

    /** The names of the session's secrets (files in {@link DockerRuntime#SECRETS_DIR}); never their values. */
    private final List<String> secretNames;

    /** The secrets' values until they are written into the container, then null: the server keeps no copy. */
    private volatile Map<String, String> pendingSecrets;

    /** The user profile files until they are written into the container, then null. */
    private volatile List<ProfileFile> pendingUserProfile;

    /** Why writing the secrets failed, if it did: reported as is rather than as a failure of the agent. */
    private volatile ApiException secretsFailure;

    /** Whether the repository is in the workspace: cloned, and not wiped since. */
    private volatile boolean repositoryPresent;

    /**
     * @param repository cloned into the workspace when the session starts, or null
     * @param secrets written as files into the container when it starts (names already checked); kept until then
     * @param userProfile files written into /home/bob in the container before the agent starts
     */
    AgentSession(DockerRuntime.ContainerSpec spec, GitRepository repository, Map<String, String> secrets,
            List<ProfileFile> userProfile, String title, DockerRuntime docker, BobCrewProperties properties) {
        this.secretNames = secrets.keySet().stream().sorted().toList();
        this.pendingSecrets = Map.copyOf(secrets);
        this.pendingUserProfile = userProfile == null ? List.of() : List.copyOf(userProfile);
        this.id = spec.sessionId();
        this.owner = spec.owner();
        this.title = title;
        this.spec = spec;
        this.repository = repository;
        this.docker = docker;
        this.properties = properties;
        this.events = new EventLog(properties.eventBufferSize());
    }

    String id() {
        return id;
    }

    String owner() {
        return owner;
    }

    Set<String> sharedWith() {
        return sharedWith;
    }

    /**
     * Replaces the users the session is shared with, and records who did it in the event log, which every user
     * of the session sees. Who may do this is {@link SessionAccessPolicy}'s decision, made before.
     * @param users already checked names, without the owner
     */
    void share(String by, Set<String> users) {
        sharedWith = Set.copyOf(users);
        updatedAt = Instant.now();
        events.append(UiEvent.SHARED, by, Map.of("users", users.stream().sorted().toList()));
    }

    Status status() {
        return status;
    }

    String image() {
        return spec.image();
    }

    Instant updatedAt() {
        return updatedAt;
    }

    EventLog events() {
        return events;
    }

    Info info() {
        return new Info(id, owner, sharedWith.stream().sorted().toList(), title, spec.image(),
                DockerRuntime.shortId(containerId), secretNames, repository, spec.capabilities(), spec.resources(),
                status,
                agentName,
                currentMode, modes, createdAt, updatedAt, events.lastSeq());
    }

    /**
     * Creates and starts the agent's container, initializes ACP, clones the repository if there is one, and
     * creates the agent's session (after the clone, so the agent sees the repository from the start).
     * @throws ApiException with 400 if the image is not on the server or the repository cannot be cloned, 502 if
     * the agent cannot be started or refuses the session
     */
    void start() {
        try {
            String created = docker.create(spec);
            this.containerId = created;
            ContainerAcpTransport transport = new ContainerAcpTransport(docker, created,
                    line -> agentStderr.debug("[{}] {}", id, line), reason -> onAgentExit(),
                    () -> writeSecrets(created));
            AcpSyncClient newClient = AcpClient.sync(transport)
                .requestTimeout(properties.requestTimeout())
                .promptTimeout(properties.promptTimeout())
                .clientInfo(new AcpSchema.Implementation("bob-crew", "0.1.0", "bob-crew"))
                // no file system or terminal capabilities: the agent works with its own tools
                .clientCapabilities(new AcpSchema.ClientCapabilities())
                .sessionUpdateHandler(this::onUpdate)
                .requestPermissionHandler(this::onPermissionRequest)
                .build();
            this.client = newClient;
            AcpSchema.InitializeResponse initialized = newClient.initialize();
            if (initialized.agentInfo() != null) {
                AcpSchema.Implementation info = initialized.agentInfo();
                this.agentName = (info.title() != null ? info.title() : info.name()) + " " + info.version();
            }
            if (repository != null) {
                docker.cloneRepository(created, repository, properties.git().cloneTimeout());
                repositoryPresent = true;
                Map<String, Object> cloned = new LinkedHashMap<>();
                cloned.put("url", repository.url());
                if (repository.branch() != null) {
                    cloned.put("branch", repository.branch());
                }
                cloned.put("path", repository.path());
                events.append(UiEvent.REPOSITORY_CLONED, null, cloned);
            }
            useSession(newClient.newSession(new AcpSchema.NewSessionRequest(DockerRuntime.WORKSPACE, List.of())));
        } catch (ApiException e) {
            setStatus(Status.FAILED);
            shutdown();
            throw e;
        } catch (RuntimeException e) {
            pendingSecrets = null;
            pendingUserProfile = null;
            if (secretsFailure != null) {
                setStatus(Status.FAILED);
                shutdown();
                throw secretsFailure;
            }
            log.warn("Session {}: the agent could not be started: {}", id, e.toString());
            setStatus(Status.FAILED);
            shutdown();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The agent could not start a session: " + rootMessage(e));
        }
        synchronized (this) {
            // the container may already have died while starting
            if (status == Status.STARTING) {
                setStatus(Status.IDLE);
            }
        }
    }

    /** Runs once the container has started; its entrypoint waits until this is done. */
    private void writeSecrets(String container) {
        List<ProfileFile> profile = pendingUserProfile;
        pendingUserProfile = null;
        Map<String, String> secrets = pendingSecrets;
        pendingSecrets = null;
        try {
            if (profile != null && !profile.isEmpty()) {
                docker.writeUserProfile(container, profile, properties.requestTimeout());
            }
            docker.writeSecrets(container, secrets == null ? Map.of() : secrets, properties.requestTimeout());
        } catch (ApiException e) {
            secretsFailure = e;
            throw e;
        }
    }

    private void useSession(AcpSchema.NewSessionResponse created) {
        this.acpSessionId = created.sessionId();
        if (created.modes() != null) {
            this.currentMode = created.modes().currentModeId();
            this.modes = created.modes()
                .availableModes()
                .stream()
                .map(mode -> new Mode(mode.id(), mode.name()))
                .toList();
        }
    }

    /**
     * Starts the conversation over without ending the session: optionally deletes the workspace's files,
     * opens a fresh ACP session in the same agent, forgets the recorded events and records a
     * {@link UiEvent#CLEARED} event. The session counts as starting meanwhile, so no prompt gets in.
     * If it fails, the session is as it was (except for what the wipe already removed).
     * @param wipeWorkspace whether to delete everything in the workspace; false keeps, say, a cloned repository
     * @throws ApiException with 409 if the session is not idle, 502 if the agent refuses a new session
     */
    Info clear(String user, boolean wipeWorkspace) {
        synchronized (this) {
            if (status != Status.IDLE) {
                throw new ApiException(HttpStatus.CONFLICT, "The session is " + status.name().toLowerCase()
                        + "; it can only be cleared when idle (stop the running turn first)");
            }
            firstPrompt = true;
            setStatus(Status.STARTING);
        }
        try {
            if (wipeWorkspace) {
                repositoryPresent = false;
                docker.wipeWorkspace(containerId, spec.privileged());
            }
            AcpSchema.NewSessionResponse created;
            try {
                created = client.newSession(new AcpSchema.NewSessionRequest(DockerRuntime.WORKSPACE, List.of()));
            } catch (RuntimeException e) {
                log.warn("Session {}: the agent refused a new session: {}", id, e.toString());
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "The agent could not start a fresh session: " + rootMessage(e));
            }
            useSession(created);
            events.discard();
            events.append(UiEvent.CLEARED, user, Map.of("workspaceWiped", wipeWorkspace));
        } finally {
            synchronized (this) {
                if (status == Status.STARTING) {
                    setStatus(Status.IDLE);
                }
            }
        }
        return info();
    }

    /**
     * Starts a prompt turn. It returns at once; the turn's progress and its end arrive as events.
     * @throws ApiException with 409 if the session is not ready for a prompt
     */
    void prompt(String user, String text) {
        boolean isFirst;
        synchronized (this) {
            if (status != Status.IDLE) {
                throw new ApiException(HttpStatus.CONFLICT, "The session is " + status.name().toLowerCase()
                        + " and cannot take a prompt now");
            }
            isFirst = firstPrompt;
            firstPrompt = false;
            events.append(UiEvent.USER_PROMPT, user, Map.of("text", text));
            setStatus(Status.RUNNING);
        }
        Thread.ofVirtual().name("prompt-" + id).start(() -> {
            try {
                List<AcpSchema.ContentBlock> contents = buildPromptContents(text, isFirst);
                AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(acpSessionId, contents));
                events.append(UiEvent.TURN_END, null, Map.of("stopReason", response.stopReason()));
            } catch (AcpConnectionException e) {
                // the connection to the agent is gone, so the session is too
                onAgentExit();
            } catch (RuntimeException e) {
                log.warn("Session {}: prompt failed: {}", id, e.toString());
                events.append(UiEvent.ERROR, null, Map.of("message", "The prompt failed: " + rootMessage(e)));
            } finally {
                cancelPendingPermissions();
                synchronized (this) {
                    if (status == Status.RUNNING) {
                        setStatus(Status.IDLE);
                    }
                }
            }
        });
    }

    /** Asks the agent to stop the current turn; pending permission requests are answered as cancelled. */
    void cancel() {
        if (status != Status.RUNNING) {
            return;
        }
        cancelPendingPermissions();
        try {
            client.cancel(new AcpSchema.CancelNotification(acpSessionId));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The agent did not take the cancellation: " + rootMessage(e));
        }
    }

    /**
     * The first prompt of a conversation carries notes about the environment: how to share files, and where
     * the repository the session was created with is checked out.
     */
    private List<AcpSchema.ContentBlock> buildPromptContents(String text, boolean isFirst) {
        StringBuilder notes = new StringBuilder();
        if (isFirst && properties.fileDownloadsEnabled()) {
            notes.append("[Instructions: You can share files from the workspace with the user by replying with a file URL (for example file:///opt/workspace/path/to/file or file://relative/path). The user interface will make it clickable for download.]\n\n");
        }
        if (isFirst && repositoryPresent) {
            notes.append(repositoryNote()).append("\n\n");
        }
        if (isFirst && !secretNames.isEmpty()) {
            notes.append(secretsNote()).append("\n\n");
        }
        return List.of(new AcpSchema.TextContent(notes + text));
    }

    String secretsNote() {
        return "[Note: the user gave this session secrets, as files in " + DockerRuntime.SECRETS_DIR + ": "
                + String.join(", ", secretNames) + ". Read one only where a command needs it (for example "
                + "\"$(cat " + DockerRuntime.SECRETS_DIR + "/" + secretNames.get(0) + ")\"), and never print, log, "
                + "commit or send its value anywhere else. git and gh already use GH_TOKEN when it is there.]";
    }

    String repositoryNote() {
        return "[Note: the git repository " + repository.url()
                + (repository.branch() == null ? " (default branch)" : " (branch " + repository.branch() + ")")
                + " has been cloned into " + repository.path()
                + ". It is a shallow clone (depth 1, that branch only): run `git fetch --unshallow` if you need its history.]";
    }

    void setMode(String modeId) {
        requireOpen();
        if (modes.stream().noneMatch(mode -> mode.id().equals(modeId))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown mode: " + modeId);
        }
        try {
            client.setSessionMode(new AcpSchema.SetSessionModeRequest(acpSessionId, modeId));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The agent did not change mode: " + rootMessage(e));
        }
        this.currentMode = modeId;
        touch();
    }

    /**
     * Answers a pending permission request with one of the options the agent offered.
     * @throws ApiException with 404 if no such request is pending, 400 if the option was not offered
     */
    void answerPermission(String user, String toolCallId, String optionId) {
        PendingPermission pending = pendingPermissions.get(toolCallId);
        if (pending == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No permission request is pending for this tool call");
        }
        if (!pending.optionIds().contains(optionId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The agent did not offer this option");
        }
        if (!pending.answer().complete(new Decision(optionId, user))) {
            throw new ApiException(HttpStatus.CONFLICT, "The permission request was already answered");
        }
    }

    /** A snapshot of the container's resource use. */
    ContainerMetrics metrics() {
        String current = containerId;
        if (current == null || status == Status.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "The session has no container");
        }
        return docker.metrics(current);
    }

    /**
     * Opens a regular file of the session's workspace.
     * @param path a client path; it goes through {@link ContainerPaths}
     */
    DockerRuntime.ContainerFile readFile(String path) {
        String target = ContainerPaths.resolve(path);
        String current = containerId;
        if (current == null || status == Status.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "The session has no container");
        }
        return docker.readFile(current, target);
    }

    /**
     * Writes a file into the workspace, replacing one already there, and records a
     * {@link UiEvent#FILE_UPLOADED} event. The content is read whole into a temporary file first, because the
     * write in the container must know its exact size (see {@link DockerRuntime#writeFile}).
     * @param path a client path; it goes through {@link ContainerPaths}
     * @throws ApiException 413 if the content is larger than {@code maxSize}, 409 if the session is not open
     */
    Upload upload(String user, String path, InputStream content, long maxSize) throws IOException {
        String target = ContainerPaths.resolve(path);
        requireOpen();
        Path spool = Files.createTempFile("bobcrew-upload-", ".tmp");
        try {
            long size;
            try (OutputStream out = Files.newOutputStream(spool)) {
                size = copyAtMost(content, out, maxSize);
            }
            if (size < 0) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "The file is larger than the server accepts ("
                        + maxSize / (1024 * 1024) + " MB)");
            }
            requireOpen();
            try (InputStream in = Files.newInputStream(spool)) {
                docker.writeFile(containerId, target, in, size, Duration.ofMinutes(10));
            }
            events.append(UiEvent.FILE_UPLOADED, user, Map.of("path", target, "size", size));
            touch();
            log.info("Session {}: {} uploaded {} ({} bytes)", id, user, target, size);
            return new Upload(target, size);
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** Copies everything, or returns -1 as soon as there is more than {@code max} bytes. */
    private static long copyAtMost(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > max) {
                return -1;
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    /** Closes the session and removes its container. */
    void close() {
        synchronized (this) {
            if (status == Status.CLOSED) {
                return;
            }
            setStatus(Status.CLOSED);
        }
        shutdown();
    }

    private void shutdown() {
        cancelPendingPermissions();
        AcpSyncClient current = this.client;
        if (current != null) {
            try {
                current.closeGracefully();
            } catch (RuntimeException e) {
                log.debug("Session {}: closing the client failed: {}", id, e.toString());
            }
        }
        String container = this.containerId;
        if (container != null) {
            docker.remove(container);
        }
        events.close();
    }

    private void onAgentExit() {
        synchronized (this) {
            if (status == Status.CLOSED || status == Status.FAILED) {
                return;
            }
            log.warn("Session {}: the agent's container ended unexpectedly", id);
            events.append(UiEvent.ERROR, null, Map.of("message", "The agent ended unexpectedly"));
            setStatus(Status.FAILED);
        }
        cancelPendingPermissions();
    }

    private void onUpdate(AcpSchema.SessionNotification notification) {
        AcpSchema.SessionUpdate update = notification.update();
        if (update instanceof AcpSchema.CurrentModeUpdate modeUpdate) {
            this.currentMode = modeUpdate.currentModeId();
        }
        events.append(UiEvent.UPDATE, null, update);
        touch();
    }

    private AcpSchema.RequestPermissionResponse onPermissionRequest(AcpSchema.RequestPermissionRequest request) {
        String toolCallId = request.toolCall().toolCallId();
        List<AcpSchema.PermissionOption> options = request.options() == null ? List.of() : request.options();
        CompletableFuture<Decision> answer = new CompletableFuture<>();
        pendingPermissions.put(toolCallId, new PendingPermission(answer,
                options.stream().map(AcpSchema.PermissionOption::optionId).collect(Collectors.toSet())));
        Map<String, Object> asked = new LinkedHashMap<>();
        asked.put("toolCallId", toolCallId);
        asked.put("toolCall", request.toolCall());
        asked.put("options", options);
        events.append(UiEvent.PERMISSION_REQUEST, null, asked);
        touch();

        Decision decision;
        boolean timedOut = false;
        try {
            decision = answer.get(properties.permissionTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            timedOut = true;
            decision = new Decision(rejectOption(options), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = new Decision(null, null);
        } catch (ExecutionException e) {
            decision = new Decision(null, null);
        } finally {
            pendingPermissions.remove(toolCallId);
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("toolCallId", toolCallId);
        resolved.put("outcome", decision.optionId() == null ? "cancelled" : "selected");
        if (decision.optionId() != null) {
            resolved.put("optionId", decision.optionId());
        }
        resolved.put("timedOut", timedOut);
        events.append(UiEvent.PERMISSION_RESOLVED, decision.user(), resolved);
        touch();
        return new AcpSchema.RequestPermissionResponse(decision.optionId() == null
                ? new AcpSchema.PermissionCancelled() : new AcpSchema.PermissionSelected(decision.optionId()));
    }

    /** The option to take when nobody answers: a rejecting one, or none (cancelled) if the agent offered none. */
    private static String rejectOption(List<AcpSchema.PermissionOption> options) {
        return options.stream()
            .filter(option -> option.kind() != null && option.kind().value().startsWith("reject"))
            // "reject once" before "reject always": a timeout should not set a lasting rule
            .sorted(Comparator.comparing(option -> option.kind().value().endsWith("once") ? 0 : 1))
            .map(AcpSchema.PermissionOption::optionId)
            .findFirst()
            .orElse(null);
    }

    private void cancelPendingPermissions() {
        pendingPermissions.values().forEach(pending -> pending.answer().complete(new Decision(null, null)));
    }

    private void requireOpen() {
        if (status != Status.IDLE && status != Status.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "The session is " + status.name().toLowerCase());
        }
    }

    private void setStatus(Status newStatus) {
        this.status = newStatus;
        touch();
        events.append(UiEvent.STATUS, null, Map.of("status", newStatus));
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
