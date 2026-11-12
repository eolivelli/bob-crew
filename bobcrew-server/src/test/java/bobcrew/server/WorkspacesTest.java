package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacesTest {

    @TempDir
    Path temp;

    private Path root;

    private Workspaces workspaces;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temp.resolve("root")).toRealPath();
        workspaces = new Workspaces(new BobCrewProperties(root, 1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ZERO, 10, null, null));
    }

    @Test
    void createsAndResolvesDirectoriesUnderTheRoot() {
        Path nested = workspaces.resolve("team/project");
        assertThat(nested).isEqualTo(root.resolve("team/project")).isDirectory();
        assertThat(workspaces.nameOf(nested)).isEqualTo(Path.of("team", "project").toString());
        assertThat(workspaces.resolve("team/./project/../project")).isEqualTo(nested);
        assertThat(workspaces.list()).containsExactly("team");
    }

    @Test
    void refusesPathsThatLeaveTheRoot() {
        for (String name : new String[] {"..", "../x", "a/../../x", "/etc", root.resolveSibling("x").toString(), "", " "}) {
            assertThatThrownBy(() -> workspaces.resolve(name)).as(name).isInstanceOf(ApiException.class);
        }
        assertThat(temp.resolve("x")).doesNotExist();
    }

    @Test
    void refusesSymbolicLinksThatLeaveTheRoot() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("link"), outside);
        assertThatThrownBy(() -> workspaces.resolve("link")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workspaces.resolve("link/new-dir")).isInstanceOf(ApiException.class);
        assertThat(outside.resolve("new-dir")).doesNotExist();
    }

    @Test
    void refusesAFile() throws Exception {
        Files.writeString(root.resolve("file.txt"), "x");
        assertThatThrownBy(() -> workspaces.resolve("file.txt")).isInstanceOf(ApiException.class);
    }
}
