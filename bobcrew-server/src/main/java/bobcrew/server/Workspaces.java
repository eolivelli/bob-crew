package bobcrew.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Session workspaces are directories under one root. A client names one by a relative path; this
 * class is the only place that turns such a name into a directory, and it never returns one
 * outside the root.
 */
@Component
class Workspaces {

    private static final Logger log = LoggerFactory.getLogger(Workspaces.class);

    private final Path root;

    Workspaces(BobCrewProperties properties) {
        Path configured = properties.workspaceRoot();
        if (configured == null) {
            throw new IllegalStateException("bobcrew.workspace-root is not set");
        }
        try {
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot use the workspace root " + configured, e);
        }
    }

    Path root() {
        return root;
    }

    /**
     * Resolves a workspace name to its directory, creating it if it does not exist.
     * @throws ApiException with 400 if the name is absolute, leaves the root (directly or through
     * a symbolic link) or is not a directory
     */
    Path resolve(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The workspace must not be empty");
        }
        if (name.indexOf('\0') >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid workspace name");
        }
        Path relative;
        try {
            relative = Path.of(name);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid workspace name");
        }
        if (relative.isAbsolute()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The workspace must be a relative path");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The workspace must stay inside the workspace root");
        }
        try {
            // The nearest existing ancestor must really be inside the root before anything is
            // created under it: a symbolic link on the way could lead elsewhere.
            Path existing = target;
            while (!Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (!existing.toRealPath().startsWith(root)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The workspace must stay inside the workspace root");
            }
            Files.createDirectories(target);
            Path real = target.toRealPath();
            if (!real.startsWith(root) || !Files.isDirectory(real)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The workspace must be a directory inside the workspace root");
            }
            return real;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot use this workspace: " + e.getMessage());
        }
    }

    /**
     * Deletes everything inside a workspace directory, hidden files included, and keeps the
     * directory itself. Symbolic links are removed, never followed. The directory must still be
     * the one {@link #resolve} returned: if something replaced it (with a link, say), nothing is
     * deleted.
     * @throws ApiException with 400 for the root itself, 409 if the directory is no longer where it was
     */
    void wipe(Path workspace) {
        try {
            Path real = workspace.toRealPath();
            if (!real.equals(workspace) || !real.startsWith(root)) {
                throw new ApiException(HttpStatus.CONFLICT, "The workspace directory has changed; nothing was deleted");
            }
            if (real.equals(root)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The workspace root itself cannot be wiped");
            }
            Files.walkFileTree(real, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    if (!directory.equals(real)) {
                        Files.delete(directory);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Wiping workspace {} failed: {}", workspace, e.toString());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The workspace could not be wiped completely; see the server log");
        }
    }

    /** The name of a workspace directory, relative to the root. */
    String nameOf(Path workspace) {
        String name = root.relativize(workspace).toString();
        return name.isEmpty() ? "." : name;
    }

    /** The directories directly under the root, sorted by name. */
    List<String> list() {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.startsWith("."))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
