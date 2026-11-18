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
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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
 * Forwards {@code /api/**} to the appropriate bob-crew server, adding that server's token, or the logged-in user's
 * ({@link OidcLogin}). The browser never sees a token, and the server needs no CORS. Without a login, it answers
 * 401 with {@code {"login": "/login"}}, for the page to offer one. Responses are streamed through as they arrive,
 * which is what keeps the Server-Sent Events of a session flowing.
 *
 * <p>Creating a session is the one request it rewrites: the browser names a {@code role}, and the request
 * forwarded carries that role's {@code image}, {@code secrets} (with their values from the secrets file,
 * {@link SecretStore}) and {@code repository}. So the values never pass through the browser, and the page cannot
 * choose an image, pass secrets or variables, or have a repository cloned by itself: a request that tries is
 * refused. Nothing comes from this app's own environment.
 *
 * <p>Refreshing a session's secrets ({@code PUT .../sessions/{id}/secrets}) is rewritten the same way: the browser
 * sends nothing, and the request forwarded carries the values the secrets file has now for the secrets the session
 * was created with (the server knows their names; nobody remembers the role). That is how a token that expired is
 * replaced in a running session.
 *
 * <p>Request bodies are read whole (up to {@link #MAX_BODY}) before forwarding, except file uploads
 * ({@code PUT .../sessions/{id}/files}), which are streamed: the server enforces their size limit.
 */
@RestController
class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Largest request body forwarded, in bytes. */
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of("Content-Type", "Accept", "Last-Event-ID");

    /** A file upload, which is streamed rather than read into memory. */
    private static final Pattern UPLOAD_PATH = Pattern.compile("/api/sessions/[^/]+/files");

    /** Replacing the values of a session's secrets, which this app fills in from the secrets file. */
    private static final Pattern SECRETS_PATH = Pattern.compile("/api/sessions/[^/]+/secrets");

    /** Creating a session waits for the server to clone the role's repository, which can take minutes. */
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(15);

    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(30);

    /** Besides Content-Type: the file name of a download, and what keeps the browser from sniffing it. */
    private static final List<String> FORWARDED_RESPONSE_HEADERS = List.of("Cache-Control", "Content-Disposition",
            "X-Content-Type-Options");

    /** Names the secrets a role lists that the secrets file does not have; the page tells the user. */
    static final String MISSING_SECRETS_HEADER = "X-BobCrew-Missing-Secrets";

    private final ServerRegistry registry;

    private final RoleRegistry roles;

    private final SecretStore secrets;

    private final OidcLogin login;

    private final UserProfileStore userProfile;

    private final JsonMapper json = JsonMapper.builder().build();

    ProxyController(ServerRegistry registry, RoleRegistry roles, SecretStore secrets, OidcLogin login, UserProfileStore userProfile) {
        this.registry = registry;
        this.roles = roles;
        this.secrets = secrets;
        this.login = login;
        this.userProfile = userProfile;
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

        String token = server.token();
        if (token == null) {
            try {
                token = login.currentToken();
            } catch (IOException e) {
                log.warn("Cannot reach the identity provider to refresh the login: {}", e.toString());
                sendError(response, HttpServletResponse.SC_BAD_GATEWAY, "Cannot reach the identity provider: "
                        + e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (token == null) {
                sendLogin(response, "Log in to continue");
                return;
            }
        }

        String query = request.getQueryString();
        URI target = URI.create(server.baseUri() + forwardedPath + (query == null ? "" : "?" + query));

        boolean upload = request.getMethod().equals("PUT") && UPLOAD_PATH.matcher(forwardedPath).matches();
        boolean create = request.getMethod().equals("POST") && forwardedPath.equals("/api/sessions");
        boolean refresh = request.getMethod().equals("PUT") && SECRETS_PATH.matcher(forwardedPath).matches();
        List<String> missingSecrets = new ArrayList<>();
        HttpRequest.BodyPublisher publisher;
        if (upload) {
            InputStream in = request.getInputStream();
            long length = request.getContentLengthLong();
            HttpRequest.BodyPublisher streamed = HttpRequest.BodyPublishers.ofInputStream(() -> in);
            publisher = length >= 0 ? HttpRequest.BodyPublishers.fromPublisher(streamed, length) : streamed;
        } else {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "The request is too large");
                return;
            }
            if (create) {
                Optional<byte[]> expanded = expandRole(body, missingSecrets, response);
                if (expanded.isEmpty()) {
                    return;
                }
                body = expanded.get();
            } else if (refresh) {
                Optional<byte[]> filled = fillSecrets(server, token, forwardedPath, body, missingSecrets, response);
                if (filled.isEmpty()) {
                    return;
                }
                body = filled.get();
            }
            publisher = body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
        }
        boolean stream = path.endsWith("/events");
        HttpRequest.Builder forwarded = HttpRequest.newBuilder(target)
            .method(request.getMethod(), publisher)
            .header("Authorization", "Bearer " + token);
        if (!stream) {
            forwarded.timeout(upload ? UPLOAD_TIMEOUT : create ? CREATE_TIMEOUT : Duration.ofSeconds(120));
        }
        for (String name : FORWARDED_REQUEST_HEADERS) {
            String value = request.getHeader(name);
            if (refresh && name.equals("Content-Type")) {
                // the body is this app's, whatever the browser said of its own (empty) one
                value = "application/json";
            }
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
                sendUnauthorized(server, response);
                return;
            }
            response.setStatus(upstream.statusCode());
            if (!missingSecrets.isEmpty()) {
                response.setHeader(MISSING_SECRETS_HEADER, String.join(",", missingSecrets));
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
     * {@code {"title": ..., "image": ..., "secrets": {...}, "repository": {"url": ..., "branch": ...}}}. Secrets the
     * role lists but the secrets file does not have are left out and added to {@code missingSecrets} (names only).
     * @return the body to forward, or empty if an error was sent instead
     */
    private Optional<byte[]> expandRole(byte[] body, List<String> missingSecrets, HttpServletResponse response)
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
        if (request.has("image") || request.has("env") || request.has("secrets") || request.has("repository")
                || request.has("userProfile")) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "The image, secrets, userProfile and repository of a session come from its role or configuration");
            return Optional.empty();
        }
        // the page may ask for capabilities and resources itself; the server checks the values
        JsonNode capabilities = request.get("capabilities");
        if (capabilities != null && !(capabilities.isArray() && capabilities.valueStream().allMatch(JsonNode::isString))) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "capabilities must be a list of names");
            return Optional.empty();
        }
        JsonNode resources = request.get("resources");
        if (resources != null && !(resources instanceof ObjectNode
                && (resources.get("memory") == null || resources.get("memory").isString())
                && (resources.get("cpus") == null || resources.get("cpus").isNumber())
                && resources.propertyNames().stream().allMatch(n -> n.equals("memory") || n.equals("cpus")))) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "resources must be {\"memory\": \"8GB\", \"cpus\": 2}, both optional");
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
            ObjectNode passed = json.createObjectNode();
            if (!role.get().secrets().isEmpty()) {
                Map<String, String> available;
                try {
                    available = secrets.load();
                } catch (IllegalStateException e) {
                    sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                    return Optional.empty();
                }
                for (String name : role.get().secrets()) {
                    String value = available.get(name);
                    if (value == null) {
                        missingSecrets.add(name);
                    } else {
                        passed.put(name, value);
                    }
                }
            }
            if (!passed.isEmpty()) {
                request.set("secrets", passed);
            }
            if (role.get().repository() != null) {
                ObjectNode repository = request.putObject("repository");
                repository.put("url", role.get().repository());
                if (role.get().branch() != null) {
                    repository.put("branch", role.get().branch());
                }
            }
            // the role's capabilities always apply, with any the page adds
            if (!role.get().capabilities().isEmpty()) {
                ArrayNode merged = request.putArray("capabilities");
                java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(role.get().capabilities());
                if (capabilities != null) {
                    capabilities.valueStream().forEach(name -> names.add(name.asString()));
                }
                names.forEach(merged::add);
            }
            // the role's resources, unless the page sends its own
            if (role.get().memory() != null || role.get().cpus() != null) {
                ObjectNode merged = json.createObjectNode();
                if (role.get().memory() != null) {
                    merged.put("memory", role.get().memory());
                }
                if (role.get().cpus() != null) {
                    merged.put("cpus", role.get().cpus());
                }
                if (resources != null) {
                    merged.setAll((ObjectNode) resources);
                }
                request.set("resources", merged);
            }
            log.info("Creating a session with role {} (image {}, secrets {}, not in the secrets file: {}, repository {}, "
                    + "capabilities {}, resources {})",
                    role.get().id(), role.get().image() == null ? "default" : role.get().image(), passed.propertyNames(),
                    missingSecrets, role.get().repository() == null ? "none" : role.get().repository(),
                    request.path("capabilities"), request.path("resources"));
        } else {
            log.info("Creating a session without role (image default, secrets [], repository none, "
                    + "capabilities {}, resources {})",
                    request.path("capabilities"), request.path("resources"));
        }
        List<ProfileFile> profileFiles;
        try {
            profileFiles = userProfile.load();
        } catch (IllegalStateException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return Optional.empty();
        }
        if (!profileFiles.isEmpty()) {
            ArrayNode profileArray = request.putArray("userProfile");
            for (ProfileFile file : profileFiles) {
                ObjectNode fileNode = profileArray.addObject();
                fileNode.put("path", file.path());
                fileNode.put("contentBase64", file.contentBase64());
                fileNode.put("executable", file.executable());
            }
        }
        return Optional.of(json.writeValueAsBytes(request));
    }

    /**
     * Makes the body of a request to replace a session's secrets: {@code {"secrets": {...}}} with the values the
     * secrets file has now for the secrets the session was created with, whose names the server is asked for.
     * Those the file does not have are left as they are in the session, and added to {@code missingSecrets}
     * (names only). The browser sends no values, as when a session is created.
     * @return the body to forward, or empty if an error was sent instead
     */
    private Optional<byte[]> fillSecrets(ServerConnection server, String token, String forwardedPath, byte[] body,
            List<String> missingSecrets, HttpServletResponse response) throws IOException {
        try {
            JsonNode sent = body.length == 0 ? json.createObjectNode() : json.readTree(body);
            if (!(sent instanceof ObjectNode) || !sent.isEmpty()) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "The values of a session's secrets come from this UI's secrets file; send no body");
                return Optional.empty();
            }
        } catch (JacksonException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "The request is not valid JSON");
            return Optional.empty();
        }
        String sessionPath = forwardedPath.substring(0, forwardedPath.length() - "/secrets".length());
        HttpResponse<byte[]> session;
        try {
            session = server.client().send(HttpRequest.newBuilder(URI.create(server.baseUri() + sessionPath))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("Cannot reach {}: {}", server.baseUri(), e.toString());
            sendError(response, HttpServletResponse.SC_BAD_GATEWAY, "Cannot reach the bob-crew server: " + e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (session.statusCode() == HttpServletResponse.SC_UNAUTHORIZED) {
            sendUnauthorized(server, response);
            return Optional.empty();
        }
        JsonNode info;
        try {
            info = json.readTree(session.body());
        } catch (JacksonException e) {
            info = json.createObjectNode();
        }
        if (session.statusCode() != HttpServletResponse.SC_OK) {
            // "No such session", for one: as the server put it
            sendError(response, session.statusCode(), info.path("error").asString("The session cannot be read"));
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        info.path("secrets").valueStream().forEach(name -> names.add(name.asString("")));
        if (names.isEmpty()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "This session was created without secrets, so there is nothing to refresh");
            return Optional.empty();
        }
        Map<String, String> available;
        try {
            available = secrets.load();
        } catch (IllegalStateException e) {
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return Optional.empty();
        }
        ObjectNode passed = json.createObjectNode();
        for (String name : names) {
            String value = available.get(name);
            if (value == null) {
                missingSecrets.add(name);
            } else {
                passed.put(name, value);
            }
        }
        if (passed.isEmpty()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "None of the session's secrets ("
                    + String.join(", ", names) + ") is in the secrets file, so nothing was refreshed");
            return Optional.empty();
        }
        log.info("Refreshing the secrets {} of a session (not in the secrets file: {})", passed.propertyNames(),
                missingSecrets);
        ObjectNode request = json.createObjectNode();
        request.set("secrets", passed);
        return Optional.of(json.writeValueAsBytes(request));
    }

    /** The server refused the token: the user logs in again, or the configured token is to be replaced. */
    private void sendUnauthorized(ServerConnection server, HttpServletResponse response) throws IOException {
        if (server.token() == null) {
            sendLogin(response, "The bob-crew server " + server.name() + " did not accept your login; "
                    + "log in again, or check that it trusts the same identity provider as this UI");
        } else {
            sendError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "The bob-crew server rejected the configured token (expired, revoked or wrong)");
        }
    }

    /** 401, and where the page sends the user to log in. */
    private void sendLogin(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getOutputStream().write(json.writeValueAsBytes(Map.of("error", message, "login", "/login")));
    }

    private static void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", " ");
        response.getOutputStream().write(("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
