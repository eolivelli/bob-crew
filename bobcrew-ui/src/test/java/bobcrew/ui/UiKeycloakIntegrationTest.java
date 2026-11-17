package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import bobcrew.testsupport.KeycloakAvailable;
import bobcrew.testsupport.TestKeycloak;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Logging in to the UI through a real Keycloak's login page, with the project's realm as it ships: on a random
 * port (so the realm's port-less loopback redirect address is what lets it in), then using the login on a stand-in
 * server that asks Keycloak who the token belongs to, then logging out at Keycloak too.
 */
@ExtendWith(KeycloakAvailable.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UiKeycloakIntegrationTest {

    private static final Pattern LOGIN_FORM = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*>");

    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]+)\"");

    private static final TestHttps upstream;

    private static final Path rolesDir;

    static {
        try {
            Path directory = Files.createTempDirectory("bobcrew-ui-keycloak-test");
            rolesDir = Files.createDirectory(directory.resolve("roles"));
            upstream = TestHttps.start(directory, "/api/", UiKeycloakIntegrationTest::handle);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A stand-in server: asks Keycloak whose token it got, as the real one checks it with Keycloak's keys. */
    private static void handle(HttpExchange exchange) throws java.io.IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        try {
            HttpResponse<String> userinfo = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                    TestKeycloak.get().issuer() + "/protocol/openid-connect/userinfo"))
                .header("Authorization", authorization == null ? "" : authorization)
                .build(), HttpResponse.BodyHandlers.ofString());
            TestHttps.respond(exchange, userinfo.statusCode() == 200 ? 200 : 401, "application/json",
                    userinfo.statusCode() == 200 ? userinfo.body() : "{}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void stop() {
        upstream.server.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bobcrew.ui.server-url", upstream::url);
        registry.add("bobcrew.ui.token", () -> "");
        registry.add("bobcrew.ui.token-file", () -> "");
        registry.add("bobcrew.ui.ca-file", upstream.caFile::toString);
        registry.add("bobcrew.ui.roles-dir", rolesDir::toString);
        registry.add("bobcrew.ui.oidc.issuer-uri", () -> TestKeycloak.get().issuer());
    }

    @LocalServerPort
    int port;

    /** The user's browser: keeps Keycloak's cookies, follows no redirects by itself. */
    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(new LoopbackCookies()).build();

    /**
     * Cookies as a browser keeps them for a loopback address: Keycloak marks its cookies Secure, which browsers
     * still send to http://127.0.0.1 (a secure context), and the JDK's {@link CookieManager} does not.
     */
    private static final class LoopbackCookies extends java.net.CookieHandler {

        private final Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Map<String, java.util.List<String>> get(URI uri, Map<String, java.util.List<String>> headers) {
            if (cookies.isEmpty()) {
                return Map.of();
            }
            String header = cookies.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
            return Map.of("Cookie", java.util.List.of(header));
        }

        @Override
        public void put(URI uri, Map<String, java.util.List<String>> headers) {
            headers.forEach((name, values) -> {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    for (String value : values) {
                        for (java.net.HttpCookie cookie : java.net.HttpCookie.parse(value)) {
                            if (cookie.getMaxAge() == 0) {
                                cookies.remove(cookie.getName());
                            } else {
                                cookies.put(cookie.getName(), cookie.getValue());
                            }
                        }
                    }
                }
            });
        }
    }

    @Test
    void logsInThroughKeycloaksPageAndOutAgain() throws Exception {
        TestKeycloak keycloak = TestKeycloak.get();
        keycloak.user("erin");

        URI authorization = location(get(URI.create("http://127.0.0.1:" + port + "/login")));
        HttpResponse<String> page = get(authorization);
        assertThat(page.statusCode()).as("Keycloak accepts the UI's callback on port " + port + ": " + page.body())
            .isEqualTo(200);
        Matcher form = LOGIN_FORM.matcher(page.body());
        assertThat(form.find()).as("Keycloak's login form").isTrue();
        Matcher action = ACTION.matcher(form.group());
        assertThat(action.find()).isTrue();
        HttpResponse<String> posted = browser.send(HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(TestKeycloak.form(Map.of("username", "erin", "password",
                    TestKeycloak.PASSWORD, "credentialId", ""))))
            .build(), HttpResponse.BodyHandlers.ofString());
        URI callback = location(posted);
        assertThat(callback.toString()).startsWith("http://127.0.0.1:" + port + "/login/callback?");

        // the browser comes back from Keycloak: a navigation from another site
        HttpResponse<String> back = browser.send(HttpRequest.newBuilder(callback).header("Sec-Fetch-Site", "cross-site")
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(back.statusCode()).as(back.body()).isEqualTo(302);

        HttpResponse<String> me = get(URI.create("http://127.0.0.1:" + port + "/api/me"));
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).contains("\"preferred_username\":\"erin\"");

        URI endSession = location(get(URI.create("http://127.0.0.1:" + port + "/logout")));
        assertThat(endSession.toString()).startsWith(keycloak.issuer() + "/protocol/openid-connect/logout?");
        URI afterLogout = location(get(endSession));
        assertThat(afterLogout.toString()).as("Keycloak accepts the way back")
            .startsWith("http://127.0.0.1:" + port + "/login/callback");
        assertThat(get(URI.create("http://127.0.0.1:" + port + "/api/me")).statusCode()).isEqualTo(401);

        // Keycloak's own session ended too: a new login asks for the password again
        HttpResponse<String> again = get(location(get(URI.create("http://127.0.0.1:" + port + "/login"))));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(LOGIN_FORM.matcher(again.body()).find()).isTrue();
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI location(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.uri() + ": " + response.body()).isIn(302, 303);
        return URI.create(response.headers().firstValue("Location").orElseThrow());
    }
}
