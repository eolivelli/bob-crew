package bobcrew.server;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The sessions of this server, all in memory: they end with the server, as their containers do. This is
 * the only entry point the API uses.
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** Environment variable names a client may set. */
    static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /** Names a client may not set: they carry the server's secrets or would change how the agent starts. */
    private static final Set<String> RESERVED_ENV = Set.of("BOB_API_KEY", "BOBSHELL_API_KEY", "PATH", "HOME", "USER",
            "LOGNAME", "SHELL", "HOSTNAME");

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private final BobCrewProperties properties;

    private final DockerRuntime docker;

    private final SessionAccessPolicy accessPolicy;

    private volatile boolean stopping;

    private final List<String> allowedCapabilities;

    private final AllowedImages allowedImages;

    SessionManager(BobCrewProperties properties, DockerRuntime docker, SessionAccessPolicy accessPolicy) {
        this.properties = properties;
        this.docker = docker;
        this.accessPolicy = accessPolicy;
        this.allowedCapabilities = Capabilities.allowed(properties.allowedCapabilities());
        this.allowedImages = new AllowedImages(properties.docker().allowedImages(), docker.defaultImage());
        log.info("Agent: {}", properties.agent());
    }

    /** The capabilities sessions may ask for on this server. */
    List<String> allowedCapabilities() {
        return allowedCapabilities;
    }

    /**
     * Creates a session owned by {@code owner} and starts its agent in a new container.
     * @param image the image to run; null or blank for the server's default image
     * @param env environment variables for the container; nothing else from the client or the server's own
     * environment gets in (the server adds the API key)
     * @param repository cloned into the workspace before the agent's session starts, or null
     * @param capabilities what the agent may run besides commands ({@link Capabilities}); null or empty for none
     * @param resources the container's limits, already checked ({@link Resources#of})
     * @throws ApiException 400 for a bad image, environment, repository or capability, 403 for an image or a
     * capability the server does not allow, 429 when the server or the user's share of it is full, 502 if the
     * agent fails
     */
    AgentSession create(String owner, String title, String image, Map<String, String> env, GitRepository repository,
            List<String> capabilities, Resources resources) {
        List<String> checkedCapabilities = Capabilities.check(capabilities, allowedCapabilities);
        String chosenImage = image == null || image.isBlank() ? docker.defaultImage() : image.strip();
        allowedImages.check(chosenImage);
        Map<String, String> checkedEnv = checkEnv(env);
        String id = UUID.randomUUID().toString();
        AgentSession session = new AgentSession(new DockerRuntime.ContainerSpec(id, owner, chosenImage, checkedEnv,
                checkedCapabilities, resources),
                repository, title == null || title.isBlank() ? chosenImage : title.strip(), docker, properties);
        synchronized (this) {
            if (stopping) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The server is shutting down");
            }
            long active = sessions.values().stream().filter(SessionManager::isActive).count();
            if (active >= properties.maxSessions()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The server already runs "
                        + properties.maxSessions() + " sessions; close one first");
            }
            int perUser = properties.maxSessionsPerUser();
            if (perUser > 0 && sessions.values().stream()
                .filter(other -> isActive(other) && owner.equals(other.owner()))
                .count() >= perUser) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "You already have " + perUser
                        + " open sessions, the most this server allows per user; close one first");
            }
            sessions.put(session.id(), session);
        }
        try {
            session.start();
        } catch (RuntimeException e) {
            sessions.remove(session.id());
            throw e;
        }
        log.info("Session {} created by {} with image {}, environment {}{}", session.id(), owner, chosenImage,
                checkedEnv.keySet(), (repository == null ? "" : ", repository " + repository.url())
                        + (checkedCapabilities.isEmpty() ? "" : ", capabilities " + checkedCapabilities
                                + " (privileged)")
                        + ", memory " + (resources.memoryBytes() == null ? "unlimited" : resources.memoryBytes() + " bytes")
                        + ", cpus " + (resources.cpus() == null ? "unlimited" : resources.cpus()));
        return session;
    }

    private Map<String, String> checkEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Map.of();
        }
        BobCrewProperties.Docker limits = properties.docker();
        if (env.size() > limits.maxEnvVars()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many environment variables (at most "
                    + limits.maxEnvVars() + ")");
        }
        Map<String, String> checked = new LinkedHashMap<>();
        env.forEach((name, value) -> {
            if (name == null || !ENV_NAME.matcher(name).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid environment variable name");
            }
            if (RESERVED_ENV.contains(name) || name.startsWith("BOBCREW_") || name.startsWith("LD_")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The environment variable " + name
                        + " is reserved and cannot be set");
            }
            if (value == null || value.length() > limits.maxEnvValueLength() || value.indexOf('\0') >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The value of the environment variable " + name
                        + " is missing, too long or invalid");
            }
            checked.put(name, value);
        });
        return checked;
    }

    /** @throws ApiException 404 if there is no such session or the user may not use it */
    AgentSession get(String user, String id) {
        AgentSession session = sessions.get(id);
        if (session == null || !accessPolicy.canAccess(user, session)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No such session");
        }
        return session;
    }

    List<AgentSession.Info> list(String user) {
        return sessions.values()
            .stream()
            .filter(session -> accessPolicy.canAccess(user, session))
            .map(AgentSession::info)
            .sorted(Comparator.comparing(AgentSession.Info::createdAt).reversed())
            .toList();
    }

    /** Closes the session, removes its container and forgets it. */
    void close(String user, String id) {
        AgentSession session = get(user, id);
        session.close();
        sessions.remove(id);
        log.info("Session {} closed by {}", id, user);
    }

    /**
     * Starts the session's conversation over, deleting everything in its workspace unless asked not to; the
     * session stays open.
     * @throws ApiException 409 if busy
     */
    AgentSession.Info clear(String user, String id, boolean wipeWorkspace) {
        AgentSession session = get(user, id);
        AgentSession.Info info = session.clear(user, wipeWorkspace);
        log.info("Session {} cleared by {}{}", id, user, wipeWorkspace ? "; workspace wiped" : "");
        return info;
    }

    /** A snapshot of the session container's resource use. */
    ContainerMetrics metrics(String user, String id) {
        return get(user, id).metrics();
    }

    @Scheduled(fixedDelay = 60_000)
    void closeIdleSessions() {
        Duration limit = properties.idleTimeout();
        if (limit == null || limit.isZero() || limit.isNegative()) {
            return;
        }
        Instant threshold = Instant.now().minus(limit);
        for (AgentSession session : sessions.values()) {
            if (session.status() != AgentSession.Status.RUNNING && session.updatedAt().isBefore(threshold)) {
                log.info("Session {} closed after {} without activity", session.id(), limit);
                session.close();
                sessions.remove(session.id());
            }
        }
    }

    /**
     * Removes every session's container when the application stops, and any other container labelled as
     * this server's. This runs before the web server's graceful shutdown, and it ends the open event
     * streams, which would otherwise hold it up.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        synchronized (this) {
            stopping = true;
        }
        sessions.values().parallelStream().forEach(AgentSession::close);
        sessions.clear();
        try {
            docker.removeOrphans();
        } catch (RuntimeException e) {
            log.warn("Removing leftover containers failed: {}", e.toString());
        }
    }

    private static boolean isActive(AgentSession session) {
        return switch (session.status()) {
            case STARTING, IDLE, RUNNING -> true;
            case FAILED, CLOSED -> false;
        };
    }
}
