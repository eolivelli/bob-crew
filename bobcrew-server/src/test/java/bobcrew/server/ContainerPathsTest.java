package bobcrew.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerPathsTest {

    @Test
    void resolvesRelativeAndAbsolutePathsInsideTheWorkspace() {
        assertThat(ContainerPaths.resolve("out/hello.txt")).isEqualTo("/opt/workspace/out/hello.txt");
        assertThat(ContainerPaths.resolve("./out//./hello.txt")).isEqualTo("/opt/workspace/out/hello.txt");
        assertThat(ContainerPaths.resolve("file:///opt/workspace/a/../b.txt")).isEqualTo("/opt/workspace/b.txt");
        assertThat(ContainerPaths.resolve("file://out/x")).isEqualTo("/opt/workspace/out/x");
        assertThat(ContainerPaths.resolve("  /opt/workspace/x  ")).isEqualTo("/opt/workspace/x");
    }

    @Test
    void refusesPathsOutsideTheWorkspace() {
        for (String bad : new String[] {"../../etc/passwd", "/etc/passwd", "/opt/workspace", "/opt/workspace/..",
                "/opt/workspace-other/x", "file:///proc/1/environ", "a/../../x", "/opt/workspace/../workspace2/x", ".",
                "/../../../../"}) {
            assertThatThrownBy(() -> ContainerPaths.resolve(bad)).as(bad).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void refusesEmptyAndMalformedPaths() {
        assertThatThrownBy(() -> ContainerPaths.resolve(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContainerPaths.resolve("  ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContainerPaths.resolve("a\0b")).isInstanceOf(ApiException.class);
    }
}
