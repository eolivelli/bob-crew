package bobcrew.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    /** Most users a session can be shared with. */
    static final int MAX_SHARED_USERS = 50;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private final BobCrewProperties properties;

    private final DockerRuntime docker;

    private final SessionAccessPolicy accessPolicy;

    private volatile boolean stopping;

    private final AllowedImages allowedImages;

    SessionManager(BobCrewProperties properties, DockerRuntime docker, SessionAccessPolicy accessPolicy) {
        this.properties = properties;
        this.docker = docker;
        this.accessPolicy = accessPolicy;
        this.allowedImages = new AllowedImages(properties.docker().allowedImages(), docker.defaultImage());
        log.info("Agent: {}", properties.agent());
    }

    /**
     * Creates a session owned by {@code caller} and starts its agent in a new container.
     * @param image the image to run; null or blank for the server's default image
     * @param env environment variables for the container; nothing else from the client or the server's own
     * environment gets in (the server adds the API key)
     * @param secrets written as files into the container's {@link DockerRuntime#SECRETS_DIR}, never into its
     * environment; null or empty for none
     * @param repository cloned into the workspace before the agent's session starts, or null
     * @param capabilities what the agent may run besides commands ({@link Capabilities}); null or empty for none.
     * Only those the caller may use ({@link Caller#capabilities()}).
     * @param resources the container's limits, already checked against the caller's ({@link Resources#of})
     * @throws ApiException 400 for a bad image, environment, repository or capability, 403 for an image or a
     * capability the caller may not use, 429 when the server or the user's share of it is full, 502 if the
     * agent fails
     */
    AgentSession create(Caller caller, String title, String image, Map<String, String> env, Map<String, String> secrets,
            List<ProfileFile> userProfile, GitRepository repository,
            List<String> capabilities, Resources resources) {
        String owner = caller.name();
        List<String> checkedCapabilities = Capabilities.check(capabilities, caller.capabilities());
        String chosenImage = image == null || image.isBlank() ? docker.defaultImage() : image.strip();
        allowedImages.check(chosenImage);
        Map<String, String> checkedEnv = checkEnv(env);
        Map<String, String> checkedSecrets = checkSecrets(secrets);
        List<ProfileFile> checkedUserProfile = checkUserProfile(userProfile);
        String id = UUID.randomUUID().toString();
        AgentSession session = new AgentSession(new DockerRuntime.ContainerSpec(id, owner, chosenImage, checkedEnv,
                checkedCapabilities, resources),
                repository, checkedSecrets, checkedUserProfile, title == null || title.isBlank() ? chosenImage : title.strip(), docker, properties);
        synchronized (this) {
            if (stopping) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The server is shutting down");
            }
            long active = sessions.values().stream().filter(SessionManager::isActive).count();
            if (active >= properties.maxSessions()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The server already runs "
                        + properties.maxSessions() + " sessions; close one first");
            }
            int perUser = caller.limits().maxSessions();
            if (perUser > 0 && sessions.values().stream()
                .filter(other -> isActive(other) && owner.equals(other.owner()))
                .count() >= perUser) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "You already have " + perUser
                        + " open sessions, the most you may have on this server; close one first");
            }
            sessions.put(session.id(), session);
        }
        try {
            session.start();
        } catch (RuntimeException e) {
            sessions.remove(session.id());
            throw e;
        }
        log.info("Session {} created by {} with image {}, environment {}, secrets {}, user-profile {} files{}", session.id(), owner,
                chosenImage, checkedEnv.keySet(), checkedSecrets.keySet(), checkedUserProfile.size(),
                (repository == null ? "" : ", repository " + repository.url())
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

    /**
     * Secrets become file names in the container, so their names follow the same rule as variable names (no
     * separators, no dots); their values may be anything up to the same length as a variable's.
     */
    private Map<String, String> checkSecrets(Map<String, String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return Map.of();
        }
        BobCrewProperties.Docker limits = properties.docker();
        if (secrets.size() > limits.maxEnvVars()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many secrets (at most " + limits.maxEnvVars() + ")");
        }
        Map<String, String> checked = new LinkedHashMap<>();
        secrets.forEach((name, value) -> {
            if (name == null || !ENV_NAME.matcher(name).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid secret name");
            }
            if (value == null || value.length() > limits.maxEnvValueLength()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The secret " + name + " is missing or too long");
            }
            checked.put(name, value);
        });
        return checked;
    }

    static final int MAX_PROFILE_FILES = 64;
    static final int MAX_PROFILE_FILE_BYTES = 256 * 1024;
    static final int MAX_PROFILE_B64_CHARS = (MAX_PROFILE_FILE_BYTES * 4) / 3 + 4;
    static final int MAX_PROFILE_TOTAL_BYTES = 1024 * 1024;
    static final int MAX_PROFILE_PATH_LENGTH = 255;
    static final int MAX_PROFILE_PATH_SEGMENTS = 16;

    /**
     * Validates user profile files (paths, counts, base64 encoding, sizes).
     *
     * @param userProfile client-supplied list of profile files
     * @return immutable validated list
     * @throws ApiException 400 if any validation check fails
     */
    static List<ProfileFile> checkUserProfile(List<ProfileFile> userProfile) {
        if (userProfile == null || userProfile.isEmpty()) {
            return List.of();
        }
        if (userProfile.size() > MAX_PROFILE_FILES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many user profile files (at most "
                    + MAX_PROFILE_FILES + ")");
        }
        Set<String> seenPaths = new HashSet<>();
        Set<String> dirPaths = new HashSet<>();
        long totalBytes = 0;
        List<ProfileFile> checked = new ArrayList<>(userProfile.size());

        for (ProfileFile file : userProfile) {
            if (file == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Null profile file entry");
            }
            String rawPath = file.path();
            if (rawPath == null || rawPath.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid profile file path: empty");
            }
            if (rawPath.length() > MAX_PROFILE_PATH_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Profile file path is too long (at most "
                        + MAX_PROFILE_PATH_LENGTH + " characters): " + rawPath);
            }
            for (int i = 0; i < rawPath.length(); i++) {
                char c = rawPath.charAt(i);
                if (c < 0x20 || c == 0x7f || c == ':' || c == '\\') {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid characters in profile file path");
                }
            }
            if (rawPath.startsWith("/") || rawPath.endsWith("/")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Profile file path must be relative without leading or trailing slashes: " + rawPath);
            }
            String[] segments = rawPath.split("/", -1);
            if (segments.length > MAX_PROFILE_PATH_SEGMENTS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Too many path segments in profile file path: " + rawPath);
            }
            StringBuilder currentDir = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                String seg = segments[i];
                if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid segment in profile file path: " + rawPath);
                }
                if (i < segments.length - 1) {
                    if (currentDir.length() > 0) {
                        currentDir.append("/");
                    }
                    currentDir.append(seg);
                    dirPaths.add(currentDir.toString());
                }
            }
            Path resolved = Path.of("/home/bob").resolve(rawPath).normalize();
            if (!resolved.startsWith(Path.of("/home/bob/")) || resolved.equals(Path.of("/home/bob"))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Profile file path escapes home directory: " + rawPath);
            }
            if (rawPath.equals(".bobcrew") || rawPath.startsWith(".bobcrew/")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Profile file path uses reserved prefix: " + rawPath);
            }
            if (!seenPaths.add(rawPath)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate profile file path: " + rawPath);
            }

            if (file.contentBase64() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Missing content for profile file: " + rawPath);
            }
            if (file.contentBase64().length() > MAX_PROFILE_B64_CHARS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Profile file is too large (at most "
                        + (MAX_PROFILE_FILE_BYTES / 1024) + " KiB): " + rawPath);
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(file.contentBase64());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Base64 content for profile file: " + rawPath);
            }
            if (decoded.length > MAX_PROFILE_FILE_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Profile file is too large (at most "
                        + (MAX_PROFILE_FILE_BYTES / 1024) + " KiB): " + rawPath);
            }
            totalBytes += decoded.length;
            if (totalBytes > MAX_PROFILE_TOTAL_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Total profile files size is too large (at most "
                        + (MAX_PROFILE_TOTAL_BYTES / 1024) + " KiB)");
            }

            checked.add(new ProfileFile(rawPath, file.contentBase64(), file.executable()));
        }

        for (String filePath : seenPaths) {
            if (dirPaths.contains(filePath)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Profile file path conflicts with a directory of another file: " + filePath);
            }
        }

        return List.copyOf(checked);
    }

    /**
     * @throws ApiException 404 if there is no such session or the user may not use it: a session someone else
     * has not shared with you does not exist, as far as you can tell
     */
    AgentSession get(Caller caller, String id) {
        AgentSession session = sessions.get(id);
        if (session == null || !accessPolicy.canUse(caller, session)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No such session");
        }
        return session;
    }

    /** Whether {@code caller} may still use {@code session}: sharing can be withdrawn while a client follows it. */
    boolean canUse(Caller caller, AgentSession session) {
        return accessPolicy.canUse(caller, session);
    }

    /** @throws ApiException 404 as {@link #get}, 403 if the caller may use the session but not {@code action} */
    private AgentSession getToManage(Caller caller, String id, String action) {
        AgentSession session = get(caller, id);
        if (!accessPolicy.canManage(caller, session)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the session's owner can " + action);
        }
        return session;
    }

    /**
     * Shares the session with exactly {@code users} (an empty list makes it the owner's alone again). Only its
     * owner or an admin may.
     * @throws ApiException 400 for a bad list, 403 if the user may not, 404 as {@link #get}
     */
    AgentSession.Info share(Caller caller, String id, List<String> users) {
        AgentSession session = getToManage(caller, id, "change who it is shared with");
        if (users == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "users is required: the names to share with, or []");
        }
        if (users.size() > MAX_SHARED_USERS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A session can be shared with at most "
                    + MAX_SHARED_USERS + " users");
        }
        Set<String> checked = new TreeSet<>();
        for (String name : users) {
            String trimmed = name == null ? "" : name.strip();
            if (trimmed.isEmpty() || trimmed.length() > 200 || trimmed.chars().anyMatch(Character::isISOControl)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid user name in users");
            }
            if (!trimmed.equals(session.owner())) {
                checked.add(trimmed);
            }
        }
        session.share(caller.name(), checked);
        log.info("Session {} shared by {} with {}", id, caller.name(), checked.isEmpty() ? "nobody" : checked);
        return session.info();
    }

    /**
     * Replaces the values of secrets the session has, in its running container. Only its owner or an admin may:
     * the session then acts with the caller's credentials.
     * @throws ApiException 400 for bad secrets or ones the session does not have, 403 if the user may not, 404
     * as {@link #get}, 409 if the session is not open
     */
    AgentSession.Info refreshSecrets(Caller caller, String id, Map<String, String> secrets) {
        AgentSession session = getToManage(caller, id, "refresh its secrets");
        session.refreshSecrets(caller.name(), checkSecrets(secrets));
        return session.info();
    }

    List<AgentSession.Info> list(Caller caller) {
        return sessions.values()
            .stream()
            .filter(session -> accessPolicy.canUse(caller, session))
            .map(AgentSession::info)
            .sorted(Comparator.comparing(AgentSession.Info::createdAt).reversed())
            .toList();
    }

    /** Closes the session, removes its container and forgets it. Only its owner or an admin may. */
    void close(Caller caller, String id) {
        AgentSession session = getToManage(caller, id, "close it");
        session.close();
        sessions.remove(id);
        log.info("Session {} closed by {}", id, caller.name());
    }

    /**
     * Starts the session's conversation over, deleting everything in its workspace unless asked not to; the
     * session stays open.
     * @throws ApiException 409 if busy
     */
    AgentSession.Info clear(Caller caller, String id, boolean wipeWorkspace) {
        AgentSession session = get(caller, id);
        AgentSession.Info info = session.clear(caller.name(), wipeWorkspace);
        log.info("Session {} cleared by {}{}", id, caller.name(), wipeWorkspace ? "; workspace wiped" : "");
        return info;
    }

    /** A snapshot of the session container's resource use. */
    ContainerMetrics metrics(Caller caller, String id) {
        return get(caller, id).metrics();
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
