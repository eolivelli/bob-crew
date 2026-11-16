package bobcrew.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Docker for the tests. As a JUnit condition ({@code @ExtendWith(TestDocker.class)}) it skips the test class
 * when no Docker daemon answers, before Spring starts (the server refuses to start without one); otherwise it
 * builds the fake agent's image, {@value #IMAGE}, once per test run.
 */
final class TestDocker implements ExecutionCondition {

    static final String IMAGE = "bobcrew-fake-agent:test";

    /** A bare repository inside the image, with branches {@code main} and {@code feature}. */
    static final String SAMPLE_REPOSITORY = "file:///opt/test-repos/sample.git";

    private static final String HOST = System.getenv().getOrDefault("DOCKER_HOST", "unix:///var/run/docker.sock");

    private static DockerClient client;

    private static String unavailable;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String reason = prepare();
        return reason == null ? ConditionEvaluationResult.enabled("Docker is available")
                : ConditionEvaluationResult.disabled(reason);
    }

    /** Returns null when Docker and the image are ready, otherwise why not. */
    private static synchronized String prepare() {
        if (client != null || unavailable != null) {
            return unavailable;
        }
        DockerClient candidate;
        try {
            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(HOST)
                .withApiVersion("1.44")
                .build();
            candidate = DockerClientImpl.getInstance(config, new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(5))
                .build());
            candidate.pingCmd().exec();
        } catch (RuntimeException e) {
            unavailable = "No Docker daemon at " + HOST + " (" + e.getMessage() + "); Docker tests skipped";
            System.err.println(unavailable);
            return unavailable;
        }
        try {
            buildImage(candidate);
        } catch (Exception e) {
            unavailable = "Could not build " + IMAGE + " (" + e + "); Docker tests skipped";
            System.err.println(unavailable);
            return unavailable;
        }
        client = candidate;
        return null;
    }

    private static void buildImage(DockerClient docker) throws IOException, InterruptedException {
        Path module = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path context = Files.createTempDirectory("bobcrew-test-image");
        Files.copy(module.resolve("src/test/docker/fake-agent/Dockerfile"), context.resolve("Dockerfile"));
        Files.copy(module.resolveSibling("docker").resolve("bob-base/bobcrew-entrypoint.sh"),
                context.resolve("bobcrew-entrypoint.sh"));
        Files.copy(fakeAgentJar(), context.resolve("fake-agent.jar"), StandardCopyOption.REPLACE_EXISTING);
        File dir = context.toFile();
        docker.buildImageCmd(dir)
            .withTags(Set.of(IMAGE))
            .exec(new BuildImageResultCallback())
            .awaitImageId(10, TimeUnit.MINUTES);
    }

    /** The fake agent's self-contained jar, from the test classpath. */
    private static Path fakeAgentJar() {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry);
            String name = path.getFileName().toString();
            if (name.startsWith("bobcrew-fake-agent") && name.endsWith("-all.jar")) {
                return path;
            }
        }
        throw new IllegalStateException("bobcrew-fake-agent-*-all.jar is not on the test classpath; "
                + "run mvn install -pl bobcrew-fake-agent");
    }

    /** A client for checking what the server did. Only valid once the condition has enabled the test. */
    static DockerClient client() {
        return client;
    }

    /**
     * The server settings every Docker test uses: the fake agent's image and command, and an instance id of
     * its own, so that the test never touches containers of a real server on the same machine.
     * @return the instance id
     */
    static String register(DynamicPropertyRegistry registry) {
        String instanceId = "test-" + UUID.randomUUID().toString().substring(0, 8);
        registry.add("bobcrew.docker.host", () -> HOST);
        registry.add("bobcrew.docker.instance-id", () -> instanceId);
        registry.add("bobcrew.docker.default-image", () -> IMAGE);
        registry.add("bobcrew.docker.memory-limit", () -> "512MB");
        registry.add("bobcrew.docker.stop-timeout", () -> "1s");
        registry.add("bobcrew.agent.command[0]", () -> "java");
        registry.add("bobcrew.agent.command[1]", () -> "-jar");
        registry.add("bobcrew.agent.command[2]", () -> "/opt/fake-agent.jar");
        // the image's own repository, /opt/test-repos/sample.git, is reached with file://
        registry.add("bobcrew.git.allowed-schemes", () -> "https,file");
        return instanceId;
    }
}
