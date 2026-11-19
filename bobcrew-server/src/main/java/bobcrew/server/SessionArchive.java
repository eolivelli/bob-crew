package bobcrew.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Closed sessions kept so that their users can read the transcript later: one directory per session under
 * {@code bobcrew.archive.dir}, named by the session's id, holding {@code info.json} (the session's details as the
 * API showed them when it closed, plus who archived it and when) and {@code events.json} (its events as the event
 * stream gave them: the last {@code bobcrew.event-buffer-size}). Only the transcript is kept, never the workspace.
 *
 * <p>Files, not a database, as the server keeps nothing else: they survive a restart and can be backed up or
 * removed by hand. They are the server's user's alone (directories 700, files 600), since a transcript holds
 * whatever the agent printed. Each file is written under a temporary name and renamed into place, so a reader
 * never sees half of one.
 *
 * <p>An id from a request becomes a file name only once it reads back as the UUID the server made, the same text;
 * nothing else from a request is ever part of a path here. Directories that are not such ids, and symbolic links,
 * are ignored.
 */
@Component
class SessionArchive {

    private static final Logger log = LoggerFactory.getLogger(SessionArchive.class);

    private static final String INFO = "info.json";

    private static final String EVENTS = "events.json";

    /** The agent's own record of the task (Bob's {@code _bob/task/export}), when it gave one: what resuming needs. */
    private static final String AGENT = "agent.json";

    /** What a listing needs of an archived session, read from its {@code info.json}. */
    record Entry(String id, String owner, Set<String> sharedWith, Instant archivedAt, ObjectNode details) {
    }

    private final Path root;

    private final JsonMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    SessionArchive(BobCrewProperties properties, JsonMapper json) {
        this(Path.of(properties.archive().dir()), json);
    }

    SessionArchive(Path root, JsonMapper json) {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
    }

    /**
     * Writes the session's archive, replacing what an earlier write left for it.
     * @param session the session's details; its secrets are names only, as always
     * @param events its kept events, oldest first
     * @param by who archived it
     * @throws ApiException 500 if the files cannot be written
     */
    void write(AgentSession.Info session, List<UiEvent> events, String by, Object agentTask) {
        ObjectNode details = json.valueToTree(session);
        details.put("archivedAt", Instant.now().toString());
        details.put("archivedBy", by);
        details.put("eventCount", events.size());
        details.put("resumable", agentTask != null);
        try {
            Path directory = privateDirectory(privateDirectory(root).resolve(session.id()));
            // events first: an info.json is there only once its events are
            replace(directory, EVENTS, json.writeValueAsBytes(events));
            if (agentTask != null) {
                replace(directory, AGENT, json.writeValueAsBytes(agentTask));
            } else {
                Files.deleteIfExists(directory.resolve(AGENT));
            }
            replace(directory, INFO, json.writeValueAsBytes(details));
        } catch (IOException | UncheckedIOException e) {
            log.error("Session {}: cannot write its archive in {}: {}", session.id(), root, e.toString());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The server could not write the session's archive; ask its administrator to check the disk");
        }
    }

    /** All archived sessions, newest first; unreadable ones are skipped (and logged). */
    List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return entries;
        }
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                find(directory.getFileName().toString()).ifPresent(entries::add);
            }
        } catch (IOException e) {
            log.warn("Cannot list the archive {}: {}", root, e.toString());
        }
        entries.sort((a, b) -> b.archivedAt().compareTo(a.archivedAt()));
        return entries;
    }

    /** The archived session with this id, if there is one. */
    Optional<Entry> find(String id) {
        Optional<Path> info = file(id, INFO);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode read = json.readTree(Files.readAllBytes(info.get()));
            if (!(read instanceof ObjectNode details) || !id.equals(details.path("id").asString(""))) {
                log.warn("Archive {}: {} is not a session's details; ignored", id, INFO);
                return Optional.empty();
            }
            Set<String> sharedWith = new LinkedHashSet<>();
            details.path("sharedWith").valueStream().forEach(user -> sharedWith.add(user.asString("")));
            return Optional.of(new Entry(id, details.path("owner").asString(""), Set.copyOf(sharedWith),
                    Instant.parse(details.path("archivedAt").asString("1970-01-01T00:00:00Z")), details));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | JacksonException | java.time.DateTimeException e) {
            log.warn("Archive {}: cannot read {}: {}", id, INFO, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The archived session's events, a JSON array, to stream as is.
     * @throws ApiException 404 if they are gone
     */
    InputStream openEvents(String id) {
        try {
            return Files.newInputStream(file(id, EVENTS).orElseThrow(() -> gone()));
        } catch (IOException e) {
            throw gone();
        }
    }

    /**
     * The archived session's events, to go on from when it is resumed.
     * @throws ApiException 404 if they are gone, 500 if they cannot be read
     */
    List<UiEvent> readEvents(String id) {
        try (InputStream in = openEvents(id)) {
            return List.of(json.readValue(in, UiEvent[].class));
        } catch (IOException | JacksonException e) {
            log.error("Archive {}: cannot read {}: {}", id, EVENTS, e.toString());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The archived session's events cannot be read");
        }
    }

    /** The agent's record of the task, as plain JSON values, or null if the archive has none. */
    Object readAgentTask(String id) {
        Optional<Path> file = file(id, AGENT);
        if (file.isEmpty()) {
            return null;
        }
        try {
            return json.readValue(Files.readAllBytes(file.get()), Object.class);
        } catch (IOException | JacksonException e) {
            log.error("Archive {}: cannot read {}: {}", id, AGENT, e.toString());
            return null;
        }
    }

    /** How many archived sessions belong to {@code owner}. */
    long countOwnedBy(String owner) {
        return list().stream().filter(entry -> entry.owner().equals(owner)).count();
    }

    /** Deletes the archived session's files; nothing happens if there are none. */
    void delete(String id) {
        Optional<Path> directory = directory(id);
        if (directory.isEmpty() || !Files.isDirectory(directory.get(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory.get())) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory.get());
        } catch (IOException e) {
            log.error("Archive {}: cannot delete it: {}", id, e.toString());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The server could not delete the archive");
        }
    }

    private static ApiException gone() {
        return new ApiException(HttpStatus.NOT_FOUND, "No such archived session");
    }

    /** The session's directory, if {@code id} is a session id the server could have made. */
    private Optional<Path> directory(String id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                return Optional.empty();
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(root.resolve(id));
    }

    /** One of the session's files, if it is a regular file (not a link). */
    private Optional<Path> file(String id, String name) {
        return directory(id)
            .filter(directory -> Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            .map(directory -> directory.resolve(name))
            .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
    }

    private static Path privateDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory);
            restrict(directory, "rwx------");
        }
        return directory;
    }

    private static void replace(Path directory, String name, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(directory, "." + name, ".tmp");
        try {
            restrict(temporary, "rw-------");
            Files.write(temporary, content);
            try {
                Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system: what it does by default
        }
    }
}
