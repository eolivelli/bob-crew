package bobcrew.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A real Keycloak for tests, in Docker, with the project's realm ({@code keycloak/bobcrew-realm.json}) imported
 * as it ships. One container serves the whole test JVM; it is removed when the JVM exits (and, if a run was
 * killed, by the next run, once it is two hours old).
 *
 * <p>Tests get tokens without a browser through a client of their own, {@value #TEST_CLIENT}, which has the
 * password grant and the same protocol mappers as {@code bobcrew-ui}. The shipped realm stays without it.
 * The image is pulled if it is missing: with the fake agent's base image, the only network the tests use.
 */
public final class TestKeycloak {

    /** Pinned: the realm file is checked against this version. */
    public static final String IMAGE = "quay.io/keycloak/keycloak:26.4.7";

    public static final String REALM = "bobcrew";

    public static final String TEST_CLIENT = "bobcrew-test";

    /** Like {@link #TEST_CLIENT} but without the mappers: its tokens are not for {@code bobcrew-api}. */
    public static final String OTHER_CLIENT = "bobcrew-test-other";

    /** Every test user's password. */
    public static final String PASSWORD = "test-password-1234";

    private static final String LABEL = "bobcrew.test.keycloak";

    private static final String ADMIN_PASSWORD = "test-admin-password";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static TestKeycloak instance;

    private static String unavailable;

    private final DockerClient docker;

    private final String containerId;

    private final String baseUrl;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final Map<String, Boolean> users = new ConcurrentHashMap<>();

    private TestKeycloak(DockerClient docker, String containerId, String baseUrl) {
        this.docker = docker;
        this.containerId = containerId;
        this.baseUrl = baseUrl;
    }

    /**
     * The running Keycloak, started on first use.
     * @throws IllegalStateException if it cannot start (no Docker, no image); {@link KeycloakAvailable} turns that
     * into a skipped test
     */
    public static synchronized TestKeycloak get() {
        if (instance != null) {
            return instance;
        }
        if (unavailable != null) {
            throw new IllegalStateException(unavailable);
        }
        try {
            instance = start();
            return instance;
        } catch (Exception e) {
            unavailable = "Could not start Keycloak (" + e + ")";
            throw new IllegalStateException(unavailable, e);
        }
    }

    /** Why Keycloak is not available, or null if it is (starting it if needed). */
    static synchronized String unavailableReason() {
        try {
            get();
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    /** The realm's issuer, as tokens carry it and as servers and UIs must be configured with it. */
    public String issuer() {
        return baseUrl + "/realms/" + REALM;
    }

    /** Keycloak's own address, for a test that goes through its pages. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Creates the user if it does not exist yet, with password {@link #PASSWORD}, these realm roles and these
     * attributes. A user is created once per run: later calls with the same name do nothing, so tests that share
     * a name must agree on what it has.
     */
    public synchronized void user(String name, List<String> roles, Map<String, String> attributes) {
        if (users.containsKey(name)) {
            return;
        }
        try {
            ObjectNode user = JSON.createObjectNode()
                .put("username", name)
                .put("enabled", true)
                .put("email", name + "@example.com")
                .put("emailVerified", true)
                .put("firstName", name)
                .put("lastName", "Test");
            ObjectNode attrs = user.putObject("attributes");
            attributes.forEach((key, value) -> attrs.putArray(key).add(value));
            user.putArray("credentials").addObject().put("type", "password").put("value", PASSWORD)
                .put("temporary", false);
            HttpResponse<String> created = admin("POST", "/users", user.toString());
            check(created, 201, "creating user " + name);
            String location = created.headers().firstValue("Location").orElseThrow();
            String id = location.substring(location.lastIndexOf('/') + 1);
            if (!roles.isEmpty()) {
                ArrayNode mappings = JSON.createArrayNode();
                for (String role : roles) {
                    HttpResponse<String> found = admin("GET", "/roles/" + role, null);
                    check(found, 200, "reading role " + role);
                    mappings.add(JSON.readTree(found.body()));
                }
                check(admin("POST", "/users/" + id + "/role-mappings/realm", mappings.toString()), 204,
                        "giving " + name + " roles " + roles);
            }
            users.put(name, true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A user with {@code bobcrew-user} and nothing else. */
    public void user(String name) {
        user(name, List.of("bobcrew-user"), Map.of());
    }

    /** A fresh access token for the user (created with {@link #user} first), as {@code bobcrew-ui} would get it. */
    public String token(String name) {
        return tokens(name).get("access_token").asString();
    }

    /** The whole token response: access, refresh and id tokens. */
    public JsonNode tokens(String name) {
        return tokens(TEST_CLIENT, name);
    }

    /** A token for another client of the same realm, {@link #OTHER_CLIENT}: signed by the realm, but not for us. */
    public String tokenForAnotherClient(String name) {
        return tokens(OTHER_CLIENT, name).get("access_token").asString();
    }

    private JsonNode tokens(String client, String name) {
        return post(issuer() + "/protocol/openid-connect/token", form(Map.of("grant_type", "password", "client_id",
                client, "username", name, "password", PASSWORD, "scope", "openid")));
    }

    /** A token Keycloak signed for another realm ({@code master}): a different issuer. */
    public String otherRealmToken() {
        return adminToken();
    }

    // ---- starting ----------------------------------------------------------

    private static TestKeycloak start() throws Exception {
        String host = System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(host)
            .withApiVersion("1.44")
            .build();
        DockerClient docker = DockerClientImpl.getInstance(config, new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .connectionTimeout(Duration.ofSeconds(5))
            .build());
        docker.pingCmd().exec();
        removeLeftovers(docker);
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (NotFoundException e) {
            System.err.println("Pulling " + IMAGE + " for the Keycloak tests");
            docker.pullImageCmd(IMAGE).exec(new PullImageResultCallback()).awaitCompletion(10, TimeUnit.MINUTES);
        }

        ExposedPort http = ExposedPort.tcp(8080);
        Ports ports = new Ports();
        ports.bind(http, Ports.Binding.bindIp("127.0.0.1"));
        String id = docker.createContainerCmd(IMAGE)
            .withName("bobcrew-test-keycloak-" + UUID.randomUUID().toString().substring(0, 8))
            .withLabels(Map.of(LABEL, String.valueOf(Instant.now().getEpochSecond())))
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME=admin", "KC_BOOTSTRAP_ADMIN_PASSWORD=" + ADMIN_PASSWORD)
            .withCmd("start-dev", "--import-realm")
            .withExposedPorts(http)
            .withHostConfig(HostConfig.newHostConfig().withPortBindings(ports))
            .exec()
            .getId();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                docker.removeContainerCmd(id).withForce(true).exec();
            } catch (RuntimeException e) {
                // already gone
            }
        }));
        docker.copyArchiveToContainerCmd(id)
            .withRemotePath("/opt/keycloak/data")
            .withTarInputStream(new ByteArrayInputStream(realmArchive()))
            .exec();
        docker.startContainerCmd(id).exec();

        InspectContainerResponse inspected = docker.inspectContainerCmd(id).exec();
        Ports.Binding[] bound = inspected.getNetworkSettings().getPorts().getBindings().get(http);
        String baseUrl = "http://127.0.0.1:" + bound[0].getHostPortSpec();
        TestKeycloak keycloak = new TestKeycloak(docker, id, baseUrl);
        keycloak.awaitReady();
        keycloak.addTestClient();
        return keycloak;
    }

    /** {@code import/bobcrew-realm.json}, readable by Keycloak's user, as a tar archive for Docker. */
    private static byte[] realmArchive() throws IOException {
        Path realm = realmFile();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry dir = new TarArchiveEntry("import/");
            dir.setMode(0755);
            tar.putArchiveEntry(dir);
            tar.closeArchiveEntry();
            byte[] content = Files.readAllBytes(realm);
            TarArchiveEntry file = new TarArchiveEntry("import/" + REALM + "-realm.json");
            file.setMode(0644);
            file.setSize(content.length);
            tar.putArchiveEntry(file);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    /** The realm file at the top of the project; tests run in a module's directory. */
    public static Path realmFile() {
        Path module = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        for (Path dir = module; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("keycloak").resolve(REALM + "-realm.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("keycloak/" + REALM + "-realm.json not found above " + module);
    }

    private static void removeLeftovers(DockerClient docker) {
        long cutoff = Instant.now().minus(Duration.ofHours(2)).getEpochSecond();
        for (Container container : docker.listContainersCmd().withShowAll(true).withLabelFilter(List.of(LABEL)).exec()) {
            String started = container.getLabels().get(LABEL);
            try {
                if (started != null && Long.parseLong(started) < cutoff) {
                    docker.removeContainerCmd(container.getId()).withForce(true).exec();
                }
            } catch (RuntimeException e) {
                // another run's, or already gone
            }
        }
    }

    private void awaitReady() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(issuer()
                        + "/.well-known/openid-configuration")).build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            }
            if (!Boolean.TRUE.equals(docker.inspectContainerCmd(containerId).exec().getState().getRunning())) {
                throw new IllegalStateException("Keycloak exited during startup");
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Keycloak did not become ready within 3 minutes");
    }

    /** A public client with the password grant and {@code bobcrew-ui}'s mappers, so that tokens look the same. */
    private void addTestClient() throws IOException, InterruptedException {
        JsonNode ui = null;
        for (JsonNode client : JSON.readTree(Files.readString(realmFile())).get("clients")) {
            if (client.get("clientId").asString().equals("bobcrew-ui")) {
                ui = client;
            }
        }
        if (ui == null) {
            throw new IllegalStateException("The realm has no bobcrew-ui client");
        }
        ObjectNode client = JSON.createObjectNode()
            .put("clientId", TEST_CLIENT)
            .put("publicClient", true)
            .put("standardFlowEnabled", false)
            .put("directAccessGrantsEnabled", true);
        client.set("protocolMappers", ui.get("protocolMappers"));
        check(admin("POST", "/clients", client.toString()), 201, "creating the test client");
        ObjectNode other = JSON.createObjectNode()
            .put("clientId", OTHER_CLIENT)
            .put("publicClient", true)
            .put("standardFlowEnabled", false)
            .put("directAccessGrantsEnabled", true);
        check(admin("POST", "/clients", other.toString()), 201, "creating the other test client");
    }

    // ---- HTTP --------------------------------------------------------------

    private String adminToken() {
        return post(baseUrl + "/realms/master/protocol/openid-connect/token", form(Map.of("grant_type", "password",
                "client_id", "admin-cli", "username", "admin", "password", ADMIN_PASSWORD)))
            .get("access_token").asString();
    }

    private HttpResponse<String> admin(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/admin/realms/" + REALM + path))
            .header("Authorization", "Bearer " + adminToken())
            .timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String url, String form) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
            check(response, 200, "POST " + url);
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** {@code application/x-www-form-urlencoded}. */
    public static String form(Map<String, String> fields) {
        return new LinkedHashMap<>(fields).entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }

    private static void check(HttpResponse<String> response, int expected, String what) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(what + ": HTTP " + response.statusCode() + " " + response.body());
        }
    }

    /** For the realm check: the names of the realm's roles as the file defines them. */
    public static Set<String> realmRoles() throws IOException {
        List<String> names = new ArrayList<>();
        for (JsonNode role : JSON.readTree(Files.readString(realmFile())).get("roles").get("realm")) {
            names.add(role.get("name").asString());
        }
        return Set.copyOf(names);
    }
}
