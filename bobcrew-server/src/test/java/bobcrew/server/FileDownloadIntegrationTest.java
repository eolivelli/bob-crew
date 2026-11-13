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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
        registry.add("bobcrew.workspace-root", () -> directory.resolve("workspaces").toString());
        registry.add("bobcrew.file-downloads-enabled", () -> "true");
        registry.add("bobcrew.agent.binary", () -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        registry.add("bobcrew.agent.arguments[0]", () -> "-cp");
        registry.add("bobcrew.agent.arguments[1]", () -> System.getProperty("java.class.path"));
        registry.add("bobcrew.agent.arguments[2]", () -> "bobcrew.fakeagent.FakeAcpAgent");
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
        String id = createSession("dl-project", null).get("id").asString();
        Path ws = directory.resolve("workspaces/dl-project");
        Files.createDirectories(ws.resolve("sub/dir"));
        Files.writeString(ws.resolve("sub/dir/output.txt"), "downloaded content");

        // Relative path
        HttpResponse<String> response = send("GET", "/sessions/" + id + "/file?path=sub/dir/output.txt", null, alice);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("downloaded content");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment; filename=\"output.txt\"");

        // file:// URL format
        HttpResponse<String> fileUrlResp = send("GET", "/sessions/" + id + "/file?path=file://sub/dir/output.txt", null, alice);
        assertThat(fileUrlResp.statusCode()).isEqualTo(200);
        assertThat(fileUrlResp.body()).isEqualTo("downloaded content");

        // Absolute file:/// URL within workspace
        String absoluteUrl = "file://" + ws.resolve("sub/dir/output.txt").toAbsolutePath();
        HttpResponse<String> absResp = send("GET", "/sessions/" + id + "/file?path=" + absoluteUrl, null, alice);
        assertThat(absResp.statusCode()).isEqualTo(200);
        assertThat(absResp.body()).isEqualTo("downloaded content");
    }

    @Test
    void returnsNotFoundWhenFileDoesNotExistOrIsDeleted() throws Exception {
        String id = createSession("dl-missing", null).get("id").asString();
        Path ws = directory.resolve("workspaces/dl-missing");
        Path toDelete = ws.resolve("temporary.txt");
        Files.writeString(toDelete, "temp");

        HttpResponse<String> before = send("GET", "/sessions/" + id + "/file?path=temporary.txt", null, alice);
        assertThat(before.statusCode()).isEqualTo(200);

        Files.delete(toDelete);
        HttpResponse<String> after = send("GET", "/sessions/" + id + "/file?path=temporary.txt", null, alice);
        assertThat(after.statusCode()).isEqualTo(404);
        assertThat(after.body()).contains("File not found");
    }

    @Test
    void refusesPathOutsideWorkspaceOrDirectory() throws Exception {
        String id = createSession("dl-security", null).get("id").asString();
        Path outside = Files.writeString(directory.resolve("outside-secret.txt"), "secret");

        // Attempt traversal with .. resolving to an existing file outside workspace
        HttpResponse<String> traversal = send("GET", "/sessions/" + id + "/file?path=../../outside-secret.txt", null, alice);
        assertThat(traversal.statusCode()).isEqualTo(400);
        assertThat(traversal.body()).contains("must be inside the session workspace");

        // Attempt downloading outside file with absolute path
        HttpResponse<String> outsideAbs = send("GET", "/sessions/" + id + "/file?path=" + outside.toAbsolutePath(), null, alice);
        assertThat(outsideAbs.statusCode()).isEqualTo(400);

        // Attempt downloading a directory
        Path ws = directory.resolve("workspaces/dl-security");
        Files.createDirectories(ws.resolve("my-folder"));
        HttpResponse<String> dirResp = send("GET", "/sessions/" + id + "/file?path=my-folder", null, alice);
        assertThat(dirResp.statusCode()).isEqualTo(400);

        // Empty path
        HttpResponse<String> emptyResp = send("GET", "/sessions/" + id + "/file?path=", null, alice);
        assertThat(emptyResp.statusCode()).isEqualTo(400);
    }

    private JsonNode createSession(String workspace, String title) throws Exception {
        String body = title == null ? "{\"workspace\":\"" + workspace + "\"}"
                : "{\"workspace\":\"" + workspace + "\",\"title\":\"" + title + "\"}";
        HttpResponse<String> response = send("POST", "/sessions", body, alice);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode session = JSON.readTree(response.body());
        created.add(session.get("id").asString());
        return session;
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
