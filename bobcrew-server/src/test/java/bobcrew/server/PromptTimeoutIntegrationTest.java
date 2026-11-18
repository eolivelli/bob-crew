package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A server with {@code bobcrew.prompt-timeout} set: a turn that outlasts it must really end, not just be given up
 * on while the agent goes on working. Skipped without Docker.
 */
@ExtendWith(TestDocker.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromptTimeoutIntegrationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-timeout-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path directory;

    private static final TestTls tls;

    static {
        try {
            directory = Files.createTempDirectory("bobcrew-timeout-test");
            tls = TestTls.generate(directory);
            Files.writeString(directory.resolve("jwt.secret"), SECRET + "\n");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDocker.register(registry);
        registry.add("bobcrew.tls.certificate", () -> "file:" + tls.certificatePem);
        registry.add("bobcrew.tls.private-key", () -> "file:" + tls.privateKeyPem);
        registry.add("bobcrew.auth.jwt-secret-file", () -> directory.resolve("jwt.secret").toString());
        registry.add("bobcrew.prompt-timeout", () -> "2s");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().sslContext(tls.clientContext).build();

    private final String alice = Tokens.issue(SECRET.getBytes(StandardCharsets.UTF_8), "alice", Duration.ofHours(1),
            Tokens.DEFAULT_ISSUER, Tokens.DEFAULT_AUDIENCE);

    @Test
    void aTurnThatTakesTooLongIsStoppedInTheAgentToo() throws Exception {
        HttpResponse<String> created = send("POST", "/sessions", "{}");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = JSON.readTree(created.body()).get("id").asString();
        try {
            // the fake agent streams for a minute, and like Bob stops for session/cancel only
            assertThat(send("POST", "/sessions/" + id + "/prompt", JSON.writeValueAsString(Map.of("text", "slow")))
                .statusCode()).isEqualTo(202);
            JsonNode idle = await(id, info -> info.get("status").asString().equals("IDLE"));

            // what it was doing has stopped: nothing more arrives
            Thread.sleep(500);
            long settled = session(id).get("lastSeq").asLong();
            Thread.sleep(1500);
            assertThat(session(id).get("lastSeq").asLong()).as("the agent stopped sending").isEqualTo(settled);
            assertThat(settled).isGreaterThanOrEqualTo(idle.get("lastSeq").asLong());

            assertThat(errorEvent(id)).contains("The turn took longer than this server allows (2s")
                .doesNotContain("MonoDeferContextual");

            // and the session takes the next prompt
            assertThat(send("POST", "/sessions/" + id + "/prompt", JSON.writeValueAsString(Map.of("text", "hello")))
                .statusCode()).isEqualTo(202);
            await(id, info -> info.get("status").asString().equals("IDLE")
                    && info.get("lastSeq").asLong() > settled + 2);
        } finally {
            send("DELETE", "/sessions/" + id, null);
        }
    }

    /** The session's first error event, read from its event stream (which never ends by itself). */
    private String errorEvent(String id) throws Exception {
        HttpResponse<java.io.InputStream> response = http.send(HttpRequest
            .newBuilder(URI.create("https://localhost:" + port + "/api/sessions/" + id + "/events?after=0"))
            .header("Authorization", "Bearer " + alice)
            .build(), HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.BufferedReader lines = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("data: ") && line.contains("\"type\":\"error\"")) {
                    return line;
                }
                if (line.contains("\"status\":\"IDLE\"") && line.contains("\"seq\":") && !line.contains("\"seq\":1,")
                        && !line.contains("\"seq\":2,")) {
                    break;
                }
            }
        }
        throw new AssertionError("The session has no error event");
    }

    private JsonNode session(String id) throws Exception {
        return JSON.readTree(send("GET", "/sessions/" + id, null).body());
    }

    private JsonNode await(String id, java.util.function.Predicate<JsonNode> until) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode info = session(id);
            if (until.test(info)) {
                return info;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for the session: " + session(id));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api" + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + alice);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
