package bobcrew.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP API. The user is the subject of the request's token. */
@RestController
@RequestMapping("/api")
class SessionController {

    /** Longest prompt accepted, in characters. */
    static final int MAX_PROMPT_LENGTH = 200_000;

    private static final Duration KEEPALIVE = Duration.ofSeconds(15);

    record CreateRequest(String workspace, String title) {
    }

    record PromptRequest(String text) {
    }

    record ModeRequest(String modeId) {
    }

    record PermissionAnswer(String optionId) {
    }

    private final SessionManager sessions;

    private final JsonMapper json;

    SessionController(SessionManager sessions, JsonMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @GetMapping("/me")
    Map<String, String> me(Principal principal) {
        return Map.of("user", principal.getName());
    }

    @GetMapping("/workspaces")
    List<String> workspaces() {
        return sessions.workspaces();
    }

    @GetMapping("/sessions")
    List<AgentSession.Info> list(Principal principal) {
        return sessions.list(principal.getName());
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    AgentSession.Info create(@RequestBody CreateRequest request, Principal principal) {
        String title = request.title();
        if (title != null && title.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The title is too long");
        }
        return sessions.create(principal.getName(), request.workspace(), title).info();
    }

    @GetMapping("/sessions/{id}")
    AgentSession.Info get(@PathVariable String id, Principal principal) {
        return sessions.get(principal.getName(), id).info();
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@PathVariable String id, Principal principal) {
        sessions.close(principal.getName(), id);
    }

    @PostMapping("/sessions/{id}/prompt")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void prompt(@PathVariable String id, @RequestBody PromptRequest request, Principal principal) {
        String text = request.text();
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The prompt must not be empty");
        }
        if (text.length() > MAX_PROMPT_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "The prompt is too long");
        }
        sessions.get(principal.getName(), id).prompt(principal.getName(), text);
    }

    @PostMapping("/sessions/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void cancel(@PathVariable String id, Principal principal) {
        sessions.get(principal.getName(), id).cancel();
    }

    /** Starts the conversation over and wipes the session's workspace; the session stays open. */
    @PostMapping("/sessions/{id}/clear")
    AgentSession.Info clear(@PathVariable String id, Principal principal) {
        return sessions.clear(principal.getName(), id);
    }

    @PostMapping("/sessions/{id}/mode")
    AgentSession.Info mode(@PathVariable String id, @RequestBody ModeRequest request, Principal principal) {
        if (request.modeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "modeId is required");
        }
        AgentSession session = sessions.get(principal.getName(), id);
        session.setMode(request.modeId());
        return session.info();
    }

    @PostMapping("/sessions/{id}/permissions/{toolCallId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void answerPermission(@PathVariable String id, @PathVariable String toolCallId,
            @RequestBody PermissionAnswer answer, Principal principal) {
        if (answer.optionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "optionId is required");
        }
        sessions.get(principal.getName(), id).answerPermission(principal.getName(), toolCallId, answer.optionId());
    }

    /**
     * The session's events after {@code after} (or after the {@code Last-Event-ID} header, which
     * a reconnecting browser sends) as Server-Sent Events: the kept ones at once, then new ones as
     * they happen. Each event's id is its sequence number. When the session is closed the stream
     * finishes with an event named {@code end}.
     *
     * <p>The stream is written from this request's own (virtual) thread until the client goes away.
     */
    @GetMapping("/sessions/{id}/events")
    void events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId, Principal principal,
            HttpServletResponse response) throws IOException {
        AgentSession session = sessions.get(principal.getName(), id);
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
