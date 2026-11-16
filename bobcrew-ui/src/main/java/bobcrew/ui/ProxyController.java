package bobcrew.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Forwards {@code /api/**} to the appropriate bob-crew server, adding that server's token. The browser
 * never sees the token, and the server needs no CORS. Responses are streamed through as they arrive,
 * which is what keeps the Server-Sent Events of a session flowing.
 *
 * <p>Creating a session is the one request it rewrites: the browser names a {@code role}, and the request
 * forwarded carries that role's {@code image} and {@code env}, with the values of the environment variables
 * read here, from this app's environment. So the values never pass through the browser, and the page cannot
 * choose an image or set variables by itself: a request that tries is refused.
 */
@RestController
class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Largest request body forwarded, in bytes. */
    private static final int MAX_BODY = 1024 * 1024;

    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of("Content-Type", "Accept", "Last-Event-ID");

    /** Besides Content-Type: the file name of a download, and what keeps the browser from sniffing it. */
    private static final List<String> FORWARDED_RESPONSE_HEADERS = List.of("Cache-Control", "Content-Disposition",
            "X-Content-Type-Options");

    /** Names the environment variables a role lists that are not set here; the page tells the user. */
    static final String MISSING_ENV_HEADER = "X-BobCrew-Missing-Env";

    private final ServerRegistry registry;

    private final RoleRegistry roles;

    private final HostEnvironment environment;

    private final JsonMapper json = JsonMapper.builder().build();

    ProxyController(ServerRegistry registry, RoleRegistry roles, HostEnvironment environment) {
        this.registry = registry;
        this.roles = roles;
        this.environment = environment;
    }

    @GetMapping("/api/servers")
    void listServers(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        ArrayNode array = json.createArrayNode();
        for (ServerConnection server : registry.all()) {
            ObjectNode node = array.addObject();
            node.put("name", server.name());
            node.put("url", server.baseUri().toString());
        }
        response.getOutputStream().write(json.writeValueAsBytes(array));
    }

    @RequestMapping("/api/**")
    void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        // the raw path is forwarded as is, so it must be plainly under /api/
        if (!path.startsWith("/api/") || path.contains("..") || path.contains("//") || path.contains("\\")
                || path.contains("%2e") || path.contains("%2E") || path.contains("%2f") || path.contains("%2F")
                || path.contains("%5c") || path.contains("%5C") || path.contains(";")) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid path");
            return;
        }

        ServerConnection server;
        String forwardedPath;

        if (path.startsWith("/api/servers/")) {
            String after = path.substring("/api/servers/".length());
            int slash = after.indexOf('/');
            String serverName;
            if (slash < 0) {
                serverName = after;
                forwardedPath = "/api";
            } else {
                serverName = after.substring(0, slash);
                forwardedPath = "/api" + after.substring(slash);
            }
            server = registry.get(serverName);
            if (server == null) {
                sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown server: " + serverName);
                return;
            }
        } else {
            server = registry.defaultServer();
            forwardedPath = path;
        }

        String query = request.getQueryString();
        URI target = URI.create(server.baseUri() + forwardedPath + (query == null ? "" : "?" + query));

        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "The request is too large");
            return;
        }
        List<String> missingEnv = new ArrayList<>();
        if (request.getMethod().equals("POST") && forwardedPath.equals("/api/sessions")) {
            Optional<byte[]> expanded = expandRole(body, missingEnv, response);
            if (expanded.isEmpty()) {
                return;
            }
            body = expanded.get();
        }
        boolean stream = path.endsWith("/events");
        HttpRequest.Builder forwarded = HttpRequest.newBuilder(target)
            .method(request.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body))
            .header("Authorization", "Bearer " + server.token());
        if (!stream) {
            forwarded.timeout(Duration.ofSeconds(120));
        }
        for (String name : FORWARDED_REQUEST_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) {
                forwarded.header(name, value);
            }
        }

        HttpResponse<InputStream> upstream;
        try {
            upstream = server.client().send(forwarded.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("Cannot reach {}: {}", server.baseUri(), e.toString());
            sendError(response, HttpServletResponse.SC_BAD_GATEWAY, "Cannot reach the bob-crew server: " + e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try (InputStream in = upstream.body()) {
            if (upstream.statusCode() == HttpServletResponse.SC_UNAUTHORIZED) {
                sendError(response, HttpServletResponse.SC_BAD_GATEWAY,
                        "The bob-crew server rejected the configured token (expired, revoked or wrong)");
                return;
            }
            response.setStatus(upstream.statusCode());
            if (!missingEnv.isEmpty()) {
                response.setHeader(MISSING_ENV_HEADER, String.join(",", missingEnv));
            }
            upstream.headers().firstValue("Content-Type").ifPresent(response::setContentType);
            for (String name : FORWARDED_RESPONSE_HEADERS) {
                upstream.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
            }
            OutputStream out = response.getOutputStream();
            if (stream) {
                // send the headers now: the first event may be a while away
                response.flushBuffer();
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (stream) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            // the browser or the server went away; closing the upstream body ends the other side
            log.debug("Forwarding {} ended: {}", path, e.toString());
        }
    }

    /**
     * Turns the browser's {@code {"title": ..., "role": ...}} into what the server takes:
     * {@code {"title": ..., "image": ..., "env": {...}}}. Variables the role lists but that are not set here are
     * left out and added to {@code missingEnv} (names only).
     * @return the body to forward, or empty if an error was sent instead
     */
    private Optional<byte[]> expandRole(byte[] body, List<String> missingEnv, HttpServletResponse response)
            throws IOException {
        ObjectNode request;
        try {
            JsonNode parsed = body.length == 0 ? json.createObjectNode() : json.readTree(body);
            if (!(parsed instanceof ObjectNode object)) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST, "The request must be a JSON object");
                return Optional.empty();
            }
            request = object;
        } catch (JacksonException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "The request is not valid JSON");
            return Optional.empty();
        }
        if (request.has("image") || request.has("env")) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "The image and environment of a session come from its role");
            return Optional.empty();
        }
        JsonNode roleId = request.remove("role");
        if (roleId != null && !roleId.isNull() && !roleId.asString("").isBlank()) {
            Optional<RoleRegistry.Role> role = roles.get(roleId.asString(""));
            if (role.isEmpty()) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST, "No such role: " + roleId.asString(""));
                return Optional.empty();
            }
            if (role.get().image() != null) {
                request.put("image", role.get().image());
            }
            ObjectNode env = json.createObjectNode();
            for (String name : role.get().env()) {
                String value = environment.get(name);
                if (value == null) {
                    missingEnv.add(name);
                } else {
                    env.put(name, value);
                }
            }
            if (!env.isEmpty()) {
                request.set("env", env);
            }
            log.info("Creating a session with role {} (image {}, environment {}, not set here: {})", role.get().id(),
                    role.get().image() == null ? "default" : role.get().image(), env.propertyNames(), missingEnv);
        }
        return Optional.of(json.writeValueAsBytes(request));
    }

    private static void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", " ");
        response.getOutputStream().write(("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
