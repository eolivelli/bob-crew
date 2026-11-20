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
            List<String> secrets, boolean ownApiKey, GitRepository repository,
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

    /** A note for the next prompt after the session was resumed: what changed around the agent; then null. */
    private volatile String resumeNote;

    /**
     * After a compaction: the summary the agent wrote of the conversation before it, which goes before the next
     * prompt, since the agent's new task knows nothing else; then null.
     */
    private volatile String compactNote;

    /** While a compaction's summary turn runs: the agent's message so far, which is the summary; null otherwise. */
    private volatile StringBuilder compactionSummary;

    /** Whether the agent offers Bob's task export and import ({@code bob/extensions}), so it can be resumed later. */
    private volatile boolean taskTransfer;

    static final String EXPORT_TASK = "_bob/task/export";

    static final String IMPORT_TASK = "_bob/task/import";

    /** Users other than the owner who may use the session; see {@link SessionAccessPolicy}. */
    private volatile Set<String> sharedWith = Set.of();

    /** The names of the session's secrets (files in {@link DockerRuntime#SECRETS_DIR}); never their values. */
    private final List<String> secretNames;

    /** The secrets' values until they are written into the container, then null: the server keeps no copy. */
    private volatile Map<String, String> pendingSecrets;

    /**
     * The session's own Bob API key until its container is created with it, then null; null too when the session
     * uses the server's key.
     */
    private volatile String pendingApiKey;

    /** Whether the agent runs with a key given for this session rather than the server's. */
    private final boolean ownApiKey;

    /** The user profile files until they are written into the container, then null. */
    private volatile List<ProfileFile> pendingUserProfile;

    /** Held while secrets are replaced in the running container, so that refreshes do not interleave. */
    private final java.util.concurrent.locks.ReentrantLock secretsRefresh = new java.util.concurrent.locks.ReentrantLock();

    /** Why writing the secrets failed, if it did: reported as is rather than as a failure of the agent. */
    private volatile ApiException secretsFailure;

    /** Whether the repository is in the workspace: cloned, and not wiped since. */
    private volatile boolean repositoryPresent;

    /**
     * @param repository cloned into the workspace when the session starts, or null
     * @param secrets written as files into the container when it starts (names already checked); kept until then
     * @param userProfile files written into /home/bob in the container before the agent starts
     * @param apiKey the Bob API key for this session's agent (already checked), or null for the server's
     */
    AgentSession(DockerRuntime.ContainerSpec spec, GitRepository repository, Map<String, String> secrets,
            List<ProfileFile> userProfile, String title, String apiKey, DockerRuntime docker,
            BobCrewProperties properties) {
        this(spec, repository, secrets, userProfile, title, apiKey, Set.of(), List.of(), docker, properties);
    }

    /**
     * A session that goes on from an archived one ({@link #startFromArchive}).
     * @param sharedWith who it was shared with
     * @param history its earlier events, oldest first: the session's own go on after them
     */
    AgentSession(DockerRuntime.ContainerSpec spec, GitRepository repository, Map<String, String> secrets,
            List<ProfileFile> userProfile, String title, String apiKey, Set<String> sharedWith, List<UiEvent> history,
            DockerRuntime docker, BobCrewProperties properties) {
        this.sharedWith = Set.copyOf(sharedWith);
        this.pendingApiKey = apiKey;
        this.ownApiKey = apiKey != null;
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
        this.events = new EventLog(properties.eventBufferSize(), history);
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

    /**
     * Replaces the values of some of the session's secrets in the running container, for credentials that expire
     * (a GitHub token): the files change, the image's setup runs again with them, and a
     * {@link UiEvent#SECRETS_REFRESHED} event records who did it and which names, never the values. Only secrets
     * the session was created with can be replaced: which secrets a session has is fixed, so that its details
     * and what the agent was told stay true. Who may do this is {@link SessionAccessPolicy}'s decision, made
     * before. The values are not kept.
     * @param secrets already checked names and values
     * @throws ApiException 400 for no secrets or one the session does not have, 409 if the session is not open
     */
    void refreshSecrets(String by, Map<String, String> secrets) {
        requireOpen();
        if (secrets.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "secrets is required: the secrets to replace");
        }
        List<String> names = secrets.keySet().stream().sorted().toList();
        List<String> unknown = names.stream().filter(name -> !secretNames.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The session was not created with "
                    + String.join(", ", unknown) + ": its secrets can be replaced, not added to (it has "
                    + (secretNames.isEmpty() ? "none" : String.join(", ", secretNames)) + ")");
        }
        // one at a time: two refreshes interleaved could leave values of both. A lock, not synchronized: the
        // writes block on Docker, and that would pin the virtual thread
        secretsRefresh.lock();
        try {
            requireOpen();
            docker.writeSecrets(containerId, secrets, properties.requestTimeout());
            touch();
            events.append(UiEvent.SECRETS_REFRESHED, by, Map.of("names", names));
            log.info("Session {}: {} refreshed the secrets {}", id, by, names);
            docker.applyProfile(containerId, properties.requestTimeout());
        } finally {
            secretsRefresh.unlock();
        }
    }

    /**
     * Writes updated user profile files into {@code /home/bob} of the running container. Any file already
     * there at the same path is replaced atomically. A {@link UiEvent#USER_PROFILE_REFRESHED} event records
     * who did it and how many files were written. Who may do this is {@link SessionAccessPolicy}'s decision,
     * made before.
     * @param files already validated profile files
     * @throws ApiException 400 if there are no files, 409 if the session is not open
     */
    void refreshUserProfile(String by, List<ProfileFile> files) {
        requireOpen();
        if (files.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No user profile files found; nothing to refresh");
        }
        // serialise with secret refreshes so the two do not interleave inside the container
        secretsRefresh.lock();
        try {
            requireOpen();
            docker.writeUserProfile(containerId, files, properties.requestTimeout());
            touch();
            events.append(UiEvent.USER_PROFILE_REFRESHED, by, Map.of("count", files.size()));
            log.info("Session {}: {} refreshed {} user profile file(s)", id, by, files.size());
        } finally {
            secretsRefresh.unlock();
        }
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
                DockerRuntime.shortId(containerId), secretNames, ownApiKey, repository, spec.capabilities(), spec.resources(),
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
        launch(true, opened -> useSession(opened.newSession(
                new AcpSchema.NewSessionRequest(DockerRuntime.WORKSPACE, List.of()))));
        ready();
    }

    /**
     * Starts the session of an archived one, in a new container: the repository is cloned again, and the agent's
     * task, exported when it was archived ({@link #exportTask}), is imported and resumed, so the agent goes on
     * with the conversation. The session's events (given to the constructor) go on after the archived ones.
     * @param snapshot what Bob's {@code _bob/task/export} answered, as plain JSON values
     * @throws ApiException as {@link #start}
     */
    void startFromArchive(String by, Object snapshot) {
        // the conversation already had its notes
        firstPrompt = false;
        launch(true, opened -> {
            String imported = importTask(opened, snapshot);
            resumeOrStartOver(opened, imported);
        });
        resumeNote = "[Note: this conversation was archived and has now been resumed, in a new container. The files "
                + "the workspace had before are gone"
                + (repositoryPresent ? ", except the repository, cloned again at " + repository.path()
                        + ": changes to it that were not pushed are lost" : "")
                + ". Commands that were running then are not running now.]";
        events.append(UiEvent.RESUMED, by, Map.of("from", "archive"));
        log.info("Session {}: resumed from its archive by {}", id, by);
        ready();
    }

    /**
     * Starts the session's container again after its agent stopped unexpectedly ({@link Status#FAILED}), and
     * resumes the agent's task in it: the container is the same, so the workspace, the agent's own records (Bob's
     * task database) and whatever was installed are as they were. Its secrets are written again, since their
     * tmpfs is new; which secrets it has does not change, as for {@link #refreshSecrets}. The API key is in the
     * container's environment already. Who may do this is {@link SessionAccessPolicy}'s decision, made before.
     * @param secrets already checked names and values
     * @throws ApiException 409 if the session is not failed or its container is gone, 400 for a secret the
     * session does not have, 502 if the agent cannot be started again (the container stays, for another try)
     */
    void restart(String by, Map<String, String> secrets) {
        List<String> unknown = secrets.keySet().stream().filter(name -> !secretNames.contains(name)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The session was not created with "
                    + String.join(", ", unknown) + " (it has "
                    + (secretNames.isEmpty() ? "no secrets" : String.join(", ", secretNames)) + ")");
        }
        synchronized (this) {
            if (status != Status.FAILED) {
                throw new ApiException(HttpStatus.CONFLICT, "The session is " + status.name().toLowerCase()
                        + "; only a session whose agent stopped can be restarted");
            }
            if (containerId == null || acpSessionId == null || !docker.exists(containerId)) {
                throw new ApiException(HttpStatus.CONFLICT, "This session's container is gone, or its agent never "
                        + "started a conversation, so there is nothing to resume; close it and start a new one");
            }
            pendingSecrets = Map.copyOf(secrets);
            secretsFailure = null;
            setStatus(Status.STARTING);
        }
        AcpSyncClient previous = client;
        closeQuietly(previous);
        String task = acpSessionId;
        launch(false, opened -> resumeOrStartOver(opened, task));
        resumeNote = "[Note: your container stopped unexpectedly and has been started again. The workspace's files "
                + "are as they were, but commands that were running then are not running now.]";
        events.append(UiEvent.RESUMED, by, Map.of("from", "crash"));
        log.info("Session {}: restarted by {}", id, by);
        ready();
    }

    /** What opens the agent's session once ACP is initialized: a new one, or a task resumed. */
    @FunctionalInterface
    private interface Opener {
        void open(AcpSyncClient client);
    }

    /**
     * Connects to the session's container (created first when {@code create}), initializes ACP and opens the
     * agent's session. On failure the session is {@link Status#FAILED}; a container this call created is
     * removed, one that was there already (a restart) is kept for another try.
     */
    private void launch(boolean create, Opener opener) {
        try {
            String container;
            if (create) {
                String apiKey = pendingApiKey;
                pendingApiKey = null;
                container = docker.create(spec, apiKey);
                this.containerId = container;
            } else {
                container = containerId;
            }
            // connect attaches before it starts the container (an exited one too), so that no output is lost
            ContainerAcpTransport transport = new ContainerAcpTransport(docker, container,
                    line -> agentStderr.debug("[{}] {}", id, line), reason -> onAgentExit(),
                    () -> writeSecrets(container));
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
            this.taskTransfer = offersTaskTransfer(initialized);
            if (create && repository != null) {
                docker.cloneRepository(container, repository, properties.git().cloneTimeout());
                repositoryPresent = true;
                Map<String, Object> cloned = new LinkedHashMap<>();
                cloned.put("url", repository.url());
                if (repository.branch() != null) {
                    cloned.put("branch", repository.branch());
                }
                cloned.put("path", repository.path());
                events.append(UiEvent.REPOSITORY_CLONED, null, cloned);
            }
            opener.open(newClient);
        } catch (ApiException e) {
            failToStart(create);
            throw e;
        } catch (RuntimeException e) {
            pendingSecrets = null;
            pendingUserProfile = null;
            failToStart(create);
            if (secretsFailure != null) {
                throw secretsFailure;
            }
            log.warn("Session {}: the agent could not be started: {}", id, e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The agent could not start a session: " + rootMessage(e));
        }
    }

    private void failToStart(boolean created) {
        setStatus(Status.FAILED);
        if (created) {
            shutdown();
        } else {
            cancelPendingPermissions();
            closeQuietly(client);
        }
    }

    private synchronized void ready() {
        // the container may already have died while starting
        if (status == Status.STARTING) {
            setStatus(Status.IDLE);
        }
    }

    /**
     * Opens the agent's task {@code task} with {@code session/resume}, which does not replay it: the session's own
     * events already show it. When the agent cannot (it does not have the task, or does not resume at all), the
     * session goes on with a new conversation, and an event says so, rather than failing a session whose container
     * works.
     */
    private void resumeOrStartOver(AcpSyncClient opened, String task) {
        if (task != null) {
            try {
                AcpSchema.ResumeSessionResponse resumed = opened.resumeSession(
                        new AcpSchema.ResumeSessionRequest(task, DockerRuntime.WORKSPACE, List.of()));
                this.acpSessionId = task;
                useModes(resumed.modes());
                return;
            } catch (AcpConnectionException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Session {}: the agent did not resume its task: {}", id, e.toString());
            }
        }
        events.append(UiEvent.ERROR, null, Map.of("message", "The agent could not resume its conversation, so this "
                + "is a new one: it does not know what was said before."));
        firstPrompt = true;
        useSession(opened.newSession(new AcpSchema.NewSessionRequest(DockerRuntime.WORKSPACE, List.of())));
    }

    /** Bob's {@code _bob/task/import}: the archived task, copied into this agent; null if it cannot. */
    private String importTask(AcpSyncClient opened, Object snapshot) {
        if (!taskTransfer || snapshot == null) {
            return null;
        }
        try {
            Object answer = opened.sendExtRequest(IMPORT_TASK,
                    Map.of("cwd", DockerRuntime.WORKSPACE, "snapshot", snapshot));
            if (answer instanceof Map<?, ?> result && result.get("sessionIds") instanceof List<?> ids && !ids.isEmpty()
                    && ids.get(0) instanceof String imported) {
                return imported;
            }
            log.warn("Session {}: the agent's import answered no task", id);
        } catch (AcpConnectionException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Session {}: the agent did not import the archived task: {}", id, e.toString());
        }
        return null;
    }

    /**
     * The agent's own record of the conversation, for an archive to resume it later: Bob's {@code _bob/task/export}
     * of the current task, as plain JSON values. Null when the agent does not offer it or is not running.
     */
    Object exportTask() {
        AcpSyncClient current = client;
        String task = acpSessionId;
        if (!taskTransfer || current == null || task == null || (status != Status.IDLE && status != Status.RUNNING)) {
            return null;
        }
        try {
            return current.sendExtRequest(EXPORT_TASK, Map.of("sessionId", task));
        } catch (RuntimeException e) {
            log.warn("Session {}: the agent did not export its task: {}", id, e.toString());
            return null;
        }
    }

    /** Bob advertises its task-transfer requests as {@code agentCapabilities._meta["bob/extensions"].version}. */
    private static boolean offersTaskTransfer(AcpSchema.InitializeResponse initialized) {
        AcpSchema.AgentCapabilities capabilities = initialized.agentCapabilities();
        if (capabilities == null || capabilities.meta() == null) {
            return false;
        }
        return capabilities.meta().get("bob/extensions") instanceof Map<?, ?> extensions
                && extensions.get("version") instanceof Number version && version.intValue() >= 1;
    }

    private void closeQuietly(AcpSyncClient old) {
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException e) {
                log.debug("Session {}: closing the old client failed: {}", id, e.toString());
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
        useModes(created.modes());
    }

    private void useModes(AcpSchema.SessionModeState state) {
        if (state != null) {
            this.currentMode = state.currentModeId();
            this.modes = state.availableModes().stream().map(mode -> new Mode(mode.id(), mode.name())).toList();
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
            compactNote = null;
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
                if (timedOut(e)) {
                    onPromptTimeout();
                } else {
                    log.warn("Session {}: prompt failed: {}", id, e.toString());
                    events.append(UiEvent.ERROR, null, Map.of("message", "The prompt failed: " + rootMessage(e)));
                }
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

    /**
     * Compacts the conversation, as Bob Shell's {@code /compact} does in its terminal, which its ACP server does not
     * offer: the agent is asked, in a turn of its own, for a summary of the conversation (Bob's own template, plus
     * the user's instructions if any), then a fresh ACP session (for Bob, a new task) takes the old one's place in
     * the same container, and the summary goes before the next prompt, with the notes a first prompt carries. The
     * transcript stays as it is: the summary is in it, between a {@link UiEvent#COMPACTING} and a
     * {@link UiEvent#COMPACTED} event. The summary turn runs like any other (status RUNNING, Stop works); if it is
     * stopped, fails or writes nothing, or the agent refuses the new session, an {@code error} event says so and the
     * conversation goes on as before. It returns at once.
     * @param instructions what the summary should keep or focus on, or null
     * @throws ApiException with 409 if the session is not idle
     */
    void compact(String user, String instructions) {
        synchronized (this) {
            if (status != Status.IDLE) {
                throw new ApiException(HttpStatus.CONFLICT, "The session is " + status.name().toLowerCase()
                        + "; it can only be compacted when idle (stop the running turn first)");
            }
            compactionSummary = new StringBuilder();
            events.append(UiEvent.COMPACTING, user,
                    instructions == null ? Map.of() : Map.of("instructions", instructions));
            setStatus(Status.RUNNING);
        }
        Thread.ofVirtual().name("compact-" + id).start(() -> {
            String failure = null;
            try {
                AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(acpSessionId,
                        List.of(new AcpSchema.TextContent(compactionPrompt(instructions)))));
                events.append(UiEvent.TURN_END, null, Map.of("stopReason", response.stopReason()));
                String summary = compactionSummary.toString().strip();
                String stopReason = response.stopReason() == null ? "" : response.stopReason().value();
                if (!"end_turn".equals(stopReason)) {
                    failure = "the agent did not finish the summary (" + stopReason + ")";
                } else if (summary.isEmpty()) {
                    failure = "the agent wrote no summary";
                } else {
                    failure = continueFromSummary(user, summary);
                }
            } catch (AcpConnectionException e) {
                onAgentExit();
            } catch (RuntimeException e) {
                if (timedOut(e)) {
                    onPromptTimeout();
                    failure = "the summary took too long";
                } else {
                    log.warn("Session {}: the compaction's summary failed: {}", id, e.toString());
                    failure = rootMessage(e);
                }
            } finally {
                compactionSummary = null;
                cancelPendingPermissions();
            }
            if (failure != null) {
                events.append(UiEvent.ERROR, null, Map.of("message",
                        "The conversation was not compacted: " + failure + ". It goes on as before."));
            }
            synchronized (this) {
                if (status == Status.RUNNING) {
                    setStatus(Status.IDLE);
                }
            }
        });
    }

    /** Bob's compaction prompt (its {@code /compact}), so that the summary is the one Bob itself would write. */
    static String compactionPrompt(String instructions) {
        String prompt = """
                The conversation is about to be compacted: it will go on in a new session, from a summary you write \
                now. Do not run any tools; reply with the summary only.

                Provide a detailed summary for continuing this conversation.
                Focus on information helpful for continuation: what was done, what is being worked on, which files \
                are involved, and what's next.

                Use this template:
                ---
                ## Goal
                [What goal(s) is the user trying to accomplish?]

                ## Instructions
                [Important instructions the user gave that are still relevant]
                [If there is a plan or spec, include info so next agent can continue using it]

                ## Discoveries
                [Notable things learned during this conversation useful for continuing]

                ## Accomplished
                [What work has been completed, what's in progress, what's left?]

                ## Relevant files / directories
                [Structured list of relevant files read, edited, or created]
                ---""";
        return instructions == null ? prompt : prompt + "\n\nAdditional instructions from the user:\n" + instructions;
    }

    /**
     * Opens the agent's new session after a compaction, in the mode the old one was in; returns why it could not, or
     * null. The status is still RUNNING, so no prompt gets in meanwhile.
     */
    private String continueFromSummary(String user, String summary) {
        String mode = currentMode;
        AcpSchema.NewSessionResponse created;
        try {
            created = client.newSession(new AcpSchema.NewSessionRequest(DockerRuntime.WORKSPACE, List.of()));
        } catch (RuntimeException e) {
            log.warn("Session {}: the agent refused a new session for the compaction: {}", id, e.toString());
            return "the agent could not start a new session (" + rootMessage(e) + ")";
        }
        useSession(created);
        if (mode != null && !mode.equals(currentMode) && modes.stream().anyMatch(known -> known.id().equals(mode))) {
            try {
                client.setSessionMode(new AcpSchema.SetSessionModeRequest(acpSessionId, mode));
                currentMode = mode;
            } catch (RuntimeException e) {
                log.warn("Session {}: the new session did not take mode {}: {}", id, mode, e.toString());
            }
        }
        firstPrompt = true;
        compactNote = "[Note: to keep the context small, the conversation so far was compacted. You are going on from "
                + "this summary of it, which you wrote:]\n\n" + summary;
        events.append(UiEvent.COMPACTED, user, Map.of("summaryLength", summary.length()));
        log.info("Session {}: compacted by {}", id, user);
        return null;
    }

    /**
     * The turn outlasted {@code bobcrew.prompt-timeout}. The SDK has given up on the prompt and told the agent with
     * {@code $/cancel_request}, which agents such as Bob do not act on: the agent would go on working in a session
     * that says it is idle. So it is asked to stop the way ACP defines for a turn, {@code session/cancel}, and the
     * event says what happened in the user's terms.
     */
    private void onPromptTimeout() {
        Duration limit = properties.promptTimeout();
        log.warn("Session {}: the turn took longer than {}; asking the agent to stop", id, limit);
        events.append(UiEvent.ERROR, null, Map.of("message", "The turn took longer than this server allows ("
                + limit.toString().substring(2).toLowerCase() + ", bobcrew.prompt-timeout), so the agent was asked "
                + "to stop. Use Stop if it goes on working."));
        try {
            client.cancel(new AcpSchema.CancelNotification(acpSessionId));
        } catch (RuntimeException e) {
            log.warn("Session {}: the agent did not take the cancellation: {}", id, e.toString());
        }
    }

    private static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asks the agent to stop: the current turn, with pending permission requests answered as cancelled, or, when
     * idle, whatever it still does. Agents such as Bob can end a turn while work it started (a background command,
     * a sub-task) goes on sending updates; ACP defines {@code session/cancel} for a running turn only, but it is a
     * notification, so sending it when idle is harmless, and it is the only way to ask for that work to stop.
     */
    void cancel() {
        Status current = status;
        if (current != Status.RUNNING && current != Status.IDLE) {
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
        String resumed = resumeNote;
        if (resumed != null) {
            resumeNote = null;
            notes.append(resumed).append("\n\n");
        }
        String compacted = compactNote;
        if (compacted != null) {
            compactNote = null;
            notes.append(compacted).append("\n\n[End of the summary. The user's new message follows.]\n\n");
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
        StringBuilder summary = compactionSummary;
        if (summary != null && update instanceof AcpSchema.AgentMessageChunk chunk
                && chunk.content() instanceof AcpSchema.TextContent text && text.text() != null) {
            synchronized (summary) {
                summary.append(text.text());
            }
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
