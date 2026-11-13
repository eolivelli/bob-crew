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
    void wipeDeletesEverythingInsideButKeepsTheDirectory() throws Exception {
        Path workspace = workspaces.resolve("project");
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve(".hidden"), "h");
        Files.createDirectories(workspace.resolve("src/deep/er"));
        Files.writeString(workspace.resolve("src/deep/er/b.txt"), "b");
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("precious.txt"), "keep me");
        Files.createSymbolicLink(workspace.resolve("link"), outside);
        Path sibling = workspaces.resolve("other");
        Files.writeString(sibling.resolve("c.txt"), "c");

        workspaces.wipe(workspace);

        assertThat(workspace).isDirectory();
        try (var children = Files.list(workspace)) {
            assertThat(children).isEmpty();
        }
        assertThat(outside.resolve("precious.txt")).as("a link is removed, not followed").hasContent("keep me");
        assertThat(sibling.resolve("c.txt")).exists();
    }

    @Test
    void wipeRefusesTheRootAndADirectoryThatWasReplaced() throws Exception {
        Files.writeString(workspaces.resolve("project").resolve("a.txt"), "a");
        assertThatThrownBy(() -> workspaces.wipe(root)).isInstanceOf(ApiException.class)
            .hasMessageContaining("root");
        assertThat(root.resolve("project/a.txt")).exists();

        // the directory is replaced by a link to another workspace
        Path workspace = workspaces.resolve("moved");
        Path other = workspaces.resolve("other");
        Files.writeString(other.resolve("c.txt"), "c");
        Files.delete(workspace);
        Files.createSymbolicLink(workspace, other);
        assertThatThrownBy(() -> workspaces.wipe(workspace)).isInstanceOf(ApiException.class);
        assertThat(other.resolve("c.txt")).exists();
    }

    @Test
    void refusesAFile() throws Exception {
        Files.writeString(root.resolve("file.txt"), "x");
        assertThatThrownBy(() -> workspaces.resolve("file.txt")).isInstanceOf(ApiException.class);
    }
}
