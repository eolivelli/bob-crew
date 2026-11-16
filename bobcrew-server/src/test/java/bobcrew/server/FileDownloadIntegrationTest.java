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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Downloads of workspace files, read out of the session's container. Skipped without Docker. */
@ExtendWith(TestDocker.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileDownloadIntegrationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-download-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path directory;

    private static final TestTls tls;

    static {
        try {
            directory = Files.createTempDirectory("bobcrew-dl-test");
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
        registry.add("bobcrew.file-downloads-enabled", () -> "true");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().sslContext(tls.clientContext).build();

    private final String alice = Tokens.issue(SECRET.getBytes(StandardCharsets.UTF_8), "alice", Duration.ofHours(1),
            Tokens.DEFAULT_ISSUER, Tokens.DEFAULT_AUDIENCE);

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        for (String id : created) {
            send("DELETE", "/sessions/" + id, null, alice);
        }
    }

    @Test
    void downloadsFileFromWorkspaceSuccessfully() throws Exception {
        String id = createSession();
        prompt(id, "writefile");

        for (String path : List.of("out/hello.txt", "file://out/hello.txt", "/opt/workspace/out/hello.txt",
                "file:///opt/workspace/out/hello.txt", "./out/../out/hello.txt")) {
            HttpResponse<String> response = send("GET", "/sessions/" + id + "/file?path=" + path, null, alice);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("hello from the fake agent");
            assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment; filename=\"hello.txt\"");
            assertThat(response.headers().firstValue("Content-Length").orElse("")).isEqualTo("25");
        }
    }

    @Test
    void returnsNotFoundForAMissingFile() throws Exception {
        String id = createSession();
        HttpResponse<String> response = send("GET", "/sessions/" + id + "/file?path=nothing-here.txt", null, alice);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("File not found");
    }

    @Test
    void refusesPathsOutsideTheWorkspaceLinksAndDirectories() throws Exception {
        String id = createSession();
        prompt(id, "writefile makelink");

        for (String path : List.of("../../etc/passwd", "/etc/passwd", "file:///proc/1/environ", "/opt/workspace/../../etc/passwd")) {
            HttpResponse<String> response = send("GET", "/sessions/" + id + "/file?path=" + path, null, alice);
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.body()).contains("must be inside the session workspace");
        }
        // a symbolic link inside the workspace that points out of it
        HttpResponse<String> link = send("GET", "/sessions/" + id + "/file?path=out/link", null, alice);
        assertThat(link.statusCode()).isEqualTo(400);
        assertThat(link.body()).doesNotContain("root:");
        // a directory
        assertThat(send("GET", "/sessions/" + id + "/file?path=out", null, alice).statusCode()).isEqualTo(400);
        // empty path
        assertThat(send("GET", "/sessions/" + id + "/file?path=", null, alice).statusCode()).isEqualTo(400);
    }

    private String createSession() throws Exception {
        HttpResponse<String> response = send("POST", "/sessions", "{}", alice);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String id = JSON.readTree(response.body()).get("id").asString();
        created.add(id);
        return id;
    }

    /** Sends a prompt and waits for the turn to end. */
    private void prompt(String id, String text) throws Exception {
        long before = JSON.readTree(send("GET", "/sessions/" + id, null, alice).body()).get("lastSeq").asLong();
        assertThat(send("POST", "/sessions/" + id + "/prompt", JSON.writeValueAsString(java.util.Map.of("text", text)),
                alice).statusCode()).isEqualTo(202);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode info = JSON.readTree(send("GET", "/sessions/" + id, null, alice).body());
            if (info.get("status").asString().equals("IDLE") && info.get("lastSeq").asLong() >= before + 4) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The turn did not end");
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api" + path))
            .timeout(Duration.ofSeconds(60));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
