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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real server over HTTPS on a random port, with the fake agent in real Docker containers, and
 * talks to it as a client would. Skipped when no Docker daemon is reachable.
 */
@ExtendWith(TestDocker.class)
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
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String instanceId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        instanceId = TestDocker.register(registry);
        registry.add("bobcrew.tls.certificate", () -> "file:" + tls.certificatePem);
        registry.add("bobcrew.tls.private-key", () -> "file:" + tls.privateKeyPem);
        registry.add("bobcrew.auth.jwt-secret-file", () -> directory.resolve("jwt.secret").toString());
        registry.add("bobcrew.auth.revoked-jti-file", revokedFile::toString);
        registry.add("bobcrew.max-sessions", () -> "3");
        registry.add("bobcrew.permission-timeout", () -> "2s");
        registry.add("bobcrew.agent.api-key", () -> API_KEY);
        registry.add("bobcrew.max-upload-size", () -> "1MB");
        registry.add("bobcrew.docker.max-memory", () -> "1GB");
        registry.add("bobcrew.docker.max-cpus", () -> "2");
    }

    @LocalServerPort
    int port;

    @Autowired
    DockerRuntime docker;

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
        JsonNode session = createSession("First");
        String id = session.get("id").asString();
        assertThat(session.get("owner").asString()).isEqualTo("alice");
        assertThat(session.get("title").asString()).isEqualTo("First");
        assertThat(session.get("status").asString()).isEqualTo("IDLE");
        assertThat(session.get("image").asString()).isEqualTo(TestDocker.IMAGE);
        assertThat(session.get("containerId").asString()).hasSize(12);
        assertThat(session.get("modes")).hasSize(3);

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
        String id = createSession(null).get("id").asString();
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
        String id = createSession(null).get("id").asString();
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
        String id = createSession(null).get("id").asString();
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
        String id = createSession(null).get("id").asString();
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
        String id = createSession(null).get("id").asString();
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
        String id = createSession(null).get("id").asString();
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
    void closingASessionRemovesItsContainer() throws Exception {
        String container = createSession(null).get("containerId").asString();
        assertThat(TestDocker.client().inspectContainerCmd(container).exec().getState().getRunning()).isTrue();
        send("DELETE", "/sessions/" + created.remove(0), null, alice);
        try {
            TestDocker.client().inspectContainerCmd(container).exec();
            fail("the container is still there");
        } catch (NotFoundException e) {
            // removed
        }
    }

    // ---- containers --------------------------------------------------------

    @Test
    void runsTheAgentInAnIsolatedContainer() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("env", Map.of("TEST_MARKER", "marker-value", "GH_TOKEN", "a-token"));
        JsonNode session = create(body);
        String id = session.get("id").asString();
        assertThat(session.toString()).as("env values are not echoed").doesNotContain("marker-value");

        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"whoami showenv TEST_MARKER\"}", alice);
        String text = agentText(events(id, 0, event -> isStatus(event, "IDLE") && event.get("seq").asLong() > 1));
        assertThat(text).contains("uid=1000", "cwd=/opt/workspace", "env TEST_MARKER=marker-value");

        InspectContainerResponse container = TestDocker.client()
            .inspectContainerCmd(session.get("containerId").asString()).exec();
        assertThat(container.getConfig().getUser()).isEqualTo("1000:1000");
        assertThat(container.getConfig().getLabels()).containsEntry(DockerRuntime.LABEL_MANAGED, "true")
            .containsEntry(DockerRuntime.LABEL_INSTANCE, instanceId)
            .containsEntry(DockerRuntime.LABEL_SESSION, id)
            .containsEntry(DockerRuntime.LABEL_OWNER, "alice");
        assertThat(container.getMounts()).as("no host directories").isEmpty();
        assertThat(container.getHostConfig().getBinds()).isNullOrEmpty();
        assertThat(container.getHostConfig().getCapDrop()).contains(Capability.ALL);
        assertThat(container.getHostConfig().getSecurityOpts()).contains("no-new-privileges");
        assertThat(container.getHostConfig().getInit()).isTrue();
        assertThat(container.getHostConfig().getMemory()).isEqualTo(512L * 1024 * 1024);

        // the environment is the image's plus what the server and the client put there, nothing from this host
        List<String> imageEnv = List.of(TestDocker.client().inspectImageCmd(TestDocker.IMAGE).exec()
            .getConfig().getEnv());
        List<String> extra = new ArrayList<>(List.of(container.getConfig().getEnv()));
        extra.removeAll(imageEnv);
        assertThat(extra.stream().map(entry -> entry.substring(0, entry.indexOf('='))))
            .containsExactlyInAnyOrder("TEST_MARKER", "GH_TOKEN", "BOB_API_KEY", "BOBSHELL_API_KEY", "BOBCREW_SESSION_ID");
    }

    @Test
    void refusesCapabilitiesUnlessTheServerAllowsThem() throws Exception {
        JsonNode me = JSON.readTree(send("GET", "/me", null, alice).body());
        assertThat(me.get("capabilities")).isEmpty();
        assertThat(me.get("maxMemoryBytes").asLong()).isEqualTo(1024L * 1024 * 1024);
        assertThat(me.get("maxCpus").asDouble()).isEqualTo(2.0);
        HttpResponse<String> response = send("POST", "/sessions", "{\"capabilities\":[\"docker\"]}", alice);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("does not allow the docker capability");
        assertThat(createStatus(Map.of("capabilities", List.of("root")))).isEqualTo(400);
        assertThat(TestDocker.client().listContainersCmd().withShowAll(true)
            .withLabelFilter(Map.of(DockerRuntime.LABEL_INSTANCE, instanceId)).exec()).isEmpty();
    }

    @Test
    void appliesTheResourcesASessionAsksForWithinTheServersCaps() throws Exception {
        JsonNode session = create(Map.of("resources", Map.of("memory", "256MB", "cpus", 0.5)));
        assertThat(session.get("resources").get("memoryBytes").asLong()).isEqualTo(256L * 1024 * 1024);
        assertThat(session.get("resources").get("cpus").asDouble()).isEqualTo(0.5);
        InspectContainerResponse container = TestDocker.client()
            .inspectContainerCmd(session.get("containerId").asString()).exec();
        assertThat(container.getHostConfig().getMemory()).isEqualTo(256L * 1024 * 1024);
        assertThat(container.getHostConfig().getNanoCPUs()).isEqualTo(500_000_000L);

        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("memory", "2GB"), Map.of("memory", "lots"),
                Map.of("memory", "1MB"), Map.of("cpus", 3), Map.of("cpus", 0), Map.of("cpus", -1))) {
            assertThat(createStatus(Map.of("resources", bad))).as(bad.toString()).isEqualTo(400);
        }
    }

    @Test
    void validatesImagesAndEnvironment() throws Exception {
        assertThat(createStatus(Map.of("image", "bobcrew-no-such-image:never"))).isEqualTo(400);
        assertThat(createStatus(Map.of("image", "bad image name"))).isEqualTo(400);
        for (String name : List.of("BOB_API_KEY", "BOBSHELL_API_KEY", "PATH", "HOME", "BOBCREW_X", "LD_PRELOAD",
                "1BAD", "BAD-NAME", "")) {
            assertThat(createStatus(Map.of("env", Map.of(name, "x")))).as(name).isEqualTo(400);
        }
        assertThat(createStatus(Map.of("env", Map.of("BIG", "x".repeat(40_000))))).isEqualTo(400);
        assertThat(JSON.readTree(send("GET", "/sessions", null, alice).body())).isEmpty();
        assertThat(TestDocker.client().listContainersCmd().withShowAll(true)
            .withLabelFilter(Map.of(DockerRuntime.LABEL_INSTANCE, instanceId)).exec())
            .as("refused sessions leave no container").isEmpty();
    }

    @Test
    void reportsContainerMetrics() throws Exception {
        String id = createSession(null).get("id").asString();
        HttpResponse<String> response = send("GET", "/sessions/" + id + "/metrics", null, alice);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode metrics = JSON.readTree(response.body());
        assertThat(metrics.get("state").asString()).isEqualTo("running");
        assertThat(metrics.get("image").asString()).isEqualTo(TestDocker.IMAGE);
        assertThat(metrics.get("memoryUsageBytes").asLong()).isPositive();
        assertThat(metrics.get("memoryLimitBytes").asLong()).isEqualTo(512L * 1024 * 1024);
        assertThat(metrics.get("pids").asLong()).isPositive();
        assertThat(metrics.get("cpuPercent").isNumber()).isTrue();
        assertThat(metrics.get("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(send("GET", "/sessions/no-such-session/metrics", null, alice).statusCode()).isEqualTo(404);
    }

    @Test
    void removesLeftoverContainersOfThisInstanceOnly() throws Exception {
        String mine = TestDocker.client().createContainerCmd(TestDocker.IMAGE)
            .withLabels(Map.of(DockerRuntime.LABEL_MANAGED, "true", DockerRuntime.LABEL_INSTANCE, instanceId))
            .withCmd("true")
            .exec().getId();
        String other = TestDocker.client().createContainerCmd(TestDocker.IMAGE)
            .withLabels(Map.of(DockerRuntime.LABEL_MANAGED, "true", DockerRuntime.LABEL_INSTANCE, instanceId + "-other"))
            .withCmd("true")
            .exec().getId();
        try {
            docker.removeOrphans();
            try {
                TestDocker.client().inspectContainerCmd(mine).exec();
                fail("the leftover container is still there");
            } catch (NotFoundException e) {
                // removed
            }
            assertThat(TestDocker.client().inspectContainerCmd(other).exec().getId()).isEqualTo(other);
        } finally {
            TestDocker.client().removeContainerCmd(other).withForce(true).exec();
        }
    }

    @Test
    void clearingStartsOverAndWipesTheWorkspaceButKeepsTheSession() throws Exception {
        JsonNode session = createSession(null);
        String id = session.get("id").asString();
        String container = session.get("containerId").asString();

        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"writefile\"}", alice);
        List<JsonNode> before = events(id, 0, event -> isStatus(event, "IDLE") && event.get("seq").asLong() > 1);
        assertThat(exists(container, "/opt/workspace/out/hello.txt")).isTrue();

        HttpResponse<String> cleared = send("POST", "/sessions/" + id + "/clear", null, token("carol"));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        JsonNode info = JSON.readTree(cleared.body());
        assertThat(info.get("id").asString()).isEqualTo(id);
        assertThat(info.get("status").asString()).isEqualTo("IDLE");
        assertThat(info.get("owner").asString()).isEqualTo("alice");
        assertThat(exists(container, "/opt/workspace/out")).isFalse();
        assertThat(exists(container, "/opt/workspace")).isTrue();

        // a client that connects now sees the start of the new conversation, not the old one
        List<JsonNode> after = events(id, 0, event -> isStatus(event, "IDLE"));
        assertThat(types(after)).containsExactly("cleared", "status");
        assertThat(after.get(0).get("user").asString()).isEqualTo("carol");
        assertThat(after.get(0).get("data").get("workspaceWiped").asBoolean()).isTrue();
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
    void clearingCanKeepTheWorkspace() throws Exception {
        JsonNode session = createSession(null);
        String id = session.get("id").asString();
        String container = session.get("containerId").asString();
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"writefile\"}", alice);
        events(id, 0, event -> isStatus(event, "IDLE") && event.get("seq").asLong() > 1);

        HttpResponse<String> cleared = send("POST", "/sessions/" + id + "/clear", "{\"wipeWorkspace\":false}", alice);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(exists(container, "/opt/workspace/out/hello.txt")).isTrue();
        List<JsonNode> after = events(id, 0, event -> isStatus(event, "IDLE"));
        assertThat(types(after)).containsExactly("cleared", "status");
        assertThat(after.get(0).get("data").get("workspaceWiped").asBoolean()).isFalse();
    }

    @Test
    void clearingIsRefusedWhileBusy() throws Exception {
        JsonNode session = createSession(null);
        String busy = session.get("id").asString();
        send("POST", "/sessions/" + busy + "/prompt", "{\"text\":\"writefile and be slow\"}", alice);
        events(busy, 0, event -> "agent_message_chunk".equals(updateKind(event)));
        assertThat(send("POST", "/sessions/" + busy + "/clear", null, alice).statusCode()).isEqualTo(409);
        assertThat(exists(session.get("containerId").asString(), "/opt/workspace/out/hello.txt")).isTrue();
        send("POST", "/sessions/" + busy + "/cancel", null, alice);
        events(busy, 0, event -> "turn_end".equals(event.get("type").asString()));

        assertThat(send("POST", "/sessions/no-such-session/clear", null, alice).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/sessions/" + busy + "/clear", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void limitsTheNumberOfSessions() throws Exception {
        for (int i = 0; i < 3; i++) {
            createSession("limit-" + i);
        }
        HttpResponse<String> response = send("POST", "/sessions", "{\"title\":\"limit-4\"}", alice);
        assertThat(response.statusCode()).isEqualTo(429);
    }

    @Test
    void validatesPrompts() throws Exception {
        String id = createSession(null).get("id").asString();
        assertThat(send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"  \"}", alice).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/sessions/no-such-session/prompt", "{\"text\":\"hi\"}", alice).statusCode())
            .isEqualTo(404);
    }

    // ---- repositories ------------------------------------------------------

    @Test
    void clonesTheRepositoryAndTellsTheAgentOnTheFirstPrompt() throws Exception {
        JsonNode session = create(Map.of("repository", Map.of("url", TestDocker.SAMPLE_REPOSITORY, "branch", "feature")));
        String id = session.get("id").asString();
        String container = session.get("containerId").asString();
        assertThat(session.get("repository").get("url").asString()).isEqualTo(TestDocker.SAMPLE_REPOSITORY);
        assertThat(session.get("repository").get("branch").asString()).isEqualTo("feature");
        assertThat(session.get("repository").get("directory").asString()).isEqualTo("sample");

        assertThat(exists(container, "/opt/workspace/sample/FEATURE.md")).as("the branch is checked out").isTrue();
        assertThat(exists(container, "/opt/workspace/sample/.git/shallow")).as("a shallow clone").isTrue();
        assertThat(execOutput(container, "stat", "-c", "%u", "/opt/workspace/sample/README.md").strip())
            .as("owned by the session's user").isEqualTo("1000");

        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"first\"}", alice);
        List<JsonNode> first = events(id, 0, event -> "turn_end".equals(event.get("type").asString()));
        JsonNode cloned = first(first, "repository_cloned");
        assertThat(cloned.get("data").get("path").asString()).isEqualTo("/opt/workspace/sample");
        assertThat(agentText(first)).contains("has been cloned into /opt/workspace/sample", "(branch feature)",
                "shallow clone");
        assertThat(first(first, "user_prompt").get("data").get("text").asString())
            .as("the note is not shown as the user's text").isEqualTo("first");

        long seen = first.get(first.size() - 1).get("seq").asLong();
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"second\"}", alice);
        assertThat(agentText(events(id, seen, event -> "turn_end".equals(event.get("type").asString()))))
            .isEqualTo("You said: second ");
    }

    @Test
    void clonesTheDefaultBranchWhenNoneIsGiven() throws Exception {
        JsonNode session = create(Map.of("repository", Map.of("url", TestDocker.SAMPLE_REPOSITORY)));
        String container = session.get("containerId").asString();
        assertThat(exists(container, "/opt/workspace/sample/README.md")).isTrue();
        assertThat(exists(container, "/opt/workspace/sample/FEATURE.md")).isFalse();
    }

    @Test
    void refusesBadRepositoriesAndLeavesNoContainer() throws Exception {
        for (Map<String, String> repository : List.of(
                Map.of("url", "http://example.com/repo.git"),
                Map.of("url", "ssh://git@example.com/repo.git"),
                Map.of("url", "https://user:secret@example.com/repo.git"),
                Map.of("url", "https://example.com/repo.git?x=1"),
                Map.of("url", "not a url"),
                Map.of("url", TestDocker.SAMPLE_REPOSITORY, "branch", "--upload-pack=touch /tmp/x"),
                Map.of("url", TestDocker.SAMPLE_REPOSITORY, "branch", "a..b"),
                Map.of("url", "", "branch", "main"))) {
            assertThat(createStatus(Map.of("repository", repository))).as(repository.toString()).isEqualTo(400);
        }
        HttpResponse<String> missing = send("POST", "/sessions", JSON.writeValueAsString(Map.of("repository",
                Map.of("url", TestDocker.SAMPLE_REPOSITORY, "branch", "no-such-branch"))), alice);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(missing.body()).get("error").asString()).contains("could not be cloned", "no-such-branch");
        assertThat(JSON.readTree(send("GET", "/sessions", null, alice).body())).isEmpty();
        assertThat(TestDocker.client().listContainersCmd().withShowAll(true)
            .withLabelFilter(Map.of(DockerRuntime.LABEL_INSTANCE, instanceId)).exec())
            .as("refused sessions leave no container").isEmpty();
    }

    @Test
    void wipingTheWorkspaceDropsTheRepositoryNote() throws Exception {
        JsonNode session = create(Map.of("repository", Map.of("url", TestDocker.SAMPLE_REPOSITORY)));
        String id = session.get("id").asString();
        assertThat(send("POST", "/sessions/" + id + "/clear", "{\"wipeWorkspace\":false}", alice).statusCode())
            .isEqualTo(200);
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"kept\"}", alice);
        assertThat(agentText(events(id, 0, event -> "turn_end".equals(event.get("type").asString()))))
            .as("the repository is still there after a clear that keeps files").contains("has been cloned");

        assertThat(send("POST", "/sessions/" + id + "/clear", null, alice).statusCode()).isEqualTo(200);
        assertThat(exists(session.get("containerId").asString(), "/opt/workspace/sample")).isFalse();
        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"wiped\"}", alice);
        assertThat(agentText(events(id, 0, event -> "turn_end".equals(event.get("type").asString()))))
            .isEqualTo("You said: wiped ");
    }

    // ---- uploads -----------------------------------------------------------

    @Test
    void uploadsAFileIntoTheWorkspace() throws Exception {
        JsonNode session = createSession(null);
        String id = session.get("id").asString();
        String container = session.get("containerId").asString();

        HttpResponse<String> response = upload(id, "docs/notes%20one.txt", "first line\nsecond line\n".getBytes(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode uploaded = JSON.readTree(response.body());
        assertThat(uploaded.get("path").asString()).isEqualTo("/opt/workspace/docs/notes one.txt");
        assertThat(uploaded.get("size").asLong()).isEqualTo(23);
        assertThat(execOutput(container, "cat", "/opt/workspace/docs/notes one.txt")).isEqualTo("first line\nsecond line\n");
        assertThat(execOutput(container, "stat", "-c", "%u", "/opt/workspace/docs/notes one.txt").strip())
            .as("written by the session's user").isEqualTo("1000");

        // replacing, an empty file, and an absolute path inside the workspace
        assertThat(upload(id, "/opt/workspace/docs/notes%20one.txt", "new".getBytes(StandardCharsets.UTF_8)).statusCode())
            .isEqualTo(201);
        assertThat(execOutput(container, "cat", "/opt/workspace/docs/notes one.txt")).isEqualTo("new");
        assertThat(upload(id, "empty", new byte[0]).statusCode()).isEqualTo(201);
        assertThat(exists(container, "/opt/workspace/empty")).isTrue();

        List<JsonNode> events = events(id, 0, event -> "file_uploaded".equals(event.get("type").asString())
                && event.get("data").get("path").asString().equals("/opt/workspace/empty"));
        JsonNode first = first(events, "file_uploaded");
        assertThat(first.get("user").asString()).isEqualTo("alice");
        assertThat(first.get("data").get("size").asLong()).isEqualTo(23);
    }

    @Test
    void refusesUploadsOutsideTheWorkspaceOntoLinksAndTooLarge() throws Exception {
        JsonNode session = createSession(null);
        String id = session.get("id").asString();
        byte[] small = "x".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(id, "../../etc/evil", small).statusCode()).isEqualTo(400);
        assertThat(upload(id, "/etc/evil", small).statusCode()).isEqualTo(400);
        assertThat(upload(id, "", small).statusCode()).isEqualTo(400);

        send("POST", "/sessions/" + id + "/prompt", "{\"text\":\"makelink\"}", alice);
        events(id, 0, event -> "turn_end".equals(event.get("type").asString()));
        assertThat(upload(id, "out/link", small).statusCode()).as("a symbolic link").isEqualTo(400);
        assertThat(upload(id, "out", small).statusCode()).as("a directory").isEqualTo(400);
        assertThat(execOutput(session.get("containerId").asString(), "cat", "/etc/passwd")).doesNotStartWith("x");

        HttpResponse<String> large = upload(id, "large.bin", new byte[1024 * 1024 + 1]);
        assertThat(large.statusCode()).isEqualTo(413);
        assertThat(exists(session.get("containerId").asString(), "/opt/workspace/large.bin")).isFalse();
        assertThat(upload("no-such-session", "a.txt", small).statusCode()).isEqualTo(404);
        assertThat(http.send(request("/sessions/" + id + "/files?path=a.txt", null)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(small)).build(), HttpResponse.BodyHandlers.ofString())
            .statusCode()).isEqualTo(401);
    }

    @Test
    void downloadingFilesWhenDisabledReturnsForbidden() throws Exception {
        String id = createSession(null).get("id").asString();

        HttpResponse<String> response = send("GET", "/sessions/" + id + "/file?path=hello.txt", null, alice);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("File downloads are disabled");
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

    private JsonNode createSession(String title) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (title != null) {
            body.put("title", title);
        }
        return create(body);
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = send("POST", "/sessions", JSON.writeValueAsString(body), alice);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(response.body());
        created.add(session.get("id").asString());
        return session;
    }

    private int createStatus(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = send("POST", "/sessions", JSON.writeValueAsString(body), alice);
        if (response.statusCode() == 201) {
            created.add(JSON.readTree(response.body()).get("id").asString());
        }
        return response.statusCode();
    }

    private HttpResponse<String> upload(String id, String encodedPath, byte[] content) throws Exception {
        return http.send(request("/sessions/" + id + "/files?path=" + encodedPath, alice)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .header("Content-Type", "application/octet-stream")
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** What a command run in a container prints on its standard output. */
    private static String execOutput(String container, String... command) throws Exception {
        String exec = TestDocker.client().execCreateCmd(container).withCmd(command)
            .withAttachStdout(true).withAttachStderr(true).exec().getId();
        StringBuilder output = new StringBuilder();
        TestDocker.client().execStartCmd(exec).exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<
                com.github.dockerjava.api.model.Frame>() {
            @Override
            public void onNext(com.github.dockerjava.api.model.Frame frame) {
                if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                    synchronized (output) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }
            }
        }).awaitCompletion(30, TimeUnit.SECONDS);
        synchronized (output) {
            return output.toString();
        }
    }

    /** Whether a path exists in a container, checked with {@code test -e} run in it. */
    private static boolean exists(String container, String path) throws Exception {
        String exec = TestDocker.client().execCreateCmd(container).withCmd("test", "-e", path)
            .withAttachStdout(true).withAttachStderr(true).exec().getId();
        TestDocker.client().execStartCmd(exec).start().awaitCompletion(30, TimeUnit.SECONDS);
        for (int i = 0; i < 100; i++) {
            var inspected = TestDocker.client().inspectExecCmd(exec).exec();
            if (!Boolean.TRUE.equals(inspected.isRunning()) && inspected.getExitCodeLong() != null) {
                return inspected.getExitCodeLong() == 0;
            }
            Thread.sleep(50);
        }
        return fail("the exec did not finish");
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
