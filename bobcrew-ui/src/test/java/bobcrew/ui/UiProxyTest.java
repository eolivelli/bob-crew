package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the UI app against a stand-in HTTPS server and checks what the proxy forwards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UiProxyTest {

    private static final String TOKEN = "test.token.value";

    private static final String PASSWORD = "changeit";

    private static final HttpsServer upstream;

    private static final Path caFile;

    static {
        try {
            Path directory = Files.createTempDirectory("bobcrew-ui-test");
            Path keyStoreFile = directory.resolve("upstream.p12");
            Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", keyStoreFile.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true)
                .start();
            String output = new String(keytool.getInputStream().readAllBytes());
            if (keytool.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keyStoreFile)) {
                keyStore.load(in, PASSWORD.toCharArray());
            }
            caFile = directory.resolve("ca.crt");
            Files.writeString(caFile, "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(keyStore.getCertificate("server").getEncoded())
                    + "\n-----END CERTIFICATE-----\n");
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, PASSWORD.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);

            upstream = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.setHttpsConfigurator(new HttpsConfigurator(context));
            upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            upstream.createContext("/api/", UiProxyTest::handle);
            upstream.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** The stand-in server: refuses a wrong token, echoes requests, and streams two events slowly. */
    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "text/plain", "no");
        } else if (path.equals("/api/unauthorized")) {
            respond(exchange, 401, "text/plain", "expired");
        } else if (path.endsWith("/events")) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("id: 1\ndata: first after " + exchange.getRequestHeaders().getFirst("Last-Event-ID") + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(1500);
                out.write("id: 2\ndata: second\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (path.equals("/api/missing")) {
            respond(exchange, 404, "application/json", "{\"error\":\"No such session\"}");
        } else {
            respond(exchange, 200, "application/json", "{\"method\":\"" + exchange.getRequestMethod() + "\",\"uri\":\""
                    + exchange.getRequestURI() + "\",\"contentType\":\""
                    + exchange.getRequestHeaders().getFirst("Content-Type") + "\",\"body\":"
                    + (body.length == 0 ? "null" : new String(body, StandardCharsets.UTF_8)) + "}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bobcrew.ui.server-url", () -> "https://localhost:" + upstream.getAddress().getPort() + "/");
        registry.add("bobcrew.ui.token", () -> TOKEN);
        registry.add("bobcrew.ui.ca-file", caFile::toString);
    }

    @LocalServerPort
    int port;

    private final HttpClient browser = HttpClient.newHttpClient();

    @Test
    void servesThePage() throws Exception {
        HttpResponse<String> page = get("/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<title>bob-crew</title>");
        assertThat(page.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(get("/app.js").body()).doesNotContain(TOKEN);
    }

    @Test
    void forwardsRequestsWithTheToken() throws Exception {
        HttpResponse<String> listed = get("/api/sessions?x=1&y=a%20b");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("\"method\":\"GET\"").contains("\"uri\":\"/api/sessions?x=1&y=a%20b\"");
        assertThat(listed.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        HttpResponse<String> posted = browser.send(HttpRequest.newBuilder(uri("/api/sessions"))
            .header("X-BobCrew", "1")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"workspace\":\"demo\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(posted.body()).contains("\"method\":\"POST\"")
            .contains("\"contentType\":\"application/json\"")
            .contains("\"body\":{\"workspace\":\"demo\"}");
    }

    @Test
    void passesOnErrorsAndExplainsARejectedToken() throws Exception {
        HttpResponse<String> missing = get("/api/missing");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("No such session");

        HttpResponse<String> rejected = get("/api/unauthorized");
        assertThat(rejected.statusCode()).isEqualTo(502);
        assertThat(rejected.body()).contains("rejected the configured token");
    }

    @Test
    void streamsEventsAsTheyArrive() throws Exception {
        HttpResponse<InputStream> response = browser.send(HttpRequest.newBuilder(uri("/api/sessions/abc/events"))
            .header("Last-Event-ID", "7")
            .build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        long start = System.nanoTime();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(lines.readLine()).isEqualTo("id: 1");
            assertThat(lines.readLine()).isEqualTo("data: first after 7");
            // the first event must not wait for the second, which comes 1.5 seconds later
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1200));
            assertThat(lines.lines().toList()).contains("data: second");
        }
    }

    @Test
    void refusesPathsOutsideTheApiAndUnmarkedChanges() throws Exception {
        assertThat(get("/api/%2e%2e/secret").statusCode()).isIn(400, 404);
        assertThat(get("/api/a;b").statusCode()).isIn(400, 404);
        HttpResponse<String> unmarked = browser.send(HttpRequest.newBuilder(uri("/api/sessions"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unmarked.statusCode()).isEqualTo(403);
    }

    @Test
    void doesNotTrustAnUnknownCertificate() {
        // without the CA file the stand-in server's certificate is not trusted
        ServerConnection connection = new ServerConnection(new UiProperties(
                "https://localhost:" + upstream.getAddress().getPort(), TOKEN, null, null));
        assertThatThrownBy(() -> connection.client().send(
                HttpRequest.newBuilder(URI.create(connection.baseUri() + "/api/me")).build(),
                HttpResponse.BodyHandlers.ofString()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("PKIX");
    }

    @Test
    void requiresHttpsAndAToken() {
        assertThatThrownBy(() -> new ServerConnection(new UiProperties("http://localhost:1", TOKEN, null, null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> new ServerConnection(new UiProperties("https://localhost:1", " ", null, null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("token");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
