package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The archive's files: what survives a restart, and what it refuses to read. */
class SessionArchiveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path root;

    private static AgentSession.Info info(String id, String owner, List<String> sharedWith) {
        Instant now = Instant.now();
        return new AgentSession.Info(id, owner, sharedWith, "a title", "bobcrew/bob-base:latest", "abc", List.of("GH_TOKEN"),
                false, null, List.of(), new Resources(null, null), AgentSession.Status.CLOSED, "Fake 1", "agent",
                List.of(), now, now, 3);
    }

    private static List<UiEvent> events() {
        return List.of(new UiEvent(1, Instant.now(), UiEvent.USER_PROMPT, "alice", Map.of("text", "hi")),
                new UiEvent(2, Instant.now(), UiEvent.STATUS, null, Map.of("status", "CLOSED")));
    }

    @Test
    void keepsWhatItWroteAcrossInstances() throws Exception {
        String older = UUID.randomUUID().toString();
        String newer = UUID.randomUUID().toString();
        SessionArchive archive = new SessionArchive(root.resolve("archive"), JSON);
        assertThat(archive.list()).as("no directory yet").isEmpty();
        archive.write(info(older, "alice", List.of("bob")), events(), "alice");
        Thread.sleep(5);
        archive.write(info(newer, "carol", List.of()), events(), "admin");
        // written again, as when a session closes: replaced, not doubled
        archive.write(info(newer, "carol", List.of()), events(), "admin");

        SessionArchive reopened = new SessionArchive(root.resolve("archive"), JSON);
        List<SessionArchive.Entry> entries = reopened.list();
        assertThat(entries).extracting(SessionArchive.Entry::id).as("newest first").containsExactly(newer, older);
        SessionArchive.Entry entry = reopened.find(older).orElseThrow();
        assertThat(entry.owner()).isEqualTo("alice");
        assertThat(entry.sharedWith()).containsExactly("bob");
        assertThat(entry.details().get("eventCount").asInt()).isEqualTo(2);
        assertThat(entry.details().get("secrets").toString()).isEqualTo("[\"GH_TOKEN\"]");
        try (InputStream in = reopened.openEvents(older)) {
            JsonNode read = JSON.readTree(in.readAllBytes());
            assertThat(read.get(0).get("data").get("text").asString()).isEqualTo("hi");
        }
        assertThat(reopened.countOwnedBy("carol")).isEqualTo(1);
        try (var files = Files.list(root.resolve("archive").resolve(newer))) {
            assertThat(files.map(f -> f.getFileName().toString())).as("no temporary files left")
                .containsExactlyInAnyOrder("info.json", "events.json");
        }

        reopened.delete(older);
        assertThat(reopened.find(older)).isEmpty();
        assertThat(root.resolve("archive").resolve(older)).doesNotExist();
        assertThatThrownBy(() -> reopened.openEvents(older)).isInstanceOf(ApiException.class);
        reopened.delete(older); // nothing left: nothing happens
    }

    @Test
    void readsOnlyDirectoriesNamedAsSessions() throws Exception {
        SessionArchive archive = new SessionArchive(root, JSON);
        String id = UUID.randomUUID().toString();
        archive.write(info(id, "alice", List.of()), events(), "alice");
        // a copy under another name, a link to it, and a file whose id says another session
        Path copy = Files.createDirectory(root.resolve("not-a-session"));
        Files.copy(root.resolve(id).resolve("info.json"), copy.resolve("info.json"));
        String linked = UUID.randomUUID().toString();
        Files.createSymbolicLink(root.resolve(linked), root.resolve(id));
        String mismatched = UUID.randomUUID().toString();
        Files.createDirectory(root.resolve(mismatched));
        Files.copy(root.resolve(id).resolve("info.json"), root.resolve(mismatched).resolve("info.json"));

        assertThat(archive.list()).extracting(SessionArchive.Entry::id).containsExactly(id);
        for (String bad : List.of("not-a-session", linked, mismatched, id.toUpperCase(), "../" + id, "", "..")) {
            assertThat(archive.find(bad)).as(bad).isEmpty();
        }
        assertThat(archive.find(null)).isEmpty();
        archive.delete("../" + id);
        assertThat(archive.find(id)).as("not deleted through another name").isPresent();
    }
}
