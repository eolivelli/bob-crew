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
 * {@link SecretStore}) and {@code repository}, and, whatever the role, the user's own Bob API key as {@code apiKey}
 * when the secrets file has one ({@link SecretStore#API_KEY}). So the values never pass through the browser, and
 * the page cannot choose an image or pass secrets, a key or variables: a request that tries is refused. Nothing comes from this app's
 * own environment. What the user may change when starting a session is the repository to clone (the role's is
 * the default; the page's own, or none, replaces it), the capabilities (the role's are the default; the
 * page's list replaces them) and the resources.
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

    /** Starting a failed session's agent again: its secrets are written again, from the secrets file. */
    private static final Pattern RESTART_PATH = Pattern.compile("/api/sessions/[^/]+/restart");

    /** Resuming an archived session: secrets, API key and user profile files, as for a new session. */
    private static final Pattern RESUME_PATH = Pattern.compile("/api/archives/[^/]+/resume");

    /** The requests whose body this app makes from its own files, and what each takes. */
    private enum Filled {
        /** {@code PUT .../sessions/{id}/secrets}: the session's secrets, at least one. */
        REFRESH("/secrets"),
        /** {@code POST .../sessions/{id}/restart}: the session's secrets, any of them. */
        RESTART("/restart"),
        /** {@code POST .../archives/{id}/resume}: the archive's secrets, the API key and the user profile files. */
        RESUME("/resume");

        final String suffix;

        Filled(String suffix) {
            this.suffix = suffix;
        }
    }

    /** Refreshing user profile files, which this app fills in from the user-profile directory. */
    private static final Pattern USER_PROFILE_PATH = Pattern.compile("/api/sessions/[^/]+/userprofile");

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
        Filled filled = request.getMethod().equals("PUT") && SECRETS_PATH.matcher(forwardedPath).matches() ? Filled.REFRESH
                : request.getMethod().equals("POST") && RESTART_PATH.matcher(forwardedPath).matches() ? Filled.RESTART
                : request.getMethod().equals("POST") && RESUME_PATH.matcher(forwardedPath).matches() ? Filled.RESUME
                : null;
        boolean profileRefresh = request.getMethod().equals("PUT") && USER_PROFILE_PATH.matcher(forwardedPath).matches();
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
            } else if (filled != null) {
                Optional<byte[]> made = fillSecrets(filled, server, token, forwardedPath, body, missingSecrets, response);
                if (made.isEmpty()) {
                    return;
                }
                body = made.get();
            } else if (profileRefresh) {
                Optional<byte[]> profile = fillUserProfile(body, response);
                if (profile.isEmpty()) {
                    return;
                }
                body = profile.get();
            }
            publisher = body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
        }
        boolean stream = path.endsWith("/events");
        HttpRequest.Builder forwarded = HttpRequest.newBuilder(target)
            .method(request.getMethod(), publisher)
            .header("Authorization", "Bearer " + token);
        if (!stream) {
            // a restart or a resume starts an agent, and a resume clones the repository again
            forwarded.timeout(upload ? UPLOAD_TIMEOUT : create || filled == Filled.RESTART || filled == Filled.RESUME
                    ? CREATE_TIMEOUT : Duration.ofSeconds(120));
        }
        for (String name : FORWARDED_REQUEST_HEADERS) {
            String value = request.getHeader(name);
            if ((filled != null || profileRefresh) && name.equals("Content-Type")) {
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
     * {@code {"title": ..., "image": ..., "secrets": {...}, "apiKey": ..., "repository": {"url": ..., "branch": ...}}}.
     * Secrets the role lists but the secrets file does not have are left out and added to {@code missingSecrets}
     * (names only). The secrets file's {@code BOB_API_KEY}, when there, becomes {@code apiKey} for every session,
     * with or without a role, and never one of {@code secrets}.
     * A {@code repository} or {@code capabilities} the page sends replaces the role's ({@code "repository": null}
     * and {@code "capabilities": []} meaning none); left out, the role's apply.
     * @return the body to forward, or empty if an error was sent instead
     */
    /** Whether the page's {@code repository} is {@code {"url": ..., "branch": ...}} as a role could have it. */
    private static boolean validRepository(JsonNode repository) {
        if (!(repository instanceof ObjectNode)
                || !repository.propertyNames().stream().allMatch(n -> n.equals("url") || n.equals("branch"))) {
            return false;
        }
        JsonNode url = repository.get("url");
        JsonNode branch = repository.get("branch");
        return url != null && url.isString() && RoleRegistry.REPOSITORY.matcher(url.asString()).matches()
                && (branch == null || branch.isString() && RoleRegistry.BRANCH.matcher(branch.asString()).matches()
                        && !branch.asString().contains(".."));
    }

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
        if (request.has("image") || request.has("env") || request.has("secrets") || request.has("userProfile")
                || request.has("apiKey")) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "The image, secrets, API key and userProfile of a session come from its role or configuration");
            return Optional.empty();
        }
        Map<String, String> available;
        try {
            available = secrets.load();
        } catch (IllegalStateException e) {
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return Optional.empty();
        }
        // the user's own Bob API key, for every session whatever its role: the server puts it where Bob reads it
        // (the agent's environment), in place of its own key
        String apiKey = available.get(SecretStore.API_KEY);
        boolean ownApiKey = apiKey != null && !apiKey.isBlank();
        if (ownApiKey) {
            request.put("apiKey", apiKey);
        }
        // the user may name the repository to clone, or none, instead of the role's; the server checks it again
        boolean repositoryChosen = request.has("repository");
        JsonNode chosenRepository = request.get("repository");
        if (repositoryChosen && chosenRepository.isNull()) {
            request.remove("repository");
        } else if (repositoryChosen && !validRepository(chosenRepository)) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "The repository must be an https URL without credentials, and the branch a plain branch name");
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
            for (String name : role.get().secrets()) {
                String value = available.get(name);
                if (name.equals(SecretStore.API_KEY)) {
                    // never a file: it went as the API key above, if the secrets file has it
                    if (!ownApiKey) {
                        missingSecrets.add(name);
                    }
                } else if (value == null) {
                    missingSecrets.add(name);
                } else {
                    passed.put(name, value);
                }
            }
            if (!passed.isEmpty()) {
                request.set("secrets", passed);
            }
            if (!repositoryChosen && role.get().repository() != null) {
                ObjectNode repository = request.putObject("repository");
                repository.put("url", role.get().repository());
                if (role.get().branch() != null) {
                    repository.put("branch", role.get().branch());
                }
            }
            // the role's capabilities are a default: a list from the page, even an empty one, replaces them
            if (capabilities == null && !role.get().capabilities().isEmpty()) {
                ArrayNode fromRole = request.putArray("capabilities");
                role.get().capabilities().forEach(fromRole::add);
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
            log.info("Creating a session with role {} (image {}, secrets {}, not in the secrets file: {}, API key {}, "
                    + "repository {}, capabilities {}, resources {})",
                    role.get().id(), role.get().image() == null ? "default" : role.get().image(), passed.propertyNames(),
                    missingSecrets, ownApiKey ? "from the secrets file" : "the server's", request.path("repository").path("url").asString("none"),
                    request.path("capabilities"), request.path("resources"));
        } else {
            log.info("Creating a session without role (image default, secrets [], API key {}, repository {}, "
                    + "capabilities {}, resources {})",
                    ownApiKey ? "from the secrets file" : "the server's",
                    request.path("repository").path("url").asString("none"),
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
     * Makes the body of a request that gives a session its secrets again: {@code {"secrets": {...}}} with the values
     * the secrets file has now for the secrets the session (or the archived one) has, whose names the server is
     * asked for. Those the file does not have are left out and added to {@code missingSecrets} (names only). For a
     * resume, the API key and the user profile files are added too, as for a new session. The browser sends no
     * values, as when a session is created.
     * @return the body to forward, or empty if an error was sent instead
     */
    private Optional<byte[]> fillSecrets(Filled kind, ServerConnection server, String token, String forwardedPath,
            byte[] body, List<String> missingSecrets, HttpServletResponse response) throws IOException {
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
        String sessionPath = forwardedPath.substring(0, forwardedPath.length() - kind.suffix.length());
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
        if (names.isEmpty() && kind == Filled.REFRESH) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "This session was created without secrets, so there is nothing to refresh");
            return Optional.empty();
        }
        Map<String, String> available;
        try {
            available = names.isEmpty() && kind == Filled.RESTART ? Map.of() : secrets.load();
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
        if (passed.isEmpty() && kind == Filled.REFRESH) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "None of the session's secrets ("
                    + String.join(", ", names) + ") is in the secrets file, so nothing was refreshed");
            return Optional.empty();
        }
        ObjectNode request = json.createObjectNode();
        request.set("secrets", passed);
        if (kind == Filled.RESUME) {
            String apiKey = available.get(SecretStore.API_KEY);
            if (apiKey != null && !apiKey.isBlank()) {
                request.put("apiKey", apiKey);
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
        }
        log.info("{} a session with the secrets {} (not in the secrets file: {}){}", switch (kind) {
            case REFRESH -> "Refreshing";
            case RESTART -> "Restarting";
            case RESUME -> "Resuming";
        }, passed.propertyNames(), missingSecrets, request.has("apiKey") ? ", its own API key" : "");
        return Optional.of(json.writeValueAsBytes(request));
    }

    /**
     * Makes the body of a request to refresh a session's user profile files: a JSON array of
     * {@code {"path": ..., "contentBase64": ..., "executable": ...}} objects, loaded fresh from the
     * user-profile directory. The browser sends no body.
     * @return the body to forward, or empty if an error was sent instead
     */
    private Optional<byte[]> fillUserProfile(byte[] body, HttpServletResponse response) throws IOException {
        try {
            JsonNode sent = body.length == 0 ? json.createObjectNode() : json.readTree(body);
            if (!(sent instanceof ObjectNode) || !sent.isEmpty()) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "The user profile files come from this UI's user-profile directory; send no body");
                return Optional.empty();
            }
        } catch (JacksonException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "The request is not valid JSON");
            return Optional.empty();
        }
        List<ProfileFile> files;
        try {
            files = userProfile.load();
        } catch (IllegalStateException e) {
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return Optional.empty();
        }
        if (files.isEmpty()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "The user-profile directory is empty or does not exist; nothing to refresh");
            return Optional.empty();
        }
        log.info("Refreshing the user profile of a session ({} file(s))", files.size());
        ArrayNode array = json.createArrayNode();
        for (ProfileFile file : files) {
            ObjectNode node = array.addObject();
            node.put("path", file.path());
            node.put("contentBase64", file.contentBase64());
            node.put("executable", file.executable());
        }
        return Optional.of(json.writeValueAsBytes(array));
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
