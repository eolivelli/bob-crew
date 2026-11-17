package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoleRegistryTest {

    @TempDir
    Path directory;

    private RoleRegistry registry() {
        return new RoleRegistry(directory);
    }

    @Test
    void readsFrontMatterAndPrompt() throws Exception {
        Files.writeString(directory.resolve("qa-tester.md"),
                "---\nname: QA Tester\ndescription: \"Finds defects\"\n---\n\nTest the app.\nReport bugs.\n");
        RoleRegistry.Role role = registry().get("qa-tester").orElseThrow();
        assertThat(role.id()).isEqualTo("qa-tester");
        assertThat(role.name()).isEqualTo("QA Tester");
        assertThat(role.description()).isEqualTo("Finds defects");
        assertThat(role.prompt()).isEqualTo("Test the app.\nReport bugs.");
        assertThat(role.manifest()).startsWith("---\nname: QA Tester");
        assertThat(role.image()).isNull();
        assertThat(role.secrets()).isEmpty();
    }

    @Test
    void readsTheImageAndTheSecretNames() throws Exception {
        Files.writeString(directory.resolve("builder.md"), "---\nname: Builder\nimage: bobcrew/bob-java:latest\n"
                + "secrets: GH_TOKEN, JIRA_TOKEN  MAVEN_OPTS,GH_TOKEN, bad-name, 9X, ../x\n---\nBuild it.\n");
        RoleRegistry.Role role = registry().get("builder").orElseThrow();
        assertThat(role.image()).isEqualTo("bobcrew/bob-java:latest");
        assertThat(role.secrets()).containsExactly("GH_TOKEN", "JIRA_TOKEN", "MAVEN_OPTS");
    }

    @Test
    void envIsStillReadAsSecrets() throws Exception {
        Files.writeString(directory.resolve("old.md"), "---\nenv: GH_TOKEN\nsecrets: GIT_USER_NAME\n---\nGo.\n");
        assertThat(registry().get("old").orElseThrow().secrets()).containsExactly("GH_TOKEN", "GIT_USER_NAME");
    }

    @Test
    void theTemplateDeveloperRoleIsValid() {
        Path templates = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().resolveSibling("ui.template")
            .resolve("roles");
        RoleRegistry.Role developer = new RoleRegistry(templates).get("developer").orElseThrow();
        assertThat(developer.name()).isEqualTo("Developer");
        assertThat(developer.image()).isEqualTo("bobcrew/bob-java-python:latest");
        // names the server accepts (it refuses BOB_*, BOBCREW_*, LD_*, PATH, HOME and the like)
        assertThat(developer.secrets()).containsExactly("GH_TOKEN", "GIT_USER_NAME", "GIT_USER_EMAIL");
        assertThat(developer.prompt()).contains("unit tests", "subagent", "PROJ-123:");
        assertThat(developer.capabilities()).isEmpty();

        RoleRegistry.Role power = new RoleRegistry(templates).get("power-user").orElseThrow();
        assertThat(power.name()).isEqualTo("Power User");
        assertThat(power.image()).isEqualTo("bobcrew/bob-power:latest");
        assertThat(power.capabilities()).containsExactly("docker", "libvirt");
        assertThat(power.memory()).isEqualTo("16GB");
        assertThat(power.cpus()).isEqualTo(4.0);
        assertThat(power.prompt()).contains("Docker", "Vagrant", "libvirt");
    }

    @Test
    void readsTheRepositoryAndBranch() {
        RoleRegistry.Role role = RoleRegistry.parse("c",
                "---\nrepository: https://github.com/datastax/cassandra\nbranch: release/5.0\n---\nGo.\n");
        assertThat(role.repository()).isEqualTo("https://github.com/datastax/cassandra");
        assertThat(role.branch()).isEqualTo("release/5.0");

        RoleRegistry.Role noBranch = RoleRegistry.parse("c", "---\nrepository: https://github.com/a/b.git\n---\nGo.\n");
        assertThat(noBranch.repository()).isEqualTo("https://github.com/a/b.git");
        assertThat(noBranch.branch()).isNull();

        for (String bad : new String[] {"http://github.com/a/b", "https://user:tok@github.com/a/b", "git@github.com:a/b",
                "https://github.com/a b"}) {
            assertThat(RoleRegistry.parse("c", "---\nrepository: " + bad + "\nbranch: main\n---\nGo.\n"))
                .as(bad).satisfies(r -> {
                    assertThat(r.repository()).isNull();
                    assertThat(r.branch()).as("no branch without a repository").isNull();
                });
        }
        assertThat(RoleRegistry.parse("c", "---\nrepository: https://github.com/a/b\nbranch: --upload-pack=x\n---\nGo.\n")
            .branch()).isNull();
    }

    @Test
    void readsCapabilitiesAndResources() {
        RoleRegistry.Role role = RoleRegistry.parse("p",
                "---\ncapabilities: Docker, libvirt docker root\nmemory: 16 gb\ncpus: 2.5\n---\nGo.\n");
        assertThat(role.capabilities()).as("known ones, once each").containsExactly("docker", "libvirt");
        assertThat(role.memory()).isEqualTo("16GB");
        assertThat(role.cpus()).isEqualTo(2.5);

        RoleRegistry.Role plain = RoleRegistry.parse("p", "Go.\n");
        assertThat(plain.capabilities()).isEmpty();
        assertThat(plain.memory()).isNull();
        assertThat(plain.cpus()).isNull();

        RoleRegistry.Role bad = RoleRegistry.parse("p", "---\nmemory: a lot\ncpus: -1\n---\nGo.\n");
        assertThat(bad.memory()).isNull();
        assertThat(bad.cpus()).isNull();
        assertThat(RoleRegistry.parse("p", "---\ncpus: many\n---\nGo.\n").cpus()).isNull();
    }

    @Test
    void ignoresAnInvalidImageName() throws Exception {
        Files.writeString(directory.resolve("odd.md"), "---\nimage: \"not an image; rm -rf /\"\n---\nGo.\n");
        assertThat(registry().get("odd").orElseThrow().image()).isNull();
    }

    @Test
    void frontMatterIsOptionalAndTheNameFallsBackToTheId() throws Exception {
        Files.writeString(directory.resolve("reviewer.md"), "Review the code.\n");
        RoleRegistry.Role role = registry().get("reviewer").orElseThrow();
        assertThat(role.name()).isEqualTo("reviewer");
        assertThat(role.description()).isEmpty();
        assertThat(role.prompt()).isEqualTo("Review the code.");
    }

    @Test
    void anUnclosedFrontMatterIsPartOfThePrompt() throws Exception {
        Files.writeString(directory.resolve("odd.md"), "---\nname: X\nDo things\n");
        assertThat(registry().get("odd").orElseThrow().prompt()).isEqualTo("---\nname: X\nDo things");
    }

    @Test
    void handlesWindowsLineEndings() throws Exception {
        Files.writeString(directory.resolve("win.md"), "---\r\nname: Win\r\n---\r\nPrompt\r\n");
        RoleRegistry.Role role = registry().get("win").orElseThrow();
        assertThat(role.name()).isEqualTo("Win");
        assertThat(role.prompt()).isEqualTo("Prompt");
    }

    @Test
    void listsRolesSortedByNameAndSkipsInvalidFiles() throws Exception {
        Files.writeString(directory.resolve("b.md"), "---\nname: Code reviewer\n---\nReview.");
        Files.writeString(directory.resolve("a.md"), "---\nname: code investigator\n---\nInvestigate.");
        Files.writeString(directory.resolve("empty.md"), "---\nname: Empty\n---\n  \n");
        Files.writeString(directory.resolve("notes.txt"), "not a role");
        Files.writeString(directory.resolve("bad name.md"), "Prompt");
        Files.writeString(directory.resolve(".hidden.md"), "Prompt");
        Files.createDirectory(directory.resolve("dir.md"));
        assertThat(registry().list()).extracting(RoleRegistry.Role::id).containsExactly("a", "b");
    }

    @Test
    void ignoresOversizedManifests() throws Exception {
        Files.writeString(directory.resolve("big.md"), "x".repeat((int) RoleRegistry.MAX_MANIFEST_BYTES + 1));
        assertThat(registry().list()).isEmpty();
        assertThat(registry().get("big")).isEmpty();
    }

    @Test
    void ignoresSymbolicLinks() throws Exception {
        Path outside = Files.createTempFile("outside", ".md");
        try {
            Files.writeString(outside, "secret");
            Files.createSymbolicLink(directory.resolve("link.md"), outside);
            assertThat(registry().list()).isEmpty();
            assertThat(registry().get("link")).isEmpty();
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void rejectsIdsThatAreNotPlainNames() throws Exception {
        Files.writeString(directory.resolve("ok.md"), "Prompt");
        RoleRegistry registry = registry();
        for (String id : new String[] {"../ok", "a/b", "..", ".ok", "ok.md", "", "o k", "ok\n"}) {
            assertThat(registry.get(id)).as(id).isEmpty();
        }
        assertThat(registry.get(null)).isEmpty();
        assertThat(registry.get("ok")).isPresent();
    }

    @Test
    void aMissingDirectoryMeansNoRoles() {
        RoleRegistry registry = new RoleRegistry(directory.resolve("nope"));
        assertThat(registry.list()).isEmpty();
        assertThat(registry.get("any")).isEmpty();
    }

    @Test
    void picksUpEditsWithoutARestart() throws Exception {
        RoleRegistry registry = registry();
        assertThat(registry.list()).isEmpty();
        Files.writeString(directory.resolve("new.md"), "Prompt");
        assertThat(registry.list()).hasSize(1);
    }
}
