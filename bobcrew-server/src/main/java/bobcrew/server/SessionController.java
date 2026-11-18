package bobcrew.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP API. The caller ({@link Caller}) comes from the request's token. */
@RestController
@RequestMapping("/api")
class SessionController {

    /** Longest prompt accepted, in characters. */
    static final int MAX_PROMPT_LENGTH = 200_000;

    private static final Duration KEEPALIVE = Duration.ofSeconds(15);

    /**
     * {@code env} values and {@code secrets} values are never logged or returned. Secrets become files in the
     * container rather than variables, so that they are not in every process's environment.
     */
    record CreateRequest(String title, String image, Map<String, String> env, Map<String, String> secrets,
            RepositoryRequest repository, List<String> capabilities, ResourcesRequest resources,
            List<ProfileFile> userProfile, String apiKey) {

        @Override
        public String toString() {
            return "CreateRequest[title=" + title + ", image=" + image + ", env="
                    + (env == null ? "{}" : env.keySet()) + ", secrets=" + (secrets == null ? "{}" : secrets.keySet())
                    + ", repository=" + repository + ", capabilities=" + capabilities + ", resources=" + resources
                    + ", userProfile=" + (userProfile == null ? "[]" : userProfile.stream().map(ProfileFile::path).toList())
                    + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<none>" : "<set>") + "]";
        }
    }

    /** A repository to clone into the new session's workspace; no branch means the default one. */
    record RepositoryRequest(String url, String branch) {
    }

    /** Limits for the new session's container: memory such as {@code "8GB"}, CPUs such as 2 or 0.5. */
    record ResourcesRequest(String memory, Double cpus) {
    }

    record ShareRequest(List<String> users) {
    }

    /** New values for secrets a session has; as in {@link CreateRequest}, the values are never logged. */
    record SecretsRequest(Map<String, String> secrets) {

        @Override
        public String toString() {
            return "SecretsRequest[secrets=" + (secrets == null ? "{}" : secrets.keySet()) + "]";
        }
    }

    record ClearRequest(Boolean wipeWorkspace) {
    }

    record PromptRequest(String text) {
    }

    record ModeRequest(String modeId) {
    }

    record PermissionAnswer(String toolCallId, String optionId) {
    }

    private final SessionManager sessions;

    private final JsonMapper json;

    private final BobCrewProperties properties;

    SessionController(SessionManager sessions, JsonMapper json, BobCrewProperties properties) {
        this.sessions = sessions;
        this.json = json;
        this.properties = properties;
    }

    /**
     * The caller, and what this server lets their sessions ask for: {@code capabilities}, the caps on resource
     * requests ({@code maxMemoryBytes}, {@code maxCpus}; null for none) and how many sessions they may have open
     * ({@code maxSessions}; 0 for no limit of their own). {@code issuer} is {@code oidc} for a token from the
     * identity provider, {@code token} for one made with {@code issue-token}.
     */
    @GetMapping("/me")
    Map<String, Object> me(Caller caller) {
        Map<String, Object> me = new java.util.LinkedHashMap<>();
        me.put("user", caller.name());
        me.put("admin", caller.admin());
        me.put("issuer", caller.source().name().toLowerCase(java.util.Locale.ROOT));
        me.put("capabilities", caller.capabilities());
        me.put("maxSessions", caller.limits().maxSessions());
        me.put("maxMemoryBytes", caller.limits().maxMemoryBytes());
        me.put("maxCpus", caller.limits().maxCpus());
        return me;
    }

    @GetMapping("/sessions")
    List<AgentSession.Info> list(Caller caller) {
        return sessions.list(caller);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    AgentSession.Info create(@RequestBody CreateRequest request, Caller caller) {
        String title = request.title();
        if (title != null && title.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The title is too long");
        }
        GitRepository repository = request.repository() == null ? null
                : GitRepository.of(request.repository().url(), request.repository().branch(),
                        properties.git().allowedSchemes());
        Resources resources = request.resources() == null ? Resources.of(null, null, properties.docker(), caller.limits())
                : Resources.of(request.resources().memory(), request.resources().cpus(), properties.docker(),
                        caller.limits());
        return sessions.create(caller, title, request.image(), request.env(), request.secrets(),
                request.userProfile(), repository, request.capabilities(), resources, request.apiKey()).info();
    }

    @GetMapping("/sessions/{id}")
    AgentSession.Info get(@PathVariable String id, Caller caller) {
        return sessions.get(caller, id).info();
    }

    @GetMapping("/sessions/{id}/file")
    void downloadFile(@PathVariable String id, @RequestParam(value = "path", required = false) String rawPath,
            Caller caller,
            HttpServletResponse response) throws IOException {
        if (!properties.fileDownloadsEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "File downloads are disabled on this server");
        }
        AgentSession session = sessions.get(caller, id);
        try (DockerRuntime.ContainerFile file = session.readFile(rawPath)) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\""
                    + file.name().replace("\\", "_").replace("\"", "_").replaceAll("[\\r\\n]", "_") + "\"");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentLengthLong(file.size());
            file.content().transferTo(response.getOutputStream());
        }
    }

    /**
     * Puts the request body into the session's workspace at {@code path} (relative to the workspace, or absolute
     * inside it), creating directories and replacing a file already there.
     */
    @PutMapping("/sessions/{id}/files")
    @ResponseStatus(HttpStatus.CREATED)
    AgentSession.Upload upload(@PathVariable String id, @RequestParam(value = "path", required = false) String path,
            Caller caller, HttpServletRequest request) throws IOException {
        if (!properties.fileUploadsEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "File uploads are disabled on this server");
        }
        long max = properties.maxUploadSize().toBytes();
        if (request.getContentLengthLong() > max) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "The file is larger than the server accepts ("
                    + max / (1024 * 1024) + " MB)");
        }
        AgentSession session = sessions.get(caller, id);
        return session.upload(caller.name(), path, request.getInputStream(), max);
    }

    /** A snapshot of the session container's resource use; Docker samples for a second or two. */
    @GetMapping("/sessions/{id}/metrics")
    ContainerMetrics metrics(@PathVariable String id, Caller caller) {
        return sessions.metrics(caller, id);
    }

    /**
     * Shares the session with exactly the users named (replacing the earlier list; {@code []} for nobody). Only
     * the owner or an admin may.
     */
    @PutMapping("/sessions/{id}/sharing")
    AgentSession.Info share(@PathVariable String id, @RequestBody ShareRequest request, Caller caller) {
        return sessions.share(caller, id, request.users());
    }

    /**
     * Replaces the values of secrets the session was created with (an expired token, say), in the running
     * container. Only the owner or an admin may.
     */
    @PutMapping("/sessions/{id}/secrets")
    AgentSession.Info refreshSecrets(@PathVariable String id, @RequestBody SecretsRequest request, Caller caller) {
        return sessions.refreshSecrets(caller, id, request.secrets());
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@PathVariable String id, Caller caller) {
        sessions.close(caller, id);
    }

    @PostMapping("/sessions/{id}/prompt")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void prompt(@PathVariable String id, @RequestBody PromptRequest request, Caller caller) {
        String text = request.text();
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The prompt must not be empty");
        }
        if (text.length() > MAX_PROMPT_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "The prompt is too long");
        }
        sessions.get(caller, id).prompt(caller.name(), text);
    }

    @PostMapping("/sessions/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void cancel(@PathVariable String id, Caller caller) {
        sessions.get(caller, id).cancel();
    }

    /**
     * Starts the conversation over; the session stays open. The workspace is wiped too, unless the body says
     * {@code {"wipeWorkspace": false}}.
     */
    @PostMapping("/sessions/{id}/clear")
    AgentSession.Info clear(@PathVariable String id, @RequestBody(required = false) ClearRequest request,
            Caller caller) {
        boolean wipe = request == null || request.wipeWorkspace() == null || request.wipeWorkspace();
        return sessions.clear(caller, id, wipe);
    }

    @PostMapping("/sessions/{id}/mode")
    AgentSession.Info mode(@PathVariable String id, @RequestBody ModeRequest request, Caller caller) {
        if (request.modeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "modeId is required");
        }
        AgentSession session = sessions.get(caller, id);
        session.setMode(request.modeId());
        return session.info();
    }

    /**
     * The tool call id travels in the body, not the path: agents choose it freely (Bob's contain
     * {@code /} and {@code +}), and an encoded slash in a path is refused by the web server with 400.
     */
    @PostMapping("/sessions/{id}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void answerPermission(@PathVariable String id, @RequestBody PermissionAnswer answer, Caller caller) {
        if (answer.toolCallId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "toolCallId is required");
        }
        if (answer.optionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "optionId is required");
        }
        sessions.get(caller, id).answerPermission(caller.name(), answer.toolCallId(), answer.optionId());
    }

    /**
     * The session's events after {@code after} (or after the {@code Last-Event-ID} header, which
     * a reconnecting browser sends) as Server-Sent Events: the kept ones at once, then new ones as
     * they happen. Each event's id is its sequence number. When the session is closed the stream
     * finishes with an event named {@code end}.
     *
     * <p>The stream is written from this request's own (virtual) thread until the client goes away, or until the
     * user may no longer use the session (its owner stopped sharing it): access is checked again before every
     * event, so nothing after that reaches them.
     */
    @GetMapping("/sessions/{id}/events")
    void events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId, Caller caller,
            HttpServletResponse response) throws IOException {
        AgentSession session = sessions.get(caller, id);
        long from = after;
        if (lastEventId != null) {
            try {
                from = Long.parseLong(lastEventId.strip());
            } catch (NumberFormatException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Last-Event-ID must be a number");
            }
        }
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        try (EventLog.Subscription subscription = session.events().subscribe(from)) {
            OutputStream out = response.getOutputStream();
            write(out, ": connected\n\n");
            while (!subscription.isFinished()) {
                UiEvent event = subscription.poll(KEEPALIVE);
                if (!sessions.canUse(caller, session)) {
                    // a reconnecting client then gets 404, like any session it may not see
                    return;
                }
                if (event == null) {
                    if (!subscription.isFinished()) {
                        // also how a client that went away is noticed
                        write(out, ": keepalive\n\n");
                    }
                } else {
                    write(out, "id: " + event.seq() + "\ndata: " + json.writeValueAsString(event) + "\n\n");
                }
            }
            if (subscription.isLogClosed()) {
                write(out, "event: end\ndata: {}\n\n");
            }
        } catch (IOException e) {
            // the client disconnected
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> onApiException(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
