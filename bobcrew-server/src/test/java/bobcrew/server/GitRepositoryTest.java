package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class GitRepositoryTest {

    private static final List<String> HTTPS = List.of("https");

    @Test
    void acceptsHttpsRepositoriesAndNamesTheDirectory() {
        GitRepository repository = GitRepository.of(" https://github.com/datastax/cassandra ", "main", HTTPS);
        assertThat(repository.url()).isEqualTo("https://github.com/datastax/cassandra");
        assertThat(repository.branch()).isEqualTo("main");
        assertThat(repository.directory()).isEqualTo("cassandra");
        assertThat(repository.path()).isEqualTo("/opt/workspace/cassandra");

        assertThat(GitRepository.of("https://github.com/a/b.git", null, HTTPS).branch()).isNull();
        assertThat(GitRepository.of("https://github.com/a/b.git", " ", HTTPS).directory()).isEqualTo("b");
        assertThat(GitRepository.of("https://git.example.com:8443/x/y/", "release/5.0", HTTPS).directory())
            .isEqualTo("y");
        assertThat(GitRepository.of(null, null, HTTPS)).isNull();
        assertThat(GitRepository.of("  ", "", HTTPS)).isNull();
    }

    @Test
    void namesUnusualDirectoriesSafely() {
        assertThat(GitRepository.directoryName("/")).isEqualTo("repository");
        assertThat(GitRepository.directoryName("/a/..")).isEqualTo("repository");
        assertThat(GitRepository.directoryName("/a/.git")).isEqualTo("repository");
        assertThat(GitRepository.directoryName("/a/we%20ird name$")).isEqualTo("we_20ird_name_");
    }

    @Test
    void refusesOtherSchemesCredentialsAndBadBranches() {
        for (String url : List.of("http://github.com/a/b", "ssh://git@github.com/a/b", "git@github.com:a/b.git",
                "file:///etc", "https://user:token@github.com/a/b", "https://token@github.com/a/b",
                "https://github.com/a/b?x=1", "https://github.com/a/b#frag", "https:///a/b", "https://github.com/a b",
                "https://github.com/a/\nb", "-https://x/y")) {
            assertThatThrownBy(() -> GitRepository.of(url, null, HTTPS)).as(url).isInstanceOf(ApiException.class);
        }
        for (String branch : List.of("-x", "--upload-pack=sh", "a..b", "a b", "a/", "a.lock", "a//b", "a~1", "a^",
                "a:b", "x".repeat(300))) {
            assertThatThrownBy(() -> GitRepository.of("https://github.com/a/b", branch, HTTPS)).as(branch)
                .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> GitRepository.of(null, "main", HTTPS)).isInstanceOf(ApiException.class);
    }

    @Test
    void allowsFileUrlsOnlyWhenConfigured() {
        assertThat(GitRepository.of("file:///opt/repos/x.git", null, List.of("https", "file")).directory())
            .isEqualTo("x");
    }
}
