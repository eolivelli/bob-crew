package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreTest {

    @TempDir
    Path directory;

    private SecretStore store(String content) throws Exception {
        Path file = Files.writeString(directory.resolve("secrets.json"), content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return new SecretStore(file);
    }

    @Test
    void noFileMeansNoSecrets() {
        assertThat(new SecretStore(directory.resolve("missing.json")).load()).isEmpty();
    }

    @Test
    void readsNamesAndStringValues() throws Exception {
        assertThat(store("{\"GH_TOKEN\": \"ghp_x\", \"GIT_USER_NAME\": \"Ada Lovelace\", \"EMPTY\": \"\"}").load())
            .containsExactly(java.util.Map.entry("GH_TOKEN", "ghp_x"), java.util.Map.entry("GIT_USER_NAME", "Ada Lovelace"),
                    java.util.Map.entry("EMPTY", ""));
    }

    @Test
    void refusesAFileOthersCanRead() throws Exception {
        SecretStore store = store("{\"GH_TOKEN\": \"ghp_x\"}");
        Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(store::load).hasMessageContaining("chmod 600");
    }

    @Test
    void refusesMalformedFilesWithoutQuotingThem() throws Exception {
        assertThatThrownBy(() -> store("{\"GH_TOKEN\": \"ghp_secret\"").load()).hasMessageContaining("not valid JSON")
            .hasMessageNotContaining("ghp_secret");
        assertThatThrownBy(() -> store("[\"ghp_secret\"]").load()).hasMessageContaining("one JSON object")
            .hasMessageNotContaining("ghp_secret");
        assertThatThrownBy(() -> store("{\"ghp_secret value\": \"x\"}").load()).hasMessageContaining("not valid")
            .hasMessageNotContaining("ghp_secret");
        assertThatThrownBy(() -> store("{\"GH_TOKEN\": 42}").load()).hasMessageContaining("GH_TOKEN must be a string");
        assertThatThrownBy(() -> new SecretStore(Files.createDirectory(directory.resolve("dir"))).load())
            .hasMessageContaining("not a regular file");
    }
}
