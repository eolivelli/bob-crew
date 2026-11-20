package bobcrew.fakeagent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A scripted Agent Client Protocol agent: newline-delimited JSON-RPC 2.0 on stdin/stdout, like
 * {@code bob acp}. It needs no API key, so tests and demos can run without Bob.
 *
 * <p>The prompt text selects the behaviour (keywords can be combined):
 * <ul>
 * <li>{@code tool}: announce a tool call and ask permission before "running" it</li>
 * <li>{@code plan}: send a plan update</li>
 * <li>{@code slow}: stream chunks for up to 60 seconds, until cancelled</li>
 * <li>{@code background}: end the turn at once, then keep streaming chunks for up to 60 seconds, until a
 * {@code session/cancel} arrives (as Bob does when work it started outlives the turn)</li>
 * <li>{@code unknown}: send a session update of a kind no client knows</li>
 * <li>{@code crash}: exit abruptly in the middle of the turn</li>
 * <li>{@code whoami}: report the user name, uid and working directory the agent runs with</li>
 * <li>{@code showenv NAME}: report the value of environment variable NAME, or that it is unset</li>
 * <li>{@code writefile}: write {@code out/hello.txt} under the working directory</li>
 * <li>{@code makelink}: create {@code out/link}, a symbolic link to {@code /etc/passwd}</li>
 * <li>{@code recall}: list the prompts this session's task had before this one, earlier ones included</li>
 * <li>{@code forget}: delete this session's task, so that it can no longer be resumed</li>
 * <li>anything else: think, then echo the prompt back word by word</li>
 * </ul>
 *
 * <p>A prompt starting with {@value #COMPACTION} (bob-crew's compaction) is not read for keywords and not kept in the
 * task: the answer is a summary, {@code "Summary: "} and the task's prompts, or, if the user's instructions say
 * {@code slow}, ticks until cancelled.
 *
 * <p>Like Bob, it keeps each session's task on disk ({@code $HOME/.fake-agent/tasks/<session id>}: its prompts, one per
 * line), so that a container restarted after a crash can {@code session/resume} it. It also offers Bob's task-transfer
 * extension: it advertises {@code agentCapabilities._meta["bob/extensions"] = {"version": 1}} and answers
 * {@code _bob/task/export} ({@code {"version": 1, "tasks": [{"id", "prompts"}]}}) and {@code _bob/task/import}
 * ({@code {"sessionIds": [a new id]}}). {@code session/resume} of a task it does not have is an error.
 */
public final class FakeAcpAgent {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How bob-crew's compaction prompt starts. */
    static final String COMPACTION = "The conversation is about to be compacted";

    private final PrintStream out;
    private final AtomicLong nextRequestId = new AtomicLong(1000);
    private final Map<Long, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    /** Per session: set by {@code session/cancel} to stop work left running after a turn ended. */
    private final Map<String, AtomicBoolean> backgroundCancelled = new ConcurrentHashMap<>();
    private final Map<String, String> modes = new ConcurrentHashMap<>();

    private FakeAcpAgent(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        // stdout carries protocol messages only; anything else goes to stderr
        PrintStream protocolOut = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        System.setOut(System.err);
        new FakeAcpAgent(protocolOut).run();
    }

    private void run() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = JSON.readTree(line);
            if (message.has("method")) {
                handleIncoming(message);
            } else if (message.has("id")) {
                CompletableFuture<JsonNode> waiting = pendingResponses.remove(message.get("id").asLong());
                if (waiting != null) {
                    waiting.complete(message);
                }
            }
        }
    }

    private void handleIncoming(JsonNode message) {
        String method = message.get("method").asString();
        JsonNode id = message.get("id");
        JsonNode params = message.path("params");
        switch (method) {
            case "initialize" -> {
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", 1);
                ObjectNode capabilities = result.putObject("agentCapabilities");
                capabilities.put("loadSession", false);
                capabilities.putObject("promptCapabilities");
                capabilities.putObject("sessionCapabilities").putObject("resume");
                capabilities.putObject("_meta").putObject("bob/extensions").put("version", 1);
                result.putArray("authMethods");
                ObjectNode info = result.putObject("agentInfo");
                info.put("name", "fake-agent");
                info.put("title", "Fake agent");
                info.put("version", "1.0");
                respond(id, result);
            }
            case "session/new" -> {
                String sessionId = "fake-" + UUID.randomUUID();
                try {
                    saveTask(sessionId, List.of());
                } catch (IOException e) {
                    respondError(id, -32603, "Cannot store the task: " + e);
                    return;
                }
                ObjectNode result = openSession(sessionId);
                result.put("sessionId", sessionId);
                respond(id, result);
            }
            case "session/resume" -> {
                String sessionId = params.path("sessionId").asString("");
                if (!isTaskId(sessionId) || !Files.isRegularFile(taskFile(sessionId))) {
                    respondError(id, -32602, "No such task: " + sessionId);
                    return;
                }
                respond(id, openSession(sessionId));
            }
            case "_bob/task/export" -> {
                String sessionId = params.path("sessionId").asString("");
                try {
                    ObjectNode result = JSON.createObjectNode();
                    result.put("version", 1);
                    ObjectNode task = result.putArray("tasks").addObject();
                    task.put("id", sessionId);
                    ArrayNode prompts = task.putArray("prompts");
                    loadTask(sessionId).forEach(prompts::add);
                    respond(id, result);
                } catch (IOException e) {
                    respondError(id, -32600, "No such task: " + sessionId);
                }
            }
            case "_bob/task/import" -> {
                JsonNode task = params.path("snapshot").path("tasks").path(0);
                if (!task.isObject() || !params.path("cwd").asString("").startsWith("/")) {
                    respondError(id, -32602, "Invalid snapshot or cwd");
                    return;
                }
                // like Bob, a copy with a new id
                String imported = "fake-" + UUID.randomUUID();
                List<String> prompts = new ArrayList<>();
                task.path("prompts").valueStream().forEach(prompt -> prompts.add(prompt.asString("")));
                try {
                    saveTask(imported, prompts);
                } catch (IOException e) {
                    respondError(id, -32603, "Cannot store the task: " + e);
                    return;
                }
                ObjectNode result = JSON.createObjectNode();
                result.putArray("sessionIds").add(imported);
                respond(id, result);
            }
            case "session/set_mode" -> {
                String sessionId = params.path("sessionId").asString();
                String modeId = params.path("modeId").asString();
                modes.put(sessionId, modeId);
                respond(id, JSON.createObjectNode());
                ObjectNode update = JSON.createObjectNode();
                update.put("sessionUpdate", "current_mode_update");
                update.put("currentModeId", modeId);
                sendUpdate(sessionId, update);
            }
            case "session/prompt" -> Thread.ofPlatform().name("fake-prompt").start(() -> {
                try {
                    respond(id, prompt(params));
                } catch (Exception e) {
                    respondError(id, -32603, String.valueOf(e));
                }
            });
            case "session/cancel" -> {
                AtomicBoolean flag = cancelled.get(params.path("sessionId").asString());
                if (flag != null) {
                    flag.set(true);
                }
                AtomicBoolean background = backgroundCancelled.get(params.path("sessionId").asString());
                if (background != null) {
                    background.set(true);
                }
            }
            case "session/close" -> respond(id, JSON.createObjectNode());
            default -> {
                if (id != null) {
                    respondError(id, -32601, "Method not found: " + method);
                }
            }
        }
    }

    /** Opens a session on a task (new or resumed): its modes, as {@code session/new} and {@code session/resume} answer. */
    private ObjectNode openSession(String sessionId) {
        cancelled.put(sessionId, new AtomicBoolean());
        modes.putIfAbsent(sessionId, "agent");
        ObjectNode result = JSON.createObjectNode();
        ObjectNode modeState = result.putObject("modes");
        modeState.put("currentModeId", modes.get(sessionId));
        ArrayNode available = modeState.putArray("availableModes");
        for (String mode : new String[] {"agent", "plan", "ask"}) {
            ObjectNode entry = available.addObject();
            entry.put("id", mode);
            entry.put("name", Character.toUpperCase(mode.charAt(0)) + mode.substring(1));
        }
        return result;
    }

    private static boolean isTaskId(String sessionId) {
        return sessionId.matches("fake-[0-9a-f-]{36}");
    }

    private static Path taskFile(String sessionId) {
        String home = System.getenv("HOME");
        return Path.of(home == null || home.isBlank() ? System.getProperty("user.home") : home, ".fake-agent", "tasks",
                sessionId);
    }

    private static List<String> loadTask(String sessionId) throws IOException {
        if (!isTaskId(sessionId)) {
            throw new IOException("not a task id");
        }
        return Files.readAllLines(taskFile(sessionId), StandardCharsets.UTF_8);
    }

    private static void saveTask(String sessionId, List<String> prompts) throws IOException {
        Path file = taskFile(sessionId);
        Files.createDirectories(file.getParent());
        Files.write(file, prompts, StandardCharsets.UTF_8);
    }

    private ObjectNode prompt(JsonNode params) throws Exception {
        String sessionId = params.path("sessionId").asString();
        AtomicBoolean stop = cancelled.computeIfAbsent(sessionId, k -> new AtomicBoolean());
        stop.set(false);
        StringBuilder text = new StringBuilder();
        for (JsonNode block : params.path("prompt")) {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString(""));
            }
        }
        String prompt = text.toString();
        String lower = prompt.toLowerCase();

        if (prompt.startsWith(COMPACTION)) {
            return summarize(sessionId, prompt, stop);
        }
        chunk(sessionId, "agent_thought_chunk", "Thinking about: " + prompt);

        if (isTaskId(sessionId) && Files.isRegularFile(taskFile(sessionId))) {
            if (lower.contains("recall")) {
                chunk(sessionId, "agent_message_chunk", "remembered: " + String.join(" | ", loadTask(sessionId)) + " ");
            }
            // one line per prompt
            Files.writeString(taskFile(sessionId), prompt.replaceAll("[\\r\\n]+", " ") + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            if (lower.contains("forget")) {
                Files.delete(taskFile(sessionId));
                chunk(sessionId, "agent_message_chunk", "forgot this task ");
            }
        }

        if (lower.contains("crash")) {
            chunk(sessionId, "agent_message_chunk", "About to crash");
            out.flush();
            Runtime.getRuntime().halt(3);
        }
        if (lower.contains("whoami")) {
            chunk(sessionId, "agent_message_chunk", "user=" + System.getProperty("user.name") + " uid=" + uid()
                    + " cwd=" + System.getProperty("user.dir") + " ");
        }
        int showenv = lower.indexOf("showenv ");
        if (showenv >= 0) {
            String name = prompt.substring(showenv + "showenv ".length()).strip().split("\\s+")[0];
            String value = System.getenv(name);
            chunk(sessionId, "agent_message_chunk", value == null ? "env " + name + " is unset " : "env " + name + "=" + value + " ");
        }
        if (lower.contains("writefile")) {
            java.nio.file.Path file = java.nio.file.Path.of("out", "hello.txt");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, "hello from the fake agent");
            chunk(sessionId, "agent_message_chunk", "wrote " + file.toAbsolutePath() + " ");
        }
        if (lower.contains("makelink")) {
            java.nio.file.Path link = java.nio.file.Path.of("out", "link");
            java.nio.file.Files.createDirectories(link.getParent());
            java.nio.file.Files.deleteIfExists(link);
            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Path.of("/etc/passwd"));
            chunk(sessionId, "agent_message_chunk", "linked " + link.toAbsolutePath() + " ");
        }
        if (lower.contains("unknown")) {
            ObjectNode update = JSON.createObjectNode();
            update.put("sessionUpdate", "fake_future_update");
            update.put("detail", "something new");
            sendUpdate(sessionId, update);
        }
        if (lower.contains("plan")) {
            ObjectNode update = JSON.createObjectNode();
            update.put("sessionUpdate", "plan");
            ArrayNode entries = update.putArray("entries");
            String[][] steps = {{"Read the request", "completed"}, {"Do the work", "in_progress"}, {"Report", "pending"}};
            for (String[] step : steps) {
                ObjectNode entry = entries.addObject();
                entry.put("content", step[0]);
                entry.put("priority", "medium");
                entry.put("status", step[1]);
            }
            sendUpdate(sessionId, update);
        }
        if (lower.contains("tool")) {
            String outcome = runTool(sessionId);
            if (outcome == null) {
                return stopReason("cancelled");
            }
            chunk(sessionId, "agent_message_chunk", outcome);
        }
        if (lower.contains("background")) {
            startBackgroundWork(sessionId);
        }
        if (lower.contains("slow")) {
            for (int i = 0; i < 600; i++) {
                if (stop.get()) {
                    return stopReason("cancelled");
                }
                chunk(sessionId, "agent_message_chunk", "tick " + i + " ");
                Thread.sleep(100);
            }
        }
        for (String word : ("You said: " + prompt).split(" ")) {
            if (stop.get()) {
                return stopReason("cancelled");
            }
            chunk(sessionId, "agent_message_chunk", word + " ");
        }
        return stopReason("end_turn");
    }

    /** Answers bob-crew's compaction prompt with a summary of the task: its prompts. */
    private ObjectNode summarize(String sessionId, String prompt, AtomicBoolean stop) throws Exception {
        int instructions = prompt.indexOf("Additional instructions from the user:");
        if (instructions >= 0 && prompt.substring(instructions).toLowerCase().contains("slow")) {
            for (int i = 0; i < 600; i++) {
                if (stop.get()) {
                    return stopReason("cancelled");
                }
                chunk(sessionId, "agent_message_chunk", "tick " + i + " ");
                Thread.sleep(100);
            }
        }
        List<String> prompts = isTaskId(sessionId) && Files.isRegularFile(taskFile(sessionId))
                ? loadTask(sessionId) : List.of();
        chunk(sessionId, "agent_message_chunk", "Summary: ");
        chunk(sessionId, "agent_message_chunk", String.join(" | ", prompts));
        return stopReason("end_turn");
    }

    /** Streams chunks after the turn has ended, until cancelled or for 60 seconds. */
    private void startBackgroundWork(String sessionId) {
        AtomicBoolean stop = new AtomicBoolean();
        backgroundCancelled.put(sessionId, stop);
        Thread.ofPlatform().name("fake-background").start(() -> {
            try {
                Thread.sleep(200); // let the turn's answer go out first
                for (int i = 0; i < 600; i++) {
                    if (stop.get()) {
                        chunk(sessionId, "agent_message_chunk", "background work stopped ");
                        return;
                    }
                    chunk(sessionId, "agent_message_chunk", "background tick " + i + " ");
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Returns the text to report, or null if the client cancelled the permission request. */
    private String runTool(String sessionId) throws Exception {
        // like Bob's ids, it has "/" and "+" in it: clients must not put it in a URL path
        String toolCallId = "call-" + UUID.randomUUID() + "/a+b=";
        ObjectNode toolCall = JSON.createObjectNode();
        toolCall.put("sessionUpdate", "tool_call");
        toolCall.put("toolCallId", toolCallId);
        toolCall.put("title", "Run `echo hello`");
        toolCall.put("kind", "execute");
        toolCall.put("status", "pending");
        toolCall.putObject("rawInput").put("command", "echo hello");
        sendUpdate(sessionId, toolCall);

        ObjectNode request = JSON.createObjectNode();
        request.put("sessionId", sessionId);
        ObjectNode call = request.putObject("toolCall");
        call.put("toolCallId", toolCallId);
        call.put("title", "Run `echo hello`");
        call.put("kind", "execute");
        call.put("status", "pending");
        ArrayNode options = request.putArray("options");
        String[][] choices = {{"allow", "Allow", "allow_once"}, {"allow-always", "Always allow", "allow_always"},
                {"reject", "Reject", "reject_once"}};
        for (String[] choice : choices) {
            ObjectNode option = options.addObject();
            option.put("optionId", choice[0]);
            option.put("name", choice[1]);
            option.put("kind", choice[2]);
        }
        JsonNode response = request("session/request_permission", request);
        JsonNode outcome = response.path("result").path("outcome");
        String kind = outcome.path("outcome").asString("cancelled");
        String optionId = outcome.path("optionId").asString("");

        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", "tool_call_update");
        update.put("toolCallId", toolCallId);
        if ("selected".equals(kind) && optionId.startsWith("allow")) {
            update.put("status", "completed");
            ObjectNode content = update.putArray("content").addObject();
            content.put("type", "content");
            ObjectNode block = content.putObject("content");
            block.put("type", "text");
            block.put("text", "hello");
            sendUpdate(sessionId, update);
            return "Tool ran with option " + optionId + ". ";
        }
        update.put("status", "failed");
        sendUpdate(sessionId, update);
        if (!"selected".equals(kind)) {
            return null;
        }
        return "Tool rejected with option " + optionId + ". ";
    }

    /** The real uid, from /proc on Linux; "?" elsewhere. */
    private static String uid() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
                if (line.startsWith("Uid:")) {
                    return line.substring(4).strip().split("\\s+")[0];
                }
            }
        } catch (Exception e) {
            // not Linux
        }
        return "?";
    }

    private static ObjectNode stopReason(String reason) {
        ObjectNode result = JSON.createObjectNode();
        result.put("stopReason", reason);
        return result;
    }

    private void chunk(String sessionId, String kind, String text) {
        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", kind);
        ObjectNode content = update.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        sendUpdate(sessionId, update);
    }

    private void sendUpdate(String sessionId, ObjectNode update) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", "session/update");
        ObjectNode params = message.putObject("params");
        params.put("sessionId", sessionId);
        params.set("update", update);
        write(message);
    }

    private JsonNode request(String method, ObjectNode params) throws Exception {
        long id = nextRequestId.incrementAndGet();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pendingResponses.put(id, response);
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        write(message);
        return response.get();
    }

    private void respond(JsonNode id, ObjectNode result) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", result);
        write(message);
    }

    private void respondError(JsonNode id, int code, String text) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        ObjectNode error = message.putObject("error");
        error.put("code", code);
        error.put("message", text);
        write(message);
    }

    private void write(ObjectNode message) {
        String line = JSON.writeValueAsString(message);
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }
}
