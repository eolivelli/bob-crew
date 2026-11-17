package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import bobcrew.testsupport.KeycloakAvailable;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import bobcrew.testsupport.TestKeycloak;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server with a real Keycloak (the project's realm, {@code keycloak/bobcrew-realm.json}) as identity provider,
 * next to tokens made with {@code issue-token}: who may use the server, who is an admin, who may ask for which
 * capability, and each user's limits, all from Keycloak's roles and attributes.
 */
@ExtendWith({TestDocker.class, KeycloakAvailable.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcIntegrationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final long MB = 1L << 20;

    private static final Path directory;

    private static final TestTls tls;

    static {
        try {
            directory = Files.createTempDirectory("bobcrew-oidc-test");
            tls = TestTls.generate(directory);
            Files.writeString(directory.resolve("jwt.secret"), SECRET + "\n");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDocker.register(registry);
        registry.add("bobcrew.tls.certificate", () -> "file:" + tls.certificatePem);
        registry.add("bobcrew.tls.private-key", () -> "file:" + tls.privateKeyPem);
        registry.add("bobcrew.auth.jwt-secret-file", () -> directory.resolve("jwt.secret").toString());
        registry.add("bobcrew.auth.oidc.issuer-uri", () -> TestKeycloak.get().issuer());
        registry.add("bobcrew.allowed-capabilities", () -> "docker");
        registry.add("bobcrew.max-sessions", () -> "6");
        registry.add("bobcrew.max-sessions-per-user", () -> "2");
        registry.add("bobcrew.docker.memory-limit", () -> "256MB");
        registry.add("bobcrew.docker.max-memory", () -> "512MB");
        registry.add("bobcrew.docker.max-cpus", () -> "1");
        registry.add("bobcrew.limits.ceiling-memory", () -> "768MB");
        registry.add("bobcrew.limits.ceiling-cpus", () -> "2");
    }

    @BeforeAll
    static void users() {
        TestKeycloak keycloak = TestKeycloak.get();
        keycloak.user("alice");
        keycloak.user("carol");
        keycloak.user("boss", List.of("bobcrew-user", "bobcrew-admin"), Map.of());
        keycloak.user("dave", List.of("bobcrew-user", "bobcrew-cap-docker"), Map.of(
                "bobcrew_max_sessions", "1", "bobcrew_max_memory", "1GB", "bobcrew_max_cpus", "1.5"));
        keycloak.user("mallory", List.of(), Map.of());
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().sslContext(tls.clientContext).build();

    private final TestKeycloak keycloak = TestKeycloak.get();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        String admin = keycloak.token("boss");
        for (String id : created) {
            send("DELETE", "/sessions/" + id, null, admin);
        }
    }

    @Test
    void aKeycloakUserIsKnownByName() throws Exception {
        HttpResponse<String> response = send("GET", "/me", null, keycloak.token("alice"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode me = JSON.readTree(response.body());
        assertThat(me.get("user").asString()).isEqualTo("alice");
        assertThat(me.get("issuer").asString()).isEqualTo("oidc");
        assertThat(me.get("admin").asBoolean()).isFalse();
        assertThat(me.get("capabilities")).isEmpty();
        assertThat(me.get("maxSessions").asInt()).isEqualTo(2);
        assertThat(me.get("maxMemoryBytes").asLong()).isEqualTo(512 * MB);
        assertThat(me.get("maxCpus").asDouble()).isEqualTo(1.0);

        JsonNode session = create(keycloak.token("alice"), Map.of("title", "mine"));
        assertThat(session.get("owner").asString()).isEqualTo("alice");
    }

    @Test
    void aUserWithoutTheAccessRoleIsRefused() throws Exception {
        String mallory = keycloak.token("mallory");
        for (String path : List.of("/me", "/sessions")) {
            HttpResponse<String> response = send("GET", path, null, mallory);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("not allowed to use bob-crew");
        }
        assertThat(send("POST", "/sessions", "{}", mallory).statusCode()).isEqualTo(403);
    }

    @Test
    void tokensNotForThisServerAreRefused() throws Exception {
        assertThat(send("GET", "/me", null, keycloak.otherRealmToken()).statusCode())
            .as("another realm: another issuer").isEqualTo(401);
        assertThat(send("GET", "/me", null, keycloak.tokenForAnotherClient("alice")).statusCode())
            .as("another client: not our audience").isEqualTo(401);

        String token = keycloak.token("alice");
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            .replace("\"alice\"", "\"boss\"");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThat(send("GET", "/me", null, forged).statusCode()).as("altered").isEqualTo(401);

        // an HS256 token naming Keycloak's issuer, signed with the issue-token secret: the issuer picks the
        // check, so the secret is no use here
        assertThat(send("GET", "/me", null, hs256(payload)).statusCode()).as("algorithm confusion").isEqualTo(401);
    }

    private static String hs256(String payload) throws Exception {
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        jws.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }

    @Test
    void theAdminRoleSeesAndClosesEveryonesSessions() throws Exception {
        String id = create(keycloak.token("alice"), Map.of("title", "alice's")).get("id").asString();
        assertThat(send("GET", "/sessions/" + id, null, keycloak.token("carol")).statusCode()).isEqualTo(404);

        String boss = keycloak.token("boss");
        JsonNode me = JSON.readTree(send("GET", "/me", null, boss).body());
        assertThat(me.get("admin").asBoolean()).isTrue();
        assertThat(send("GET", "/sessions", null, boss).body()).contains(id);
        assertThat(send("DELETE", "/sessions/" + id, null, boss).statusCode()).isEqualTo(204);
    }

    @Test
    void capabilitiesNeedTheirRole() throws Exception {
        HttpResponse<String> refused = send("POST", "/sessions", "{\"capabilities\":[\"docker\"]}",
                keycloak.token("alice"));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("may not use the docker capability");

        String dave = keycloak.token("dave");
        assertThat(JSON.readTree(send("GET", "/me", null, dave).body()).get("capabilities").get(0).asString())
            .isEqualTo("docker");
        JsonNode session = create(dave, Map.of("capabilities", List.of("docker")));
        assertThat(session.get("capabilities").get(0).asString()).isEqualTo("docker");
    }

    @Test
    void aUsersOwnLimitsComeFromKeycloakUpToTheCeilings() throws Exception {
        String dave = keycloak.token("dave");
        JsonNode me = JSON.readTree(send("GET", "/me", null, dave).body());
        assertThat(me.get("maxSessions").asInt()).isEqualTo(1);
        assertThat(me.get("maxMemoryBytes").asLong()).as("1GB, lowered to the ceiling").isEqualTo(768 * MB);
        assertThat(me.get("maxCpus").asDouble()).as("more than the server's own cap of 1").isEqualTo(1.5);

        HttpResponse<String> tooMuch = send("POST", "/sessions", "{\"resources\":{\"memory\":\"1GB\"}}", dave);
        assertThat(tooMuch.statusCode()).isEqualTo(400);
        assertThat(tooMuch.body()).contains("at most 768MB");

        JsonNode session = create(dave, Map.of("resources", Map.of("memory", "768MB", "cpus", 1.5)));
        assertThat(session.get("id").asString()).isNotBlank();
        HttpResponse<String> second = send("POST", "/sessions", "{}", dave);
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.body()).contains("You already have 1 open sessions");
    }

    @Test
    void anIssueTokenTokenForTheSameNameIsTheSameUser() throws Exception {
        String id = create(keycloak.token("alice"), Map.of("title", "shared name")).get("id").asString();
        String legacy = Tokens.issue(SECRET.getBytes(StandardCharsets.UTF_8), "alice", Duration.ofHours(1),
                Tokens.DEFAULT_ISSUER, Tokens.DEFAULT_AUDIENCE);
        HttpResponse<String> response = send("GET", "/sessions/" + id, null, legacy);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(send("GET", "/me", null, legacy).body()).get("issuer").asString())
            .isEqualTo("token");
    }

    // ---- helpers -----------------------------------------------------------

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = send("POST", "/sessions", JSON.writeValueAsString(body), token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(response.body());
        created.add(session.get("id").asString());
        return session;
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api" + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
