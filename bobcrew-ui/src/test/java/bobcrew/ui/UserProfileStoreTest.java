package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserProfileStoreTest {

    @Test
    void returnsEmptyWhenDirectoryDoesNotExist(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("missing-profile");
        UserProfileStore store = new UserProfileStore(missing);
        assertThat(store.load()).isEmpty();
    }

    @Test
    void throwsWhenProfilePathIsNotADirectory(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("not-a-dir");
        Files.writeString(file, "hello");
        UserProfileStore store = new UserProfileStore(file);
        assertThatThrownBy(store::load)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not a directory");
    }

    @Test
    void throwsWhenProfileDirectoryIsReadableByOthers(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir);
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            return;
        }

        UserProfileStore store = new UserProfileStore(profileDir);
        assertThatThrownBy(store::load)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("restrict it with chmod 700");
    }

    @Test
    void loadsFilesRecursively(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir.resolve(".m2"));
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
        Files.writeString(profileDir.resolve(".m2/settings.xml"), "<settings><id>test</id></settings>");
        Files.writeString(profileDir.resolve(".gitconfig"), "[user]\nname=Bob\n");

        UserProfileStore store = new UserProfileStore(profileDir);
        List<ProfileFile> files = store.load();

        assertThat(files).hasSize(2);
        assertThat(files).extracting(ProfileFile::path)
            .containsExactly(".gitconfig", ".m2/settings.xml");

        ProfileFile settings = files.stream().filter(f -> f.path().equals(".m2/settings.xml")).findFirst().orElseThrow();
        byte[] decoded = Base64.getDecoder().decode(settings.contentBase64());
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("<settings><id>test</id></settings>");
        assertThat(settings.executable()).isFalse();
    }

    @Test
    void ignoresSymlinksAndDirectorySymlinks(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir);
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }

        Path outsideDir = tempDir.resolve("outside-dir");
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("secret.txt"), "secret");

        Path outsideFile = tempDir.resolve("outside.txt");
        Files.writeString(outsideFile, "outside content");
        Files.writeString(profileDir.resolve("valid.txt"), "valid content");

        boolean symlinksCreated = true;
        try {
            Files.createSymbolicLink(profileDir.resolve("symlink-file.txt"), outsideFile);
            Files.createSymbolicLink(profileDir.resolve("symlink-dir"), outsideDir);
        } catch (FileSystemException | UnsupportedOperationException | SecurityException e) {
            symlinksCreated = false;
        }

        assumeTrue(symlinksCreated);

        UserProfileStore store = new UserProfileStore(profileDir);
        List<ProfileFile> files = store.load();

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("valid.txt");
    }

    @Test
    void detectsExecutableBit(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir.resolve("bin"));
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
        Path script = profileDir.resolve("bin/run.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");

        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            return;
        }

        UserProfileStore store = new UserProfileStore(profileDir);
        List<ProfileFile> files = store.load();

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("bin/run.sh");
        assertThat(files.get(0).executable()).isTrue();
    }

    @Test
    void throwsWhenTooManyFiles(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir);
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
        for (int i = 0; i < 65; i++) {
            Files.writeString(profileDir.resolve("file" + i + ".txt"), "x");
        }

        UserProfileStore store = new UserProfileStore(profileDir);
        assertThatThrownBy(store::load)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Too many files");
    }

    @Test
    void throwsWhenFileTooLarge(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir);
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
        byte[] big = new byte[256 * 1024 + 1];
        Files.write(profileDir.resolve("big.bin"), big);

        UserProfileStore store = new UserProfileStore(profileDir);
        assertThatThrownBy(store::load)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is larger than 256 KiB");
    }

    @Test
    void throwsWhenTotalSizeTooLarge(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir);
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
        byte[] chunk = new byte[200 * 1024];
        for (int i = 0; i < 6; i++) { // 6 * 200 KB = 1200 KB > 1024 KB
            Files.write(profileDir.resolve("f" + i + ".bin"), chunk);
        }

        UserProfileStore store = new UserProfileStore(profileDir);
        assertThatThrownBy(store::load)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exceeds 1 MB");
    }

    @Test
    void throwsWhenFileOrSubdirIsUnreadable(@TempDir Path tempDir) throws IOException {
        Path profileDir = tempDir.resolve("user-profile");
        Files.createDirectories(profileDir.resolve("unreadable-dir"));
        try {
            Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
        Path unreadableFile = profileDir.resolve("unreadable-dir/unreadable.txt");
        Files.writeString(unreadableFile, "test");

        try {
            Files.setPosixFilePermissions(profileDir.resolve("unreadable-dir"), PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            return;
        }

        UserProfileStore store = new UserProfileStore(profileDir);
        try {
            assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read user-profile");
        } finally {
            // Restore permission so JUnit cleanup can delete tempDir
            try {
                Files.setPosixFilePermissions(profileDir.resolve("unreadable-dir"), PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {
            }
        }
    }
}
