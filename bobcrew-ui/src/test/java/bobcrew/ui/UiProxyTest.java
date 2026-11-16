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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

    private static final Path rolesDir;

    static {
        try {
            Path directory = Files.createTempDirectory("bobcrew-ui-test");
            rolesDir = Files.createDirectory(directory.resolve("roles"));
            Files.writeString(rolesDir.resolve("qa-tester.md"),
                    "---\nname: QA Tester\ndescription: Finds defects\n---\nTest the app.\n");
            Files.writeString(rolesDir.resolve("builder.md"),
                    "---\nname: Builder\nimage: bobcrew/bob-java:latest\nenv: GH_TOKEN, NOT_SET_HERE\n---\nBuild it.\n");
            Files.writeString(rolesDir.resolve("cloner.md"),
                    "---\nname: Cloner\nrepository: https://github.com/datastax/cassandra\nbranch: main\n---\nFix it.\n");
            Files.writeString(rolesDir.resolve("power.md"),
                    "---\nname: Power\nimage: bobcrew/bob-power:latest\ncapabilities: docker\nmemory: 8GB\ncpus: 2\n---\nRun VMs.\n");
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
        } else if (path.endsWith("/files")) {
            respond(exchange, 201, "application/json", "{\"method\":\"" + exchange.getRequestMethod()
                    + "\",\"uri\":\"" + exchange.getRequestURI() + "\",\"received\":" + body.length
                    + ",\"sum\":" + java.util.Arrays.hashCode(body) + "}");
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
        registry.add("bobcrew.ui.roles-dir", rolesDir::toString);
    }

    /** Stands in for the UI app's own environment. */
    @TestConfiguration
    static class FakeEnvironment {

        @Bean
        @Primary
        HostEnvironment fakeHostEnvironment() {
            return new HostEnvironment() {
                @Override
                String get(String name) {
                    return name.equals("GH_TOKEN") ? "secret-gh-token" : null;
                }
            };
        }
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
    void servesTheFavicon() throws Exception {
        assertThat(get("/").body()).contains("<link rel=\"icon\" href=\"icon.svg\"").contains("href=\"favicon.ico\"")
            .contains("href=\"apple-touch-icon.png\"");
        for (String icon : new String[] {"/icon.svg", "/favicon.ico", "/apple-touch-icon.png"}) {
            assertThat(get(icon).statusCode()).as(icon).isEqualTo(200);
        }
    }

    @Test
    void servesTheTranscriptExportAndLinks() throws Exception {
        // the behaviour itself is checked by src/test/js/transcript-export.test.js and links.test.js
        assertThat(get("/").body()).contains("id=\"export\"").contains("id=\"clear\"").contains("src=\"transcript-export.js\"")
            .contains("src=\"links.js\"");
        assertThat(get("/links.js").body()).contains("function linkify");
        HttpResponse<String> script = get("/transcript-export.js");
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.body()).contains("function buildTranscriptHtml").doesNotContain(TOKEN);
    }

    @Test
    void servesThePageWithTheRoleControls() throws Exception {
        // the behaviour is in app.js and is checked by hand; here only that the page offers it
        assertThat(get("/").body()).contains("id=\"new-role\"").contains("id=\"show-roles\"").contains("id=\"roles-view\"");
        assertThat(get("/app.js").body()).contains("function showRoles").doesNotContain("innerHTML");
        // the agent's command list is not shown as a raw JSON card
        assertThat(get("/app.js").body()).contains("case 'available_commands_update':");
    }

    @Test
    void servesRolesLocallyWithoutForwardingThem() throws Exception {
        HttpResponse<String> listed = get("/api/roles");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("\"id\":\"qa-tester\"").contains("\"name\":\"QA Tester\"")
            .contains("\"description\":\"Finds defects\"").doesNotContain("Test the app.").doesNotContain("\"method\"");

        HttpResponse<String> detail = get("/api/roles/qa-tester");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"prompt\":\"Test the app.\"").contains("\"manifest\":\"---\\nname: QA Tester")
            .doesNotContain("\"method\"");

        HttpResponse<String> unknown = get("/api/roles/nope");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("No such role").doesNotContain("\"method\"");
        assertThat(get("/api/roles/a.b").statusCode()).isEqualTo(404);
        assertThat(get("/api/roles/%2e%2e").statusCode()).isIn(400, 404);
    }

    @Test
    void rolesAreOnlyServedToTheLocalBrowser() throws Exception {
        HttpResponse<String> foreign = browser.send(HttpRequest.newBuilder(uri("/api/roles"))
            .header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(403);
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
            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"demo\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(posted.body()).contains("\"method\":\"POST\"")
            .contains("\"contentType\":\"application/json\"")
            .contains("\"body\":{\"text\":\"demo\"}");
    }

    @Test
    void turnsARoleIntoTheImageAndEnvironmentOfANewSession() throws Exception {
        HttpResponse<String> created = post("/api/sessions", "{\"title\":\"t\",\"role\":\"builder\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).contains("\"body\":{\"title\":\"t\",\"image\":\"bobcrew/bob-java:latest\","
                + "\"env\":{\"GH_TOKEN\":\"secret-gh-token\"}}");
        assertThat(created.headers().firstValue(ProxyController.MISSING_ENV_HEADER)).hasValue("NOT_SET_HERE");

        // on a named server too
        HttpResponse<String> routed = post("/api/servers/localhost/sessions", "{\"role\":\"builder\"}");
        assertThat(routed.body()).contains("\"uri\":\"/api/sessions\"").contains("secret-gh-token");

        // a role with a repository to clone
        assertThat(post("/api/sessions", "{\"role\":\"cloner\"}").body()).contains("\"body\":{\"repository\":"
                + "{\"url\":\"https://github.com/datastax/cassandra\",\"branch\":\"main\"}}");

        // a role's capabilities always apply, with any the page adds; its resources unless the page sends its own
        assertThat(post("/api/sessions", "{\"role\":\"power\"}").body())
            .contains("\"body\":{\"image\":\"bobcrew/bob-power:latest\",\"capabilities\":[\"docker\"],"
                    + "\"resources\":{\"memory\":\"8GB\",\"cpus\":2.0}}");
        assertThat(post("/api/sessions", "{\"role\":\"power\",\"capabilities\":[\"libvirt\"],"
                + "\"resources\":{\"cpus\":4}}").body())
            .contains("\"capabilities\":[\"docker\",\"libvirt\"]")
            .contains("\"resources\":{\"memory\":\"8GB\",\"cpus\":4}");
        // without a role the page may ask for them itself; the server decides
        assertThat(post("/api/sessions", "{\"capabilities\":[\"docker\"],\"resources\":{\"memory\":\"1GB\"}}").body())
            .contains("\"body\":{\"capabilities\":[\"docker\"],\"resources\":{\"memory\":\"1GB\"}}");
        for (String bad : new String[] {"{\"capabilities\":\"docker\"}", "{\"capabilities\":[1]}",
                "{\"resources\":{\"memory\":8}}", "{\"resources\":{\"cpus\":\"2\"}}", "{\"resources\":{\"disk\":\"1GB\"}}",
                "{\"resources\":[]}"}) {
            assertThat(post("/api/sessions", bad).statusCode()).as(bad).isEqualTo(400);
        }
        assertThat(get("/api/roles").body()).contains("\"id\":\"power\"").contains("\"capabilities\":[\"docker\"]")
            .contains("\"memory\":\"8GB\"").contains("\"cpus\":2.0");

        // a role without image or env, and no role at all
        assertThat(post("/api/sessions", "{\"role\":\"qa-tester\"}").body()).contains("\"body\":{}");
        assertThat(post("/api/sessions", "{\"title\":\"plain\"}").body()).contains("\"body\":{\"title\":\"plain\"}");
        assertThat(post("/api/sessions", "").body()).contains("\"body\":{}");
    }

    @Test
    void thePageCannotChooseTheImageOrEnvironmentItself() throws Exception {
        for (String body : new String[] {"{\"image\":\"evil/image\"}", "{\"env\":{\"X\":\"1\"}}",
                "{\"role\":\"builder\",\"env\":{}}", "{\"repository\":{\"url\":\"https://evil.example/x\"}}",
                "{\"role\":\"cloner\",\"repository\":null}"}) {
            HttpResponse<String> refused = post("/api/sessions", body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(refused.body()).as(body).doesNotContain("\"method\"").doesNotContain("secret-gh-token");
        }
        HttpResponse<String> unknown = post("/api/sessions", "{\"role\":\"nope\"}");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("No such role");
        assertThat(post("/api/sessions", "[1]").statusCode()).isEqualTo(400);
        assertThat(post("/api/sessions", "not json").statusCode()).isEqualTo(400);
        // the role list shows names, never values
        assertThat(get("/api/roles").body()).contains("\"env\":[\"GH_TOKEN\",\"NOT_SET_HERE\"]")
            .contains("\"image\":\"bobcrew/bob-java:latest\"").doesNotContain("secret-gh-token");
    }

    @Test
    void streamsUploadsLargerThanOtherRequestsMayBe() throws Exception {
        byte[] content = new byte[3 * 1024 * 1024];
        new java.util.Random(7).nextBytes(content);
        HttpResponse<String> uploaded = browser.send(HttpRequest.newBuilder(uri("/api/sessions/abc/files?path=a%20b.bin"))
            .header("X-BobCrew", "1")
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).isEqualTo(201);
        assertThat(uploaded.body()).contains("\"method\":\"PUT\"", "\"uri\":\"/api/sessions/abc/files?path=a%20b.bin\"",
                "\"received\":" + content.length, "\"sum\":" + java.util.Arrays.hashCode(content));

        // other requests are still limited
        HttpResponse<String> large = browser.send(HttpRequest.newBuilder(uri("/api/sessions/abc/prompt"))
            .header("X-BobCrew", "1")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(content))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(large.statusCode()).isEqualTo(413);

        // and an upload needs the page's header like any change
        assertThat(browser.send(HttpRequest.newBuilder(uri("/api/sessions/abc/files?path=x"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[1]))
            .build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path))
            .header("X-BobCrew", "1")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void listsConfiguredServersAndRoutesByServerName() throws Exception {
        HttpResponse<String> servers = get("/api/servers");
        assertThat(servers.statusCode()).isEqualTo(200);
        assertThat(servers.body()).contains("\"name\":\"localhost\"")
            .contains("https://localhost:" + upstream.getAddress().getPort());

        HttpResponse<String> serverRouted = get("/api/servers/localhost/sessions");
        assertThat(serverRouted.statusCode()).isEqualTo(200);
        assertThat(serverRouted.body()).contains("\"method\":\"GET\"").contains("\"uri\":\"/api/sessions\"");

        HttpResponse<String> unknownServer = get("/api/servers/nonexistent/sessions");
        assertThat(unknownServer.statusCode()).isEqualTo(404);
        assertThat(unknownServer.body()).contains("Unknown server: nonexistent");
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
