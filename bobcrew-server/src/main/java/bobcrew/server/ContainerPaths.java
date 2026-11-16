package bobcrew.server;

import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.http.HttpStatus;

/**
 * Turns a path that came from a client into a path inside a session's container, and makes sure it stays
 * inside the workspace ({@value DockerRuntime#WORKSPACE}). This is the only place that does: every path
 * from a request goes through {@link #resolve}.
 *
 * <p>The check is on the text only (POSIX rules, whatever the server's own system). Symbolic links inside
 * the container are dealt with where the file is read: only regular files are served.
 */
final class ContainerPaths {

    private ContainerPaths() {
    }

    /**
     * Resolves a client path: {@code file://} prefix allowed, relative paths taken from the workspace, absolute
     * ones accepted only inside it.
     * @return a normalized absolute path inside the workspace, never the workspace itself
     * @throws ApiException 400 if the path is empty, malformed, or leaves the workspace
     */
    static String resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "path parameter is required");
        }
        String path = raw.strip();
        if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        }
        if (path.indexOf('\0') >= 0 || path.length() > 4096) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        String absolute = path.startsWith("/") ? path : DockerRuntime.WORKSPACE + "/" + path;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : absolute.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "The file must be inside the session workspace");
                }
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        String normalized = "/" + String.join("/", parts);
        if (!normalized.startsWith(DockerRuntime.WORKSPACE + "/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The file must be inside the session workspace");
        }
        return normalized;
    }
}
