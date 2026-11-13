package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real server over HTTPS on a random port, with the fake agent as its agent process, and
 * talks to it as a client would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerIntegrationTest {

    private static final String API_KEY = "test-api-key-that-must-never-leak";

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path directory;

    private static final TestTls tls;

    private static final Path revokedFile;

    static {
        try {
            directory = Files.createTempDirectory("bobcrew-test");
            tls = TestTls.generate(directory);
            Files.writeString(directory.resolve("jwt.secret"), SECRET + "\n");
            revokedFile = directory.resolve("revoked.txt");
            Files.createDirectories(directory.resolve("workspaces"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bobcrew.tls.certificate", () -> "file:" + tls.certificatePem);
        registry.add("bobcrew.tls.private-key", () -> "file:" + tls.privateKeyPem);
        registry.add("bobcrew.auth.jwt-secret-file", () -> directory.resolve("jwt.secret").toString());
        registry.add("bobcrew.auth.revoked-jti-file", revokedFile::toString);
        registry.add("bobcrew.workspace-root", () -> directory.resolve("workspaces").toString());
        registry.add("bobcrew.max-sessions", () -> "3");
        registry.add("bobcrew.permission-timeout", () -> "2s");
        registry.add("bobcrew.agent.api-key", () -> API_KEY);
        // the fake agent, started from this test's own classpath
        registry.add("bobcrew.agent.binary", () -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        registry.add("bobcrew.agent.arguments[0]", () -> "-cp");
        registry.add("bobcrew.agent.arguments[1]", () -> System.getProperty("java.class.path"));
        registry.add("bobcrew.agent.arguments[2]", () -> "bobcrew.fakeagent.FakeAcpAgent");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().sslContext(tls.clientContext).build();

    private final String alice = token("alice");

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        for (String id : created) {
            send("DELETE", "/sessions/" + id, null, alice);
        }
    }

    // ---- authentication ----------------------------------------------------

    @Test
    void acceptsAValidToken() throws Exception {
        HttpResponse<String> response = send("GET", "/me", null, alice);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("user").asString()).isEqualTo("alice");
    }

    @Test
    void rejectsMissingAndInvalidTokens() throws Exception {
        assertThat(send("GET", "/sessions", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/sessions", null, "not-a-token").statusCode()).isEqualTo(401);

        String wrongKey = Tokens.issue("another-secret-another-secret-another!!".getBytes(StandardCharsets.UTF_8),
                "mallory", Duration.ofHours(1), Tokens.DEFAULT_ISSUER, Tokens.DEFAULT_AUDIENCE);
        assertThat(send("GET", "/sessions", null, wrongKey).statusCode()).isEqualTo(401);

        Instant now = Instant.now();
        assertThat(send("GET", "/sessions", null,
                sign(claims("alice", now.minusSeconds(3600)).build())).statusCode())
            .as("expired").isEqualTo(401);
        assertThat(send("GET", "/sessions", null,
                sign(claims("alice", now.plusSeconds(3600)).audience("something-else").build())).statusCode())
            .as("wrong audience").isEqualTo(401);
        assertThat(send("GET", "/sessions", null,
                sign(claims("alice", now.plusSeconds(3600)).issuer("someone-else").build())).statusCode())
            .as("wrong issuer").isEqualTo(401);
        assertThat(send("GET", "/sessions", null,
                sign(new JWTClaimsSet.Builder(claims("alice", now.plusSeconds(3600)).build())
                    .expirationTime(null).build())).statusCode())
            .as("no expiry").isEqualTo(401);
    }

    @Test
    void rejectsARevokedToken() throws Exception {
        String bob = token("bob");
        assertThat(send("GET", "/me", null, bob).statusCode()).isEqualTo(200);
        String payload = new String(Base64.getUrlDecoder().decode(bob.split("\\.")[1]), StandardCharsets.UTF_8);
        Files.writeString(revokedFile, "# revoked tokens\n" + JSON.readTree(payload).get("jti").asString() + "\n");
        try {
            assertThat(send("GET", "/me", null, bob).statusCode()).isEqualTo(401);
            assertThat(send("GET", "/me", null, alice).statusCode()).isEqualTo(200);
        } finally {
            Files.delete(revokedFile);
        }
    }

    // ---- sessions ----------------------------------------------------------

    @Test
    void streamsAPromptTurnAndReplaysIt() throws Exception {
        JsonNode session = createSession("project-a", "First");
        String id = session.get("id").asString();
        assertThat(session.get("owner").asString()).isEqualTo("alice");
        assertThat(session.get("status").asString()).isEqualTo("IDLE");
        assertThat(session.get("workspace").asString()).isEqualTo("project-a");
        assertThat(session.get("modes")).hasSize(3);
        assertThat(directory.resolve("workspaces/project-a")).isDirectory();

        assertThat(send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"hello world\"}", alice).statusCode())
            .isEqualTo(202);
        List<JsonNode> events = events(id, 0, event -> isStatus(event, "IDLE") && event.get("seq").asLong() > 1);

        assertThat(types(events)).containsSubsequence("user_prompt", "status", "update", "turn_end", "status");
        JsonNode prompt = first(events, "user_prompt");
        assertThat(prompt.get("user").asString()).isEqualTo("alice");
        assertThat(prompt.get("data").get("text").asString()).isEqualTo("hello world");
        assertThat(agentText(events)).isEqualTo("You said: hello world ");
        assertThat(first(events, "turn_end").get("data").get("stopReason").asString()).isEqualTo("end_turn");
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).get("seq").asLong()).as("sequence numbers are contiguous").isEqualTo(i + 1L);
        }

        // a client that reconnects gets only what it has not seen
        long seen = first(events, "user_prompt").get("seq").asLong();
        List<JsonNode> replay = events(id, seen, event -> isStatus(event, "IDLE"));
        assertThat(replay.get(0).get("seq").asLong()).isEqualTo(seen + 1);
        assertThat(replay).hasSize(events.size() - (int) seen);

        // another user sees and can use the session
        String carol = token("carol");
        assertThat(JSON.readTree(send("GET", "/sessions", null, carol).body()).get(0).get("id").asString())
            .isEqualTo(id);
        assertThat(send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"me too\"}", carol).statusCode())
            .isEqualTo(202);
        List<JsonNode> second = events(id, events.size(), event -> isStatus(event, "IDLE"));
        assertThat(first(second, "user_prompt").get("user").asString()).isEqualTo("carol");

        // nothing a client can read contains the agent's API key
        String everything = events.toString() + second + send("GET", "/sessions", null, alice).body()
                + send("GET", "/sessions/" + id, null, alice).body();
        assertThat(everything).doesNotContain(API_KEY);
    }

    @Test
    void aUserAnswersAPermissionRequest() throws Exception {
        String id = createSession("project-b", null).get("id").asString();
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"use a tool\"}", alice);
        List<JsonNode> asked = events(id, 0, event -> event.get("type").asString().equals("permission_request"));
        JsonNode request = asked.get(asked.size() - 1).get("data");
        String toolCallId = request.get("toolCallId").asString();
        assertThat(request.get("options")).hasSize(3);
        assertThat(request.get("toolCall").get("title").asString()).contains("echo hello");

        String path = "/sessions/" + id + "/permissions/" + toolCallId;
        assertThat(send("POST", path, "{\"optionId\":\"not-offered\"}", alice).statusCode()).isEqualTo(400);
        assertThat(send("POST", path, "{\"optionId\":\"allow\"}", token("dave")).statusCode()).isEqualTo(204);
        assertThat(send("POST", path, "{\"optionId\":\"allow\"}", alice).statusCode()).as("already answered")
            .isIn(404, 409);

        List<JsonNode> rest = events(id, asked.size(), event -> isStatus(event, "IDLE"));
        JsonNode resolved = first(rest, "permission_resolved");
        assertThat(resolved.get("user").asString()).isEqualTo("dave");
        assertThat(resolved.get("data").get("optionId").asString()).isEqualTo("allow");
        assertThat(resolved.get("data").get("timedOut").asBoolean()).isFalse();
        assertThat(rest.stream().anyMatch(event -> "tool_call_update".equals(updateKind(event))
                && "completed".equals(event.get("data").path("status").asString("")))).isTrue();
        assertThat(agentText(rest)).contains("Tool ran with option allow");
    }

    @Test
    void anUnansweredPermissionRequestIsRejected() throws Exception {
        String id = createSession("project-c", null).get("id").asString();
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"use a tool\"}", alice);
        List<JsonNode> events = events(id, 0, event -> isStatus(event, "IDLE") && event.get("seq").asLong() > 1);
        JsonNode resolved = first(events, "permission_resolved");
        assertThat(resolved.get("data").get("timedOut").asBoolean()).isTrue();
        assertThat(resolved.get("data").get("optionId").asString()).isEqualTo("reject");
        assertThat(resolved.has("user")).isFalse();
        assertThat(agentText(events)).contains("Tool rejected");
    }

    @Test
    void aRunningTurnCanBeCancelledAndRefusesASecondPrompt() throws Exception {
        String id = createSession("project-d", null).get("id").asString();
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"be slow\"}", alice);
        List<JsonNode> started = events(id, 0, event -> "agent_message_chunk".equals(updateKind(event)));
        assertThat(send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"again\"}", alice).statusCode())
            .isEqualTo(409);
        assertThat(send("POST", "/sessions/" + id + "/cancel", null, alice).statusCode()).isEqualTo(202);
        List<JsonNode> rest = events(id, started.size(), event -> isStatus(event, "IDLE"));
        assertThat(first(rest, "turn_end").get("data").get("stopReason").asString()).isEqualTo("cancelled");
    }

    @Test
    void passesOnPlansModeChangesAndUnknownUpdates() throws Exception {
        String id = createSession("project-e", null).get("id").asString();
        HttpResponse<String> mode = send("POST", "/sessions/" + id + "/mode", "{\"modeId\":\"plan\"}", alice);
        assertThat(mode.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(mode.body()).get("currentMode").asString()).isEqualTo("plan");
        assertThat(send("POST", "/sessions/" + id + "/mode", "{\"modeId\":\"nope\"}", alice).statusCode())
            .isEqualTo(400);

        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"make a plan with something unknown\"}", alice);
        List<JsonNode> events = events(id, 0, event -> event.get("type").asString().equals("turn_end"));
        List<String> kinds = events.stream().map(ServerIntegrationTest::updateKind).toList();
        assertThat(kinds).contains("current_mode_update", "plan", "fake_future_update", "agent_thought_chunk");
        JsonNode plan = events.stream().filter(event -> "plan".equals(updateKind(event))).findFirst().orElseThrow();
        assertThat(plan.get("data").get("entries")).hasSize(3);
    }

    @Test
    void reportsAnAgentThatDies() throws Exception {
        String id = createSession("project-f", null).get("id").asString();
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"crash now\"}", alice);
        List<JsonNode> events = events(id, 0, event -> isStatus(event, "FAILED"));
        assertThat(types(events)).contains("error");
        assertThat(JSON.readTree(send("GET", "/sessions/" + id, null, alice).body()).get("status").asString())
            .isEqualTo("FAILED");
        assertThat(send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"hello?\"}", alice).statusCode())
            .isEqualTo(409);
    }

    @Test
    void closingASessionEndsItsStream() throws Exception {
        String id = createSession("project-g", null).get("id").asString();
        CompletableFuture<List<String>> stream = CompletableFuture.supplyAsync(() -> rawStream(id));
        Thread.sleep(300);
        assertThat(send("DELETE", "/sessions/" + id, null, alice).statusCode()).isEqualTo(204);
        created.remove(id);
        List<String> lines = stream.get(10, TimeUnit.SECONDS);
        assertThat(lines).contains("event: end");
        assertThat(send("GET", "/sessions/" + id, null, alice).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/sessions/" + id + "/events", null, alice).statusCode()).isEqualTo(404);
    }

    @Test
    void clearingStartsOverAndWipesTheWorkspaceButKeepsTheSession() throws Exception {
        JsonNode session = createSession("clear-a", null);
        String id = session.get("id").asString();
        Path workspace = directory.resolve("workspaces/clear-a");
        Files.writeString(workspace.resolve("notes.txt"), "x");
        Files.createDirectories(workspace.resolve("sub/dir"));
        Files.writeString(workspace.resolve("sub/dir/.hidden"), "y");
        Files.writeString(directory.resolve("workspaces/untouched.txt"), "z");

        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"first\"}", alice);
        List<JsonNode> before = events(id, 0, event -> isStatus(event, "IDLE") && event.get("seq").asLong() > 1);

        HttpResponse<String> cleared = send("POST", "/sessions/" + id + "/clear", null, token("carol"));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        JsonNode info = JSON.readTree(cleared.body());
        assertThat(info.get("id").asString()).isEqualTo(id);
        assertThat(info.get("status").asString()).isEqualTo("IDLE");
        assertThat(info.get("owner").asString()).isEqualTo("alice");

        assertThat(workspace).isDirectory();
        try (var children = Files.list(workspace)) {
            assertThat(children).isEmpty();
        }
        assertThat(directory.resolve("workspaces/untouched.txt")).exists();

        // a client that connects now sees the start of the new conversation, not the old one
        List<JsonNode> after = events(id, 0, event -> isStatus(event, "IDLE"));
        assertThat(types(after)).containsExactly("cleared", "status");
        assertThat(after.get(0).get("user").asString()).isEqualTo("carol");
        assertThat(after.get(0).get("seq").asLong()).isGreaterThan(before.get(before.size() - 1).get("seq").asLong());
        assertThat(JSON.readTree(send("GET", "/sessions", null, alice).body())).hasSize(1);

        // the session works as before, in a fresh agent session
        long seen = after.get(after.size() - 1).get("seq").asLong();
        assertThat(send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"second\"}", alice).statusCode())
            .isEqualTo(202);
        List<JsonNode> next = events(id, seen, event -> isStatus(event, "IDLE"));
        assertThat(agentText(next)).isEqualTo("You said: second ");
    }

    @Test
    void clearingIsRefusedWhileBusyOrWhenTheWorkspaceIsSharedOrIsTheRoot() throws Exception {
        String busy = createSession("clear-busy", null).get("id").asString();
        Files.writeString(directory.resolve("workspaces/clear-busy/keep.txt"), "x");
        send("POST", "/sessions/" + busy + "/prompt", "{\"text\":\"be slow\"}", alice);
        events(busy, 0, event -> "agent_message_chunk".equals(updateKind(event)));
        assertThat(send("POST", "/sessions/" + busy + "/clear", null, alice).statusCode()).isEqualTo(409);
        assertThat(directory.resolve("workspaces/clear-busy/keep.txt")).exists();
        send("POST", "/sessions/" + busy + "/cancel", null, alice);
        events(busy, 0, event -> "turn_end".equals(event.get("type").asString()));

        // a second open session in the same directory, and one nested inside it
        createSession("clear-busy/inner", null);
        assertThat(send("POST", "/sessions/" + busy + "/clear", null, alice).statusCode()).isEqualTo(409);
        assertThat(directory.resolve("workspaces/clear-busy/keep.txt")).exists();

        String root = createSession(".", null).get("id").asString();
        assertThat(send("POST", "/sessions/" + root + "/clear", null, alice).statusCode()).isEqualTo(400);
        assertThat(directory.resolve("workspaces/clear-busy/keep.txt")).exists();

        assertThat(send("POST", "/sessions/no-such-session/clear", null, alice).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/sessions/" + busy + "/clear", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void refusesWorkspacesOutsideTheRoot() throws Exception {
        for (String workspace : List.of("../outside", "/tmp", "a/../../outside", "")) {
            HttpResponse<String> response = send("POST", "/sessions",
                    JSON.writeValueAsString(java.util.Map.of("workspace", workspace)), alice);
            assertThat(response.statusCode()).as(workspace).isEqualTo(400);
        }
        assertThat(directory.resolve("outside")).doesNotExist();
        assertThat(JSON.readTree(send("GET", "/sessions", null, alice).body())).isEmpty();
    }

    @Test
    void limitsTheNumberOfSessions() throws Exception {
        for (int i = 0; i < 3; i++) {
            createSession("limit-" + i, null);
        }
        HttpResponse<String> response = send("POST", "/sessions", "{\"workspace\":\"limit-4\"}", alice);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(send("GET", "/workspaces", null, alice).body()).toString()).contains("limit-0");
    }

    @Test
    void validatesPrompts() throws Exception {
        String id = createSession("project-h", null).get("id").asString();
        assertThat(send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"  \"}", alice).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/sessions/no-such-session/prompt", "{\"text\":\"hi\"}", alice).statusCode())
            .isEqualTo(404);
    }

    // ---- helpers -----------------------------------------------------------

    private static String token(String user) {
        return Tokens.issue(SECRET.getBytes(StandardCharsets.UTF_8), user, Duration.ofHours(1),
                Tokens.DEFAULT_ISSUER, Tokens.DEFAULT_AUDIENCE);
    }

    private static JWTClaimsSet.Builder claims(String user, Instant expiry) {
        return new JWTClaimsSet.Builder().subject(user)
            .issuer(Tokens.DEFAULT_ISSUER)
            .audience(Tokens.DEFAULT_AUDIENCE)
            .jwtID("test-" + System.nanoTime())
            .expirationTime(Date.from(expiry));
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api" + path))
            .timeout(Duration.ofSeconds(60));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = request(path, token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode createSession(String workspace, String title) throws Exception {
        String body = title == null ? "{\"workspace\":\"" + workspace + "\"}"
                : "{\"workspace\":\"" + workspace + "\",\"title\":\"" + title + "\"}";
        HttpResponse<String> response = send("POST", "/sessions", body, alice);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(response.body());
        created.add(session.get("id").asString());
        return session;
    }

    /** Reads the session's events after {@code after} until one matches; fails after 30 seconds. */
    private List<JsonNode> events(String id, long after, Predicate<JsonNode> until) throws Exception {
        HttpResponse<InputStream> response = http.send(request("/sessions/" + id + "/events?after=" + after, alice)
            .build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<JsonNode> events = new ArrayList<>();
        Thread reader = Thread.currentThread();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(30_000);
                reader.interrupt();
                response.body().close();
            } catch (Exception e) {
                // finished in time
            }
        });
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("data: ") && !line.equals("data: {}")) {
                    JsonNode event = JSON.readTree(line.substring(6));
                    events.add(event);
                    if (until.test(event)) {
                        return events;
                    }
                }
            }
        } catch (java.io.IOException e) {
            // closed by the watchdog
        } finally {
            watchdog.interrupt();
            Thread.interrupted();
        }
        return fail("The expected event did not arrive; got " + events);
    }

    private List<String> rawStream(String id) {
        try {
            HttpResponse<InputStream> response = http.send(request("/sessions/" + id + "/events", alice).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                return lines.lines().toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isStatus(JsonNode event, String status) {
        return event.get("type").asString().equals("status")
                && event.get("data").get("status").asString().equals(status);
    }

    private static String updateKind(JsonNode event) {
        return event.get("type").asString().equals("update") ? event.get("data").path("sessionUpdate").asString("")
                : "";
    }

    private static List<String> types(List<JsonNode> events) {
        return events.stream().map(event -> event.get("type").asString()).toList();
    }

    private static JsonNode first(List<JsonNode> events, String type) {
        return events.stream().filter(event -> event.get("type").asString().equals(type)).findFirst().orElseThrow();
    }

    private static String agentText(List<JsonNode> events) {
        StringBuilder text = new StringBuilder();
        for (JsonNode event : events) {
            if ("agent_message_chunk".equals(updateKind(event))) {
                text.append(event.get("data").get("content").get("text").asString());
            }
        }
        return text.toString();
    }
}
