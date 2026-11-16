package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConnectionTest {

    @TempDir
    Path directory;

    @Test
    void noticesATokenFileOthersCanRead() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path token = Files.writeString(directory.resolve("token"), "abc");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        assertThat(ServerConnection.readableByOthers(token)).isFalse();
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r-----"));
        assertThat(ServerConnection.readableByOthers(token)).isTrue();
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw----r--"));
        assertThat(ServerConnection.readableByOthers(token)).isTrue();
        assertThat(ServerConnection.readableByOthers(directory.resolve("missing"))).isFalse();
    }
}
