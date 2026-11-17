package bobcrew.ui;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in OpenID Connect provider over plain HTTP on the loopback interface (which {@link OidcLogin} allows for
 * tests): discovery, and a token endpoint that accepts the code {@value #CODE} and refresh tokens it issued. It
 * hands out {@code access-1}, {@code access-2}, and so on, and records every token request.
 */
final class FakeOidcProvider {

    static final String CODE = "good-code";

    final HttpServer server;

    final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

    final AtomicInteger issued = new AtomicInteger();

    /** Seconds the access tokens it issues last. */
    volatile int expiresIn = 300;

    /** Makes it refuse refresh tokens, as when the provider's session ended. */
    volatile boolean refuseRefresh;

    /** What its discovery document calls it; its own address by default. */
    volatile String claimedIssuer;

    FakeOidcProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/realms/test/", this::handle);
        server.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test";
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/.well-known/openid-configuration")) {
            String issuer = issuer();
            TestHttps.respond(exchange, 200, "application/json", "{\"issuer\":\""
                    + (claimedIssuer != null ? claimedIssuer : issuer) + "\",\"authorization_endpoint\":\"" + issuer
                    + "/auth\",\"token_endpoint\":\"" + issuer + "/token\",\"end_session_endpoint\":\"" + issuer
                    + "/logout\"}");
        } else if (path.endsWith("/token") && exchange.getRequestMethod().equals("POST")) {
            Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokenRequests.add(form);
            boolean ok = switch (form.getOrDefault("grant_type", "")) {
                case "authorization_code" -> CODE.equals(form.get("code"));
                case "refresh_token" -> !refuseRefresh && form.getOrDefault("refresh_token", "").startsWith("refresh-");
                default -> false;
            };
            if (!ok) {
                TestHttps.respond(exchange, 400, "application/json",
                        "{\"error\":\"invalid_grant\",\"error_description\":\"no\"}");
                return;
            }
            int n = issued.incrementAndGet();
            TestHttps.respond(exchange, 200, "application/json", "{\"access_token\":\"access-" + n
                    + "\",\"expires_in\":" + expiresIn + ",\"refresh_token\":\"refresh-" + n + "\",\"id_token\":\"id-"
                    + n + "\",\"token_type\":\"Bearer\"}");
        } else {
            TestHttps.respond(exchange, 404, "text/plain", "no");
        }
    }

    static Map<String, String> parse(String query) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return fields;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            fields.put(key, value);
        }
        return fields;
    }
}
