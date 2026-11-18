package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs bob-base's {@code profile.d/30-bob-settings.sh}, as the entrypoint does (a POSIX shell with {@code set -eu}),
 * to check that sessions get Bob Shell's turn limit switched off unless the user's own settings say otherwise. Needs
 * {@code jq}, as the image has; skipped without it.
 */
class BobSettingsSnippetTest {

    private static final Path SNIPPET = Path.of(System.getProperty("basedir", ".")).toAbsolutePath()
        .resolveSibling("docker/bob-base/profile.d/30-bob-settings.sh");

    @TempDir
    Path home;

    @BeforeEach
    void needsJq() throws Exception {
        assumeTrue(new ProcessBuilder("jq", "--version").redirectErrorStream(true).start().waitFor() == 0, "jq");
    }

    private Path settings() {
        return home.resolve(".bob/settings/settings.json");
    }

    private JsonNode read() throws Exception {
        return JsonMapper.builder().build().readTree(Files.readString(settings()));
    }

    private ProcessBuilder shell(String script) {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script, "sh", SNIPPET.toString());
        builder.environment().put("HOME", home.toString());
        builder.redirectErrorStream(true);
        return builder;
    }

    private String run() throws Exception {
        Process process = shell("set -eu; . \"$1\"; echo ok").start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }

    @Test
    void withoutASettingsFileTheTurnLimitIsOff() throws Exception {
        assertThat(run()).isEqualTo("ok\n");
        assertThat(read().path("session").path("maxTurns").asInt(-1)).isZero();
    }

    @Test
    void otherSettingsOfTheUserAreKept() throws Exception {
        Files.createDirectories(settings().getParent());
        Files.writeString(settings(), "{\"logging\":{\"logLevel\":\"debug\"},\"session\":{\"defaultMode\":\"plan\"}}");
        run();
        JsonNode json = read();
        assertThat(json.path("session").path("maxTurns").asInt(-1)).isZero();
        assertThat(json.path("session").path("defaultMode").asString()).isEqualTo("plan");
        assertThat(json.path("logging").path("logLevel").asString()).isEqualTo("debug");
    }

    @Test
    void aLimitTheUserSetIsKept() throws Exception {
        Files.createDirectories(settings().getParent());
        Files.writeString(settings(), "{\"session\":{\"maxTurns\":500}}");
        run();
        assertThat(read().path("session").path("maxTurns").asInt()).isEqualTo(500);
    }

    @Test
    void aBrokenSettingsFileIsLeftAloneAndDoesNotStopTheSession() throws Exception {
        Files.createDirectories(settings().getParent());
        Files.writeString(settings(), "{not json");
        assertThat(run()).isEqualTo("ok\n");
        assertThat(Files.readString(settings())).isEqualTo("{not json");
        try (var files = Files.list(settings().getParent())) {
            assertThat(files.count()).isEqualTo(1);
        }
    }
}
