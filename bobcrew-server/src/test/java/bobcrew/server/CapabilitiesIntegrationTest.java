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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sessions with capabilities, on a server that allows only docker: the container is privileged and starts as
 * root, the image's root.d hooks run as root and learn the capabilities, and the agent still runs as the
 * configured non-root user. Ordinary sessions on the same server keep every restriction. Skipped without Docker.
 */
@ExtendWith(TestDocker.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapabilitiesIntegrationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-capabilities-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path directory;

    private static final TestTls tls;

    static {
        try {
            directory = Files.createTempDirectory("bobcrew-cap-test");
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
        registry.add("bobcrew.allowed-capabilities", () -> "docker");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().sslContext(tls.clientContext).build();

    private final String alice = Tokens.issue(SECRET.getBytes(StandardCharsets.UTF_8), "alice", Duration.ofHours(1),
            Tokens.DEFAULT_ISSUER, Tokens.DEFAULT_AUDIENCE);

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        for (String id : created) {
            send("DELETE", "/sessions/" + id, null);
        }
    }

    @Test
    void tellsClientsWhichCapabilitiesAreAllowed() throws Exception {
        JsonNode me = JSON.readTree(send("GET", "/me", null).body());
        assertThat(me.get("capabilities").toString()).isEqualTo("[\"docker\"]");
        assertThat(me.get("maxMemoryBytes").isNull()).isTrue();
    }

    @Test
    void refusesACapabilityTheServerDoesNotAllow() throws Exception {
        HttpResponse<String> response = send("POST", "/sessions", "{\"capabilities\":[\"docker\",\"libvirt\"]}");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("may not use the libvirt capability");
    }

    @Test
    void runsRootHooksThenTheAgentAsTheSessionUser() throws Exception {
        JsonNode session = create(Map.of("capabilities", List.of("docker", "docker"),
                "resources", Map.of("memory", "768MB")));
        assertThat(session.get("capabilities").toString()).isEqualTo("[\"docker\"]");
        String container = session.get("containerId").asString();

        InspectContainerResponse inspected = TestDocker.client().inspectContainerCmd(container).exec();
        HostConfig host = inspected.getHostConfig();
        assertThat(host.getPrivileged()).isTrue();
        assertThat(host.getMemory()).isEqualTo(768L * 1024 * 1024);
        assertThat(host.getBinds()).isNullOrEmpty();
        assertThat(inspected.getConfig().getUser()).isEqualTo("0:0");
        assertThat(inspected.getConfig().getEnv()).contains("BOBCREW_AGENT_USER=1000:1000");
        assertThat(inspected.getConfig().getEnv()).contains("BOBCREW_CAPABILITIES=docker");
        assertThat(inspected.getConfig().getLabels()).containsEntry(DockerRuntime.LABEL_CAPABILITIES, "docker");

        assertThat(exec(container, "0:0", "cat", "/run/bobcrew-root-hook").strip())
            .as("the root.d hook ran as root, knowing the capabilities").isEqualTo("0 docker");
        String agentUid = exec(container, "0:0", "sh", "-c",
                "for p in /proc/[0-9]*; do tr '\\0' ' ' < $p/cmdline 2>/dev/null | grep -q '^java .*fake-agent.jar'"
                        + " && grep Uid: $p/status; done");
        assertThat(agentUid).as("the agent's process").isEqualTo("Uid:\t1000\t1000\t1000\t1000\n");
    }

    @Test
    void wipingAPrivilegedWorkspaceRemovesFilesOwnedByRoot() throws Exception {
        JsonNode session = create(Map.of("capabilities", List.of("docker")));
        String id = session.get("id").asString();
        String container = session.get("containerId").asString();
        exec(container, "0:0", "sh", "-c", "mkdir -p /opt/workspace/build && touch /opt/workspace/build/out.o");

        HttpResponse<String> cleared = send("POST", "/sessions/" + id + "/clear", "{\"wipeWorkspace\":true}");
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(exec(container, "0:0", "ls", "-A", "/opt/workspace")).isEmpty();
    }

    @Test
    void ordinarySessionsKeepTheirRestrictions() throws Exception {
        JsonNode session = create(Map.of());
        assertThat(session.get("capabilities")).isEmpty();
        InspectContainerResponse inspected = TestDocker.client()
            .inspectContainerCmd(session.get("containerId").asString()).exec();
        assertThat(inspected.getHostConfig().getPrivileged()).isFalse();
        assertThat(inspected.getHostConfig().getCapDrop()).contains(Capability.ALL);
        assertThat(inspected.getHostConfig().getSecurityOpts()).contains("no-new-privileges");
        assertThat(inspected.getConfig().getUser()).isEqualTo("1000:1000");
        assertThat(String.join(" ", inspected.getConfig().getEnv())).doesNotContain("BOBCREW_AGENT_USER");
    }

    @Test
    void theEntrypointNeverRunsTheAgentAsRoot() throws Exception {
        // started as root without BOBCREW_AGENT_USER, as a misconfigured runtime would: it refuses
        String id = TestDocker.client().createContainerCmd(TestDocker.IMAGE)
            .withUser("0:0")
            .withCmd("id")
            .withLabels(Map.of(DockerRuntime.LABEL_MANAGED, "true", DockerRuntime.LABEL_INSTANCE, "test-entrypoint"))
            .exec().getId();
        try {
            TestDocker.client().startContainerCmd(id).exec();
            int exit = TestDocker.client().waitContainerCmd(id).start().awaitStatusCode(30, TimeUnit.SECONDS);
            assertThat(exit).isEqualTo(77);
        } finally {
            TestDocker.client().removeContainerCmd(id).withForce(true).exec();
        }
    }

    // ---- helpers -----------------------------------------------------------

    private JsonNode create(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = send("POST", "/sessions", JSON.writeValueAsString(body));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(response.body());
        created.add(session.get("id").asString());
        return session;
    }

    /** What a command run in the container as {@code user} prints on its standard output. */
    private static String exec(String container, String user, String... command) throws Exception {
        String exec = TestDocker.client().execCreateCmd(container).withUser(user).withCmd(command)
            .withAttachStdout(true).withAttachStderr(true).exec().getId();
        StringBuilder output = new StringBuilder();
        TestDocker.client().execStartCmd(exec).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                    synchronized (output) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }
            }
        }).awaitCompletion(30, TimeUnit.SECONDS);
        synchronized (output) {
            return output.toString();
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api" + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + alice);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
