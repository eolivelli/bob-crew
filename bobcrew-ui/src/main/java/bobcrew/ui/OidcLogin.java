package bobcrew.ui;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Logs the user of this UI in with an OpenID Connect provider (Keycloak), with the authorization code flow and
 * PKCE, as a public client ({@code bobcrew.ui.oidc.client-id}): every user runs their own UI, so a client secret
 * could not stay secret. The tokens stay in this process's memory: the browser never sees them, and restarting
 * the UI means logging in again (without a password while the provider's own session lasts). One login serves
 * every configured server that has no token of its own.
 *
 * <p>The browser is sent to the provider with a fresh {@code state} and PKCE challenge; the provider sends it back
 * to {@code http://127.0.0.1:<port>/login/callback} (the realm registers that address without a port, which matches
 * any port), where {@code state} must match a login this UI started, once. The code is then exchanged, with the PKCE
 * verifier, over a direct HTTPS call. The access token is refreshed shortly before it expires; when that fails
 * (the provider's session ended), the user must log in again.
 */
@Component
class OidcLogin {

    private static final Logger log = LoggerFactory.getLogger(OidcLogin.class);

    /** How long a login started may take to come back. */
    static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(10);

    /** Most logins waiting at once: each browser tab that opens /login starts one. */
    private static final int MAX_PENDING = 20;

    /** The access token is refreshed when it has less than this left. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Where the provider's endpoints are, from its discovery document. */
    private record Endpoints(URI authorization, URI token, URI endSession) {
    }

    private record Pending(String verifier, String redirectUri, Instant expires) {
    }

    private record Tokens(String access, Instant accessExpires, String refresh, String id) {
    }

    /** What the user's browser should do next. */
    record Redirect(URI location) {
    }

    private final String issuer;

    private final String clientId;

    private final HttpClient http;

    private final Clock clock;

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    private Endpoints endpoints;

    private Tokens tokens;

    @Autowired
    OidcLogin(UiProperties properties) {
        this(properties.oidc(), Clock.systemUTC());
    }

    OidcLogin(UiProperties.Oidc settings, Clock clock) {
        this.clock = clock;
        if (settings == null || settings.issuerUri() == null || settings.issuerUri().isBlank()) {
            this.issuer = null;
            this.clientId = null;
            this.http = null;
            return;
        }
        this.issuer = settings.issuerUri().strip().replaceAll("/+$", "");
        URI uri = URI.create(issuer);
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || isLoopback(uri))) {
            // tokens and the user's login travel through it
            throw new IllegalStateException("bobcrew.ui.oidc.issuer-uri must be an https URL (http only on this "
                    + "machine's loopback, for tests), but is " + issuer);
        }
        this.clientId = settings.clientId() == null || settings.clientId().isBlank() ? "bobcrew-ui"
                : settings.clientId().strip();
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER);
        if (settings.caFile() != null && !settings.caFile().isBlank()) {
            builder.sslContext(ServerConnection.trusting(java.nio.file.Path.of(settings.caFile().strip())));
        }
        this.http = builder.build();
        log.info("Users log in with {} (client {})", issuer, clientId);
    }

    /** Whether this UI logs its user in with a provider at all. */
    boolean enabled() {
        return issuer != null;
    }

    synchronized boolean loggedIn() {
        return tokens != null;
    }

    /**
     * Starts a login: where to send the browser.
     * @param port this UI's port, for the address the provider sends the browser back to
     */
    synchronized Redirect start(int port) throws IOException, InterruptedException {
        Endpoints known = endpoints();
        Instant now = clock.instant();
        pending.values().removeIf(login -> login.expires().isBefore(now));
        while (pending.size() >= MAX_PENDING) {
            Iterator<String> oldest = pending.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        String state = random(24);
        String verifier = random(48);
        String redirectUri = callbackUri(port);
        pending.put(state, new Pending(verifier, redirectUri, now.plus(LOGIN_TIMEOUT)));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("scope", "openid");
        query.put("state", state);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        return new Redirect(withQuery(known.authorization(), query));
    }

    /**
     * Finishes a login the provider sent back: exchanges the code for tokens.
     * @return false if this is not a login this UI started (unknown, reused or expired {@code state}), or the
     * provider reports an error
     */
    boolean finish(String state, String code, String error) throws IOException, InterruptedException {
        Pending login;
        synchronized (this) {
            login = state == null ? null : pending.remove(state);
        }
        if (login == null || login.expires().isBefore(clock.instant())) {
            return false;
        }
        if (error != null || code == null || code.isBlank()) {
            log.info("The identity provider did not log the user in ({})", error == null ? "no code" : error);
            return false;
        }
        JsonNode response = tokenRequest(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri",
                login.redirectUri(), "client_id", clientId, "code_verifier", login.verifier()));
        if (response == null) {
            return false;
        }
        store(response, null);
        log.info("Logged in with {}", issuer);
        return true;
    }

    /**
     * The access token to present to servers: refreshed when it is about to expire.
     * @return null when nobody is logged in, or the login ended (the refresh was refused)
     * @throws IOException when the provider cannot be reached to refresh it
     */
    synchronized String currentToken() throws IOException, InterruptedException {
        if (tokens == null) {
            return null;
        }
        if (clock.instant().plus(REFRESH_MARGIN).isBefore(tokens.accessExpires())) {
            return tokens.access();
        }
        if (tokens.refresh() == null) {
            tokens = null;
            return null;
        }
        JsonNode response = tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", tokens.refresh(),
                "client_id", clientId));
        if (response == null) {
            log.info("The login with {} ended; the user must log in again", issuer);
            tokens = null;
            return null;
        }
        store(response, tokens);
        return tokens.access();
    }

    /**
     * Forgets the tokens and returns where to send the browser to end the provider's session too, or null if the
     * provider has no logout endpoint.
     */
    synchronized Redirect logout(int port) throws IOException, InterruptedException {
        Tokens ended = tokens;
        tokens = null;
        Endpoints known = endpoints();
        if (known.endSession() == null) {
            return null;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("post_logout_redirect_uri", callbackUri(port));
        if (ended != null && ended.id() != null) {
            query.put("id_token_hint", ended.id());
        }
        return new Redirect(withQuery(known.endSession(), query));
    }

    private void store(JsonNode response, Tokens previous) {
        long expiresIn = response.path("expires_in").asLong(60);
        String refresh = response.path("refresh_token").asString(null);
        String id = response.path("id_token").asString(null);
        synchronized (this) {
            tokens = new Tokens(response.get("access_token").asString(), clock.instant().plusSeconds(expiresIn),
                    refresh != null ? refresh : previous == null ? null : previous.refresh(),
                    id != null ? id : previous == null ? null : previous.id());
        }
    }

    /** POSTs to the token endpoint; null when the provider refuses (400 or 401), with the reason logged. */
    private JsonNode tokenRequest(Map<String, String> form) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoints().token())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
            .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            // the body names the OAuth error (invalid_grant and such); it holds no token
            log.info("The identity provider refused a token request: {}", errorOf(response.body()));
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("The identity provider answered HTTP " + response.statusCode());
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (JacksonException e) {
            throw new IOException("The identity provider's answer is not JSON");
        }
        if (!body.path("access_token").isString()) {
            throw new IOException("The identity provider's answer has no access token");
        }
        return body;
    }

    private static String errorOf(String body) {
        try {
            JsonNode error = JSON.readTree(body);
            return error.path("error").asString("unknown") + (error.has("error_description")
                    ? " (" + error.get("error_description").asString() + ")" : "");
        } catch (JacksonException e) {
            return "unknown";
        }
    }

    /** The provider's endpoints, from its discovery document, fetched once (again after a failure). */
    private synchronized Endpoints endpoints() throws IOException, InterruptedException {
        if (endpoints != null) {
            return endpoints;
        }
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(issuer
                + "/.well-known/openid-configuration")).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("The identity provider's configuration is unavailable (HTTP "
                    + response.statusCode() + ")");
        }
        JsonNode discovery;
        try {
            discovery = JSON.readTree(response.body());
        } catch (JacksonException e) {
            throw new IOException("The identity provider's configuration is not JSON");
        }
        if (!issuer.equals(discovery.path("issuer").asString(""))) {
            throw new IOException("The identity provider calls itself " + discovery.path("issuer").asString("nothing")
                    + ", not " + issuer + "; set bobcrew.ui.oidc.issuer-uri to exactly its issuer");
        }
        String endSession = discovery.path("end_session_endpoint").asString(null);
        endpoints = new Endpoints(endpoint(discovery, "authorization_endpoint"), endpoint(discovery, "token_endpoint"),
                endSession == null ? null : URI.create(endSession));
        return endpoints;
    }

    private static URI endpoint(JsonNode discovery, String name) throws IOException {
        String value = discovery.path(name).asString(null);
        if (value == null) {
            throw new IOException("The identity provider's configuration has no " + name);
        }
        return URI.create(value);
    }

    /**
     * Always 127.0.0.1, even when the page was opened as localhost: the realm registers the loopback address
     * without a port, which the provider then matches for any port (RFC 8252); it does not do that for names.
     */
    static String callbackUri(int port) {
        return "http://127.0.0.1:" + port + "/login/callback";
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
                && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"));
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static URI withQuery(URI base, Map<String, String> query) {
        String separator = base.getRawQuery() == null ? "?" : "&";
        return URI.create(base + separator + encode(query));
    }

    private static String encode(Map<String, String> fields) {
        return fields.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }
}
