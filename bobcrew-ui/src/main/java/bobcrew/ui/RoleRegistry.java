package bobcrew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The library of roles: named default prompts, one Markdown manifest per role in a directory (by default
 * {@code ~/.bobcrew/roles}). A manifest is an optional front matter block ({@code key: value} lines between two
 * {@code ---} lines) followed by the prompt. The keys:
 * <ul>
 * <li>{@code name}, {@code description}: what the user sees;</li>
 * <li>{@code image}: the Docker image the session runs in (otherwise the server's default image);</li>
 * <li>{@code env}: names of environment variables, separated by commas or spaces, whose values this UI app
 * passes from its own environment to the session's container (never to the browser).</li>
 * </ul>
 *
 * <p>The directory is read on every call so that edits show up without restarting the UI. Role ids come from
 * client requests, so they must match {@link #ID} (no separators, so no way out of the directory) before a
 * file name is built from them. Symbolic links, oversized files and anything beyond
 * {@link #MAX_ROLES} are ignored, so a stray file cannot make the UI read arbitrary data or run out of memory.
 */
@Component
class RoleRegistry {

    /**
     * A role as shown to the user. {@code manifest} is the file as written, {@code prompt} its body; {@code image}
     * is null when the role names none, {@code env} holds variable names only.
     */
    record Role(String id, String name, String description, String prompt, String manifest, String image,
            List<String> env) {
    }

    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /** What a Docker image reference may look like; the server checks again. */
    static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}");

    /** An environment variable name. */
    static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    static final String EXTENSION = ".md";

    static final int MAX_ROLES = 200;

    static final long MAX_MANIFEST_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(RoleRegistry.class);

    private final Path directory;

    @Autowired
    RoleRegistry(UiProperties properties) {
        this(properties.rolesDir() != null && !properties.rolesDir().isBlank()
                ? Path.of(properties.rolesDir())
                : Path.of(System.getProperty("user.home"), ".bobcrew", "roles"));
    }

    RoleRegistry(Path directory) {
        this.directory = directory;
    }

    /** All valid roles, sorted by name. A missing directory means no roles. */
    List<Role> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(EXTENSION))
                .map(name -> name.substring(0, name.length() - EXTENSION.length()))
                .filter(id -> ID.matcher(id).matches())
                .sorted()
                .limit(MAX_ROLES)
                .map(this::load)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing((Role role) -> role.name().toLowerCase()).thenComparing(Role::id))
                .toList();
        } catch (IOException e) {
            log.warn("Cannot list the roles directory {}: {}", directory, e.toString());
            return List.of();
        }
    }

    /** One role by id, or empty when there is no such valid role. */
    Optional<Role> get(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return load(id);
    }

    private Optional<Role> load(String id) {
        Path file = directory.resolve(id + EXTENSION);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (Files.size(file) > MAX_MANIFEST_BYTES) {
                log.warn("Ignoring role {}: manifest is larger than {} bytes", id, MAX_MANIFEST_BYTES);
                return Optional.empty();
            }
            Role role = parse(id, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (role == null) {
                log.warn("Ignoring role {}: the prompt is empty", id);
            }
            return Optional.ofNullable(role);
        } catch (IOException e) {
            log.warn("Ignoring role {}: cannot read the manifest: {}", id, e.toString());
            return Optional.empty();
        }
    }

    /** Parses a manifest; returns null when the prompt is empty. Package-private for tests. */
    static Role parse(String id, String manifest) {
        String text = manifest.startsWith("\uFEFF") ? manifest.substring(1) : manifest;
        String name = null;
        String description = "";
        String image = null;
        List<String> env = new ArrayList<>();
        String body = text;

        String[] lines = text.split("\n", -1);
        if (lines.length > 0 && lines[0].stripTrailing().equals("---")) {
            int end = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].stripTrailing().equals("---")) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                for (int i = 1; i < end; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = lines[i].substring(0, colon).strip().toLowerCase();
                    String value = unquote(lines[i].substring(colon + 1).strip());
                    if (key.equals("name")) {
                        name = value;
                    } else if (key.equals("description")) {
                        description = value;
                    } else if (key.equals("image")) {
                        if (IMAGE.matcher(value).matches()) {
                            image = value;
                        } else {
                            log.warn("Role {}: ignoring the invalid image name", id);
                        }
                    } else if (key.equals("env")) {
                        for (String variable : value.split("[,\\s]+")) {
                            if (variable.isEmpty()) {
                                continue;
                            }
                            if (ENV_NAME.matcher(variable).matches()) {
                                if (!env.contains(variable)) {
                                    env.add(variable);
                                }
                            } else {
                                log.warn("Role {}: ignoring the invalid environment variable name {}", id, variable);
                            }
                        }
                    }
                }
                body = String.join("\n", List.of(lines).subList(end + 1, lines.length));
            }
        }

        String prompt = body.strip();
        if (prompt.isEmpty()) {
            return null;
        }
        return new Role(id, name == null || name.isBlank() ? id : name, description, prompt, manifest, image,
                List.copyOf(env));
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
