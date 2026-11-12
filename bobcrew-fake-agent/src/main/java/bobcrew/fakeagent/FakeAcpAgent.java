package bobcrew.fakeagent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * <li>{@code unknown}: send a session update of a kind no client knows</li>
 * <li>{@code crash}: exit abruptly in the middle of the turn</li>
 * <li>anything else: think, then echo the prompt back word by word</li>
 * </ul>
 */
public final class FakeAcpAgent {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PrintStream out;
    private final AtomicLong nextRequestId = new AtomicLong(1000);
    private final Map<Long, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
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
                result.putArray("authMethods");
                ObjectNode info = result.putObject("agentInfo");
                info.put("name", "fake-agent");
                info.put("title", "Fake agent");
                info.put("version", "1.0");
                respond(id, result);
            }
            case "session/new" -> {
                String sessionId = "fake-" + UUID.randomUUID();
                cancelled.put(sessionId, new AtomicBoolean());
                modes.put(sessionId, "agent");
                ObjectNode result = JSON.createObjectNode();
                result.put("sessionId", sessionId);
                ObjectNode modeState = result.putObject("modes");
                modeState.put("currentModeId", "agent");
                ArrayNode available = modeState.putArray("availableModes");
                for (String mode : new String[] {"agent", "plan", "ask"}) {
                    ObjectNode entry = available.addObject();
                    entry.put("id", mode);
                    entry.put("name", Character.toUpperCase(mode.charAt(0)) + mode.substring(1));
                }
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
            }
            case "session/close" -> respond(id, JSON.createObjectNode());
            default -> {
                if (id != null) {
                    respondError(id, -32601, "Method not found: " + method);
                }
            }
        }
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

        chunk(sessionId, "agent_thought_chunk", "Thinking about: " + prompt);

        if (lower.contains("crash")) {
            chunk(sessionId, "agent_message_chunk", "About to crash");
            out.flush();
            Runtime.getRuntime().halt(3);
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

    /** Returns the text to report, or null if the client cancelled the permission request. */
    private String runTool(String sessionId) throws Exception {
        String toolCallId = "call-" + UUID.randomUUID();
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
