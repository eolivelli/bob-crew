package bobcrew.server;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * One agent process and the ACP session running in it: starts the process, sends prompts,
 * records everything the agent reports in the session's {@link EventLog}, and holds the agent's
 * permission requests until a user answers them.
 */
final class AgentSession {

    enum Status {
        /** The agent process is being started. */
        STARTING,
        /** Ready for a prompt. */
        IDLE,
        /** A prompt turn is in progress. */
        RUNNING,
        /** The agent process ended by itself or could not be started. */
        FAILED,
        /** Closed on request. */
        CLOSED
    }

    /** What clients see of a session. */
    record Info(String id, String owner, String workspace, String title, Status status, String agent,
            String currentMode, List<Mode> modes, Instant createdAt, Instant updatedAt, long lastSeq) {
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

    /** Serializes process starts, so the child process each one creates can be told apart. */
    private static final Object START_LOCK = new Object();

    private final String id;

    private final String owner;

    private final String title;

    private final Path workspace;

    private final String workspaceName;

    private final BobCrewProperties properties;

    private final EventLog events;

    private final Instant createdAt = Instant.now();

    private final Map<String, PendingPermission> pendingPermissions = new ConcurrentHashMap<>();

    private volatile Instant updatedAt = createdAt;

    private volatile Status status = Status.STARTING;

    private volatile AcpSyncClient client;

    private volatile ProcessHandle process;

    private volatile String acpSessionId;

    private volatile String agentName;

    private volatile String currentMode;

    private volatile List<Mode> modes = List.of();

    AgentSession(String id, String owner, String title, Path workspace, String workspaceName,
            BobCrewProperties properties) {
        this.id = id;
        this.owner = owner;
        this.title = title;
        this.workspace = workspace;
        this.workspaceName = workspaceName;
        this.properties = properties;
        this.events = new EventLog(properties.eventBufferSize());
    }

    String id() {
        return id;
    }

    String owner() {
        return owner;
    }

    Status status() {
        return status;
    }

    Path workspace() {
        return workspace;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    EventLog events() {
        return events;
    }

    Info info() {
        return new Info(id, owner, workspaceName, title, status, agentName, currentMode, modes, createdAt, updatedAt,
                events.lastSeq());
    }

    /**
     * Starts the agent process, initializes ACP and creates the agent's session.
     * @throws ApiException with 502 if the agent cannot be started or refuses the session
     */
    void start() {
        BobCrewProperties.Agent agent = properties.agent();
        AgentParameters.Builder parameters = AgentParameters.builder(agent.binary())
            .args(agent.arguments())
            .inheritEnvironment(agent.inheritEnvironment());
        if (agent.apiKey() != null && !agent.apiKey().isBlank()) {
            parameters.addEnvVar("BOB_API_KEY", agent.apiKey());
            parameters.addEnvVar("BOBSHELL_API_KEY", agent.apiKey());
        }
        StdioAcpClientTransport transport = new StdioAcpClientTransport(parameters.build());
        transport.setStdErrorHandler(line -> agentStderr.debug("[{}] {}", id, line));
        try {
            AcpSyncClient newClient;
            AcpSchema.InitializeResponse initialized;
            synchronized (START_LOCK) {
                // the SDK does not expose the process it starts: it is the child that appears here
                Set<Long> before = childPids();
                newClient = AcpClient.sync(transport)
                    .requestTimeout(properties.requestTimeout())
                    .promptTimeout(properties.promptTimeout())
                    .clientInfo(new AcpSchema.Implementation("bob-crew", "0.1.0", "bob-crew"))
                    // no file system or terminal capabilities: the agent works with its own tools
                    .clientCapabilities(new AcpSchema.ClientCapabilities())
                    .sessionUpdateHandler(this::onUpdate)
                    .requestPermissionHandler(this::onPermissionRequest)
                    .build();
                this.client = newClient;
                initialized = newClient.initialize();
                this.process = ProcessHandle.current()
                    .children()
                    .filter(child -> !before.contains(child.pid()))
                    .findFirst()
                    .orElse(null);
            }
            if (this.process == null) {
                log.warn("Session {}: could not identify the agent process; its exit will not be noticed", id);
            }
            if (initialized.agentInfo() != null) {
                AcpSchema.Implementation info = initialized.agentInfo();
                this.agentName = (info.title() != null ? info.title() : info.name()) + " " + info.version();
            }
            AcpSchema.NewSessionResponse created = newClient
                .newSession(new AcpSchema.NewSessionRequest(workspace.toString(), List.of()));
            useSession(created);
        } catch (RuntimeException e) {
            log.warn("Session {}: the agent could not be started: {}", id, e.toString());
            setStatus(Status.FAILED);
            shutdown();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The agent could not start a session: " + rootMessage(e));
        }
        ProcessHandle handle = this.process;
        if (handle != null) {
            handle.onExit().thenRun(this::onProcessExit);
        }
        setStatus(Status.IDLE);
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
     * Starts the conversation over without ending the session: runs {@code wipeWorkspace}, opens a
     * fresh ACP session in the same agent process, forgets the recorded events and records a
     * {@link UiEvent#CLEARED} event. The session counts as starting meanwhile, so no prompt gets in.
     * If it fails, the session is as it was (except for what {@code wipeWorkspace} already removed).
     * @throws ApiException with 409 if the session is not idle, 502 if the agent refuses a new session
     */
    Info clear(String user, Runnable wipeWorkspace) {
        synchronized (this) {
            if (status != Status.IDLE) {
                throw new ApiException(HttpStatus.CONFLICT, "The session is " + status.name().toLowerCase()
                        + "; it can only be cleared when idle (stop the running turn first)");
            }
            setStatus(Status.STARTING);
        }
        try {
            wipeWorkspace.run();
            AcpSchema.NewSessionResponse created;
            try {
                created = client.newSession(new AcpSchema.NewSessionRequest(workspace.toString(), List.of()));
            } catch (RuntimeException e) {
                log.warn("Session {}: the agent refused a new session: {}", id, e.toString());
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "The agent could not start a fresh session: " + rootMessage(e));
            }
            useSession(created);
            events.discard();
            events.append(UiEvent.CLEARED, user, Map.of());
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
        synchronized (this) {
            if (status != Status.IDLE) {
                throw new ApiException(HttpStatus.CONFLICT, "The session is " + status.name().toLowerCase()
                        + " and cannot take a prompt now");
            }
            events.append(UiEvent.USER_PROMPT, user, Map.of("text", text));
            setStatus(Status.RUNNING);
        }
        Thread.ofVirtual().name("prompt-" + id).start(() -> {
            try {
                AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(acpSessionId,
                        List.of(new AcpSchema.TextContent(text))));
                events.append(UiEvent.TURN_END, null, Map.of("stopReason", response.stopReason()));
            } catch (AcpConnectionException e) {
                // the connection to the agent is gone, so the session is too
                onProcessExit();
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

    /** Closes the session and ends its agent process. */
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
        ProcessHandle handle = this.process;
        // collected before the agent exits: afterwards its children are no longer reachable from it
        List<ProcessHandle> tree = new ArrayList<>();
        if (handle != null) {
            handle.descendants().forEach(tree::add);
            tree.add(handle);
        }
        AcpSyncClient current = this.client;
        if (current != null) {
            try {
                current.closeGracefully();
            } catch (RuntimeException e) {
                log.debug("Session {}: closing the client failed: {}", id, e.toString());
            }
        }
        for (ProcessHandle member : tree) {
            if (member.isAlive()) {
                member.destroyForcibly();
            }
        }
        events.close();
    }

    private void onProcessExit() {
        synchronized (this) {
            if (status == Status.CLOSED || status == Status.FAILED) {
                return;
            }
            log.warn("Session {}: the agent process ended unexpectedly", id);
            events.append(UiEvent.ERROR, null, Map.of("message", "The agent process ended unexpectedly"));
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

    private static Set<Long> childPids() {
        return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
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
