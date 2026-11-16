package bobcrew.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Forwards {@code /api/**} to the appropriate bob-crew server, adding that server's token. The browser
 * never sees the token, and the server needs no CORS. Responses are streamed through as they arrive,
 * which is what keeps the Server-Sent Events of a session flowing.
 */
@RestController
class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Largest request body forwarded, in bytes. */
    private static final int MAX_BODY = 1024 * 1024;

    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of("Content-Type", "Accept", "Last-Event-ID");

    private final ServerRegistry registry;

    private final JsonMapper json = JsonMapper.builder().build();

    ProxyController(ServerRegistry registry) {
        this.registry = registry;
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
            upstream.headers().firstValue("Content-Type").ifPresent(response::setContentType);
            upstream.headers().firstValue("Cache-Control").ifPresent(value -> response.setHeader("Cache-Control", value));
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

    private static void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", " ");
        response.getOutputStream().write(("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
