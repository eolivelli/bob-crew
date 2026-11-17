package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The UI with a login instead of a configured token, against a stand-in provider ({@link FakeOidcProvider}) and a
 * stand-in server that answers only to the provider's tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class UiLoginTest {

    private static final FakeOidcProvider provider;

    private static final TestHttps upstream;

    private static final Path rolesDir;

    static {
        try {
            provider = new FakeOidcProvider();
            Path directory = Files.createTempDirectory("bobcrew-ui-login-test");
            rolesDir = Files.createDirectory(directory.resolve("roles"));
            upstream = TestHttps.start(directory, "/api/", UiLoginTest::handle);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Makes the stand-in server refuse every token, as a server that does not trust the provider would. */
    private static volatile boolean refuseAll;

    /** Answers to the provider's access tokens, telling which one it got. */
    private static void handle(HttpExchange exchange) throws java.io.IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (refuseAll || authorization == null || !authorization.startsWith("Bearer access-")) {
            TestHttps.respond(exchange, 401, "text/plain", "no");
        } else {
            TestHttps.respond(exchange, 200, "application/json",
                    "{\"user\":\"alice\",\"token\":\"" + authorization.substring(7) + "\"}");
        }
    }

    @AfterAll
    static void stop() {
        provider.stop();
        upstream.server.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bobcrew.ui.server-url", upstream::url);
        registry.add("bobcrew.ui.token", () -> "");
        // whatever ~/.bobcrew/ui.properties on this machine says
        registry.add("bobcrew.ui.token-file", () -> "");
        registry.add("bobcrew.ui.ca-file", upstream.caFile::toString);
        registry.add("bobcrew.ui.roles-dir", rolesDir::toString);
        registry.add("bobcrew.ui.oidc.issuer-uri", provider::issuer);
    }

    @LocalServerPort
    int port;

    @Autowired
    OidcLogin login;

    private final HttpClient browser = HttpClient.newHttpClient();

    @Test
    void logsInForwardsWithTheUsersTokenAndLogsOut() throws Exception {
        assertThat(get("/api/login", Map.of()).body()).contains("\"enabled\":true").contains("\"loggedIn\":false");
        HttpResponse<String> before = get("/api/me", Map.of());
        assertThat(before.statusCode()).isEqualTo(401);
        assertThat(before.body()).contains("\"login\":\"/login\"");

        // the page's "Log in" link
        HttpResponse<String> start = get("/login", Map.of("Sec-Fetch-Site", "same-origin"));
        assertThat(start.statusCode()).isEqualTo(302);
        URI authorization = URI.create(start.headers().firstValue("Location").orElseThrow());
        assertThat(authorization.toString()).startsWith(provider.issuer() + "/auth?");
        Map<String, String> query = FakeOidcProvider.parse(authorization.getRawQuery());
        assertThat(query.get("redirect_uri")).isEqualTo("http://127.0.0.1:" + port + "/login/callback");

        // the provider sends the browser back: a navigation from another site
        HttpResponse<String> back = get("/login/callback?state=" + query.get("state") + "&code="
                + FakeOidcProvider.CODE, Map.of("Sec-Fetch-Site", "cross-site"));
        // not a redirect: "/" reached through a chain from another site would arrive as cross-site and be refused
        assertThat(back.statusCode()).isEqualTo(200);
        assertThat(back.headers().firstValue("Location")).isEmpty();
        assertThat(back.body()).contains("<meta http-equiv=\"refresh\" content=\"0; url=/\">");
        // the page's refresh is a navigation from the UI itself
        assertThat(get("/", Map.of("Sec-Fetch-Site", "same-origin")).statusCode()).isEqualTo(200);
        assertThat(get("/api/login", Map.of()).body()).contains("\"loggedIn\":true");

        HttpResponse<String> me = get("/api/me", Map.of());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"token\":\"access-1\"");

        // a server refusing the login: log in again, rather than a gateway error
        refuseAll = true;
        try {
            HttpResponse<String> refused = get("/api/me", Map.of());
            assertThat(refused.statusCode()).isEqualTo(401);
            assertThat(refused.body()).contains("did not accept your login").contains("\"login\":\"/login\"")
                .doesNotContain("access-");
        } finally {
            refuseAll = false;
        }

        HttpResponse<String> logout = get("/logout", Map.of("Sec-Fetch-Site", "same-origin"));
        assertThat(logout.statusCode()).isEqualTo(302);
        URI endSession = URI.create(logout.headers().firstValue("Location").orElseThrow());
        assertThat(endSession.toString()).startsWith(provider.issuer() + "/logout?");
        assertThat(FakeOidcProvider.parse(endSession.getRawQuery())).containsEntry("id_token_hint", "id-1");
        assertThat(get("/api/me", Map.of()).statusCode()).isEqualTo(401);

        // back from the provider's logout
        HttpResponse<String> loggedOut = get("/login/callback", Map.of("Sec-Fetch-Site", "cross-site"));
        assertThat(loggedOut.statusCode()).isEqualTo(200);
        assertThat(loggedOut.body()).contains("url=/");
    }

    @Test
    void aCallbackItDidNotStartIsRefused() throws Exception {
        HttpResponse<String> forged = get("/login/callback?state=made-up&code=" + FakeOidcProvider.CODE,
                Map.of("Sec-Fetch-Site", "cross-site"));
        assertThat(forged.statusCode()).isEqualTo(400);
        assertThat(get("/login", Map.of("Sec-Fetch-Site", "cross-site")).statusCode())
            .as("another site cannot start a login").isEqualTo(403);
        assertThat(get("/logout", Map.of("Sec-Fetch-Site", "cross-site")).statusCode())
            .as("nor log the user out").isEqualTo(403);
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(request::header);
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
