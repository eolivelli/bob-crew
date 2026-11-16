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
