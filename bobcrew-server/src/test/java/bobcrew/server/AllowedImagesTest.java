package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class AllowedImagesTest {

    private final AllowedImages images = new AllowedImages(List.of("bobcrew/*", " ", "registry.example.com/team/tool:1.2"),
            "local/default:latest");

    @Test
    void allowsTheDefaultImageAndMatchingNames() {
        for (String image : List.of("local/default:latest", "bobcrew/bob-base:latest", "bobcrew/bob-java",
                "bobcrew/bob-base@sha256:0123abcd", "registry.example.com/team/tool:1.2")) {
            assertThatCode(() -> images.check(image)).as(image).doesNotThrowAnyException();
        }
    }

    @Test
    void refusesOtherImages() {
        for (String image : List.of("alpine:latest", "bobcrew", "evil/bobcrew/x", "docker.io/bobcrew/bob-base",
                "registry.example.com/team/tool:1.3", "registry.example.com/team/tool:1.2-debug", "local/default")) {
            assertThatThrownBy(() -> images.check(image)).as(image).isInstanceOf(ApiException.class)
                .hasMessageContaining("not allowed");
        }
    }

    @Test
    void dotsAndOtherRegexCharactersAreLiteral() {
        AllowedImages exact = new AllowedImages(List.of("a.b:1"), "d");
        assertThatThrownBy(() -> exact.check("axb:1")).hasMessageContaining("not allowed");
    }

    @Test
    void refusesMalformedNamesBeforeTheList() {
        AllowedImages everything = new AllowedImages(List.of("*"), "d");
        assertThatCode(() -> everything.check("anything/at:all")).doesNotThrowAnyException();
        assertThatThrownBy(() -> everything.check("bad name")).hasMessageContaining("Invalid image name");
    }
}
