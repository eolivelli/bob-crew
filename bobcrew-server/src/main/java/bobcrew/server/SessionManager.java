package bobcrew.server;

import java.io.InputStream;
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
import tools.jackson.databind.JsonNode;

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

    /**
     * The variables Bob reads its API key from. Not names for secrets: a session's own key is the request's
     * {@code apiKey}, which goes into these variables rather than into a file.
     */
    private static final Set<String> API_KEY_NAMES = Set.of("BOB_API_KEY", "BOBSHELL_API_KEY");

    /** Most users a session can be shared with. */
    static final int MAX_SHARED_USERS = 50;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private final BobCrewProperties properties;

    private final DockerRuntime docker;

    private final SessionAccessPolicy accessPolicy;

    private volatile boolean stopping;

    private final AllowedImages allowedImages;

    private final SessionArchive archive;

    SessionManager(BobCrewProperties properties, DockerRuntime docker, SessionAccessPolicy accessPolicy,
            SessionArchive archive) {
        this.properties = properties;
        this.docker = docker;
        this.accessPolicy = accessPolicy;
        this.archive = archive;
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
     * @param apiKey the Bob API key this session uses in place of the server's, which is how each user of a shared
     * server works with their own; null or blank for the server's. Unlike a secret it reaches the agent through
     * the container's environment, the only place Bob reads it from.
     * @throws ApiException 400 for a bad image, environment, repository or capability, 403 for an image or a
     * capability the caller may not use, 429 when the server or the user's share of it is full, 502 if the
     * agent fails
     */
    AgentSession create(Caller caller, String title, String image, Map<String, String> env, Map<String, String> secrets,
            List<ProfileFile> userProfile, GitRepository repository,
            List<String> capabilities, Resources resources, String apiKey) {
        String owner = caller.name();
        List<String> checkedCapabilities = Capabilities.check(capabilities, caller.capabilities());
        String chosenImage = image == null || image.isBlank() ? docker.defaultImage() : image.strip();
        allowedImages.check(chosenImage);
        Map<String, String> checkedEnv = checkEnv(env);
        Map<String, String> checkedSecrets = checkSecrets(secrets);
        List<ProfileFile> checkedUserProfile = checkUserProfile(userProfile);
        String checkedApiKey = checkApiKey(apiKey);
        String id = UUID.randomUUID().toString();
        AgentSession session = new AgentSession(new DockerRuntime.ContainerSpec(id, owner, chosenImage, checkedEnv,
                checkedCapabilities, resources),
                repository, checkedSecrets, checkedUserProfile, title == null || title.isBlank() ? chosenImage : title.strip(), checkedApiKey,
                docker, properties);
        admit(session, caller, null);
        try {
            session.start();
        } catch (RuntimeException e) {
            sessions.remove(session.id());
            throw e;
        }
        log.info("Session {} created by {} with image {}, environment {}, secrets {}, user-profile {} files{}", session.id(), owner,
                chosenImage, checkedEnv.keySet(), checkedSecrets.keySet(), checkedUserProfile.size(),
                (checkedApiKey == null ? "" : ", its own API key")
                        + (repository == null ? "" : ", repository " + repository.url())
                        + (checkedCapabilities.isEmpty() ? "" : ", capabilities " + checkedCapabilities
                                + " (privileged)")
                        + ", memory " + (resources.memoryBytes() == null ? "unlimited" : resources.memoryBytes() + " bytes")
                        + ", cpus " + (resources.cpus() == null ? "unlimited" : resources.cpus()));
        return session;
    }

    /**
     * Counts the session in, within the server's and its owner's limits. {@code replacing}, if not null, is a
     * session of the same id that it takes the place of (a restart), and does not count.
     * @throws ApiException 409 if another session has its id, 429 when full, 503 when shutting down
     */
    private synchronized void admit(AgentSession session, Caller caller, AgentSession replacing) {
        if (stopping) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The server is shutting down");
        }
        AgentSession present = sessions.get(session.id());
        if (present != null && present != replacing) {
            throw new ApiException(HttpStatus.CONFLICT, "The session is open already");
        }
        long active = sessions.values().stream().filter(other -> other != replacing && isActive(other)).count();
        if (active >= properties.maxSessions()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The server already runs "
                    + properties.maxSessions() + " sessions; close one first");
        }
        int perUser = caller.limits().maxSessions();
        if (perUser > 0 && sessions.values().stream()
            .filter(other -> other != replacing && isActive(other) && session.owner().equals(other.owner()))
            .count() >= perUser) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, session.owner().equals(caller.name())
                    ? "You already have " + perUser + " open sessions, the most you may have on this server; close one first"
                    : session.owner() + " already has " + perUser
                            + " open sessions, the most they may have on this server; close one first");
        }
        sessions.put(session.id(), session);
    }

    /**
     * Starts a failed session's agent again, in its own container, and resumes its task ({@link
     * AgentSession#restart}). Only its owner or an admin may: the session then gets the caller's secrets.
     * @throws ApiException as {@link AgentSession#restart}, 404 as {@link #get}, 429 when full
     */
    AgentSession.Info restart(Caller caller, String id, Map<String, String> secrets) {
        AgentSession session = getToManage(caller, id, "restart it");
        Map<String, String> checked = checkSecrets(secrets);
        // a failed session does not count; started again, it does
        admit(session, caller, session);
        session.restart(caller.name(), checked);
        return session.info();
    }

    /**
     * Opens an archived session again, with the same id, in a new container: the server's checks for a new session
     * apply again (image, capabilities, resources, limits), the repository is cloned again, and the agent's task is
     * imported and resumed ({@link AgentSession#startFromArchive}). Once it runs, the archive is deleted: the
     * session is live again, and archiving it once more makes a new archive. Only the owner or an admin may; the
     * owner stays the archive's.
     * @param secrets values for secrets the archived session had (others are refused)
     * @throws ApiException 404 as {@link #archived}, 403 for users it was shared with, 409 if it has no agent
     * state to resume, and as {@link #create}; the archive stays when it fails
     */
    AgentSession.Info resumeArchived(Caller caller, String id, Map<String, String> secrets, String apiKey,
            List<ProfileFile> userProfile) {
        SessionArchive.Entry entry = readable(caller, id);
        if (!accessPolicy.canDelete(caller, entry)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only the owner of this archived session, or an admin, may resume it");
        }
        Object agentTask = archive.readAgentTask(entry.id());
        if (agentTask == null) {
            throw new ApiException(HttpStatus.CONFLICT, "This archived session has no record of its agent's "
                    + "conversation (its agent could not give one when it was archived), so it can be read, not resumed");
        }
        JsonNode details = entry.details();
        String image = details.path("image").asString("");
        allowedImages.check(image);
        List<String> requested = new ArrayList<>();
        details.path("capabilities").valueStream().forEach(capability -> requested.add(capability.asString("")));
        List<String> checkedCapabilities = Capabilities.check(requested, caller.capabilities());
        JsonNode recorded = details.path("resources");
        Resources resources = Resources.of(recorded.path("memoryBytes").isNumber()
                        ? recorded.path("memoryBytes").asLong() + "B" : null,
                recorded.path("cpus").isNumber() ? recorded.path("cpus").asDouble() : null, properties.docker(),
                caller.limits());
        JsonNode repositoryDetails = details.path("repository");
        GitRepository repository = repositoryDetails.isObject()
                ? GitRepository.of(repositoryDetails.path("url").asString(null),
                        repositoryDetails.path("branch").isString() ? repositoryDetails.path("branch").asString() : null,
                        properties.git().allowedSchemes())
                : null;
        Set<String> archivedSecrets = new HashSet<>();
        details.path("secrets").valueStream().forEach(name -> archivedSecrets.add(name.asString("")));
        Map<String, String> checkedSecrets = checkSecrets(secrets);
        List<String> unknown = checkedSecrets.keySet().stream().filter(name -> !archivedSecrets.contains(name))
            .sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The archived session did not have "
                    + String.join(", ", unknown) + ": secrets can be given again, not added");
        }
        List<ProfileFile> checkedUserProfile = checkUserProfile(userProfile);
        String checkedApiKey = checkApiKey(apiKey);
        String title = details.path("title").asString(image);
        AgentSession session = new AgentSession(new DockerRuntime.ContainerSpec(entry.id(), entry.owner(), image,
                Map.of(), checkedCapabilities, resources), repository, checkedSecrets, checkedUserProfile, title,
                checkedApiKey, entry.sharedWith(), archive.readEvents(entry.id()), docker, properties);
        admit(session, caller, null);
        try {
            session.startFromArchive(caller.name(), agentTask);
        } catch (RuntimeException e) {
            sessions.remove(session.id());
            throw e;
        }
        archive.delete(entry.id());
        log.info("Archived session {} of {} resumed by {} with image {}, secrets {}", id, entry.owner(), caller.name(),
                image, checkedSecrets.keySet());
        return session.info();
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
            if (API_KEY_NAMES.contains(name)) {
                // as a file it would do nothing, and its owner would believe the session uses it
                throw new ApiException(HttpStatus.BAD_REQUEST, "The Bob API key is not a secret file: pass it as "
                        + "apiKey when creating the session (it cannot be changed afterwards)");
            }
            if (value == null || value.length() > limits.maxEnvValueLength()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The secret " + name + " is missing or too long");
            }
            checked.put(name, value);
        });
        return checked;
    }

    /**
     * The API key becomes the value of environment variables, so it must fit in one: no control characters (a
     * line break or NUL would not survive), and no longer than a variable may be.
     * @return the key, or null for the server's
     */
    private String checkApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String key = apiKey.strip();
        if (key.length() > properties.docker().maxEnvValueLength() || key.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The API key is too long or has control characters");
        }
        return key;
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

    /**
     * Writes updated user profile files into the running container. Only the session's owner or an admin may.
     * @param files already validated profile files (from the UI's user-profile directory)
     * @throws ApiException 400 if there are no files, 403 if the user may not, 404 as {@link #get}, 409 if
     * the session is not open
     */
    AgentSession.Info refreshUserProfile(Caller caller, String id, List<ProfileFile> files) {
        AgentSession session = getToManage(caller, id, "refresh its user profile");
        session.refreshUserProfile(caller.name(), checkUserProfile(files));
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

    /**
     * Closes the session, removes its container and forgets it, keeping its transcript in the archive first if
     * asked to. Only its owner or an admin may.
     * @throws ApiException 409 if the owner keeps as many archived sessions as they may, 500 if the archive
     * cannot be written; the session stays open in both cases
     */
    void close(Caller caller, String id, boolean keep) {
        AgentSession session = getToManage(caller, id, keep ? "archive it" : "close it");
        Object agentTask = null;
        if (keep) {
            int most = properties.archive().maxPerUser();
            if (most > 0 && archive.countOwnedBy(session.owner()) >= most) {
                throw new ApiException(HttpStatus.CONFLICT, "There are already " + most + " archived sessions of "
                        + session.owner() + ", the most this server keeps for one user; delete some first");
            }
            // the agent's own record, while it runs, so that the session can be resumed
            agentTask = session.exportTask();
            // once before closing, so that a disk that refuses leaves the session open rather than gone
            archive.write(session.info(), session.events().snapshot(), caller.name(), agentTask);
        }
        session.close();
        sessions.remove(id);
        if (keep) {
            // and again with everything up to the end; if this fails, the first copy stays
            try {
                archive.write(session.info(), session.events().snapshot(), caller.name(), agentTask);
            } catch (ApiException e) {
                log.warn("Session {}: its archive lacks the events of its last moments", id);
            }
        }
        log.info("Session {} {} by {}", id, keep ? "closed and archived" : "closed", caller.name());
    }

    /** The archived sessions the caller may read, newest first: their details as when they closed. */
    List<JsonNode> archived(Caller caller) {
        return archive.list().stream()
            .filter(entry -> accessPolicy.canRead(caller, entry))
            .map(entry -> (JsonNode) entry.details())
            .toList();
    }

    /**
     * One archived session's details.
     * @throws ApiException 404 if there is none or the caller may not read it, as for open sessions
     */
    JsonNode archived(Caller caller, String id) {
        return readable(caller, id).details();
    }

    /** One archived session's events, a JSON array; the caller closes the stream. 404 as {@link #archived}. */
    InputStream archivedEvents(Caller caller, String id) {
        return archive.openEvents(readable(caller, id).id());
    }

    /**
     * Deletes an archived session for good. Only its owner or an admin may.
     * @throws ApiException 403 for users it was shared with, 404 as {@link #archived}
     */
    void deleteArchived(Caller caller, String id) {
        SessionArchive.Entry entry = readable(caller, id);
        if (!accessPolicy.canDelete(caller, entry)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only the owner of this archived session, or an admin, may delete it");
        }
        archive.delete(entry.id());
        log.info("Archived session {} deleted by {}", id, caller.name());
    }

    private SessionArchive.Entry readable(Caller caller, String id) {
        return archive.find(id)
            .filter(entry -> accessPolicy.canRead(caller, entry))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such archived session"));
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

    /**
     * Compacts the session's conversation: the agent summarises it, then goes on from the summary in a new session.
     * It returns once the summary turn has started.
     * @throws ApiException 409 if busy
     */
    void compact(Caller caller, String id, String instructions) {
        AgentSession session = get(caller, id);
        session.compact(caller.name(), instructions);
        log.info("Session {}: compaction asked by {}", id, caller.name());
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
