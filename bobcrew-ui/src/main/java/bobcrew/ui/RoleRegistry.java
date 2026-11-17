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
 * <li>{@code secrets}: names, separated by commas or spaces, of the secrets this UI app passes from
 * {@code ~/.bobcrew/secrets.json} ({@link SecretStore}) to the session, where they become files in
 * {@code /run/bobcrew/secrets} (never environment variables, and never sent to the browser). {@code env}, the
 * key this used to be, still works as another name for it: the values come from the secrets file all the same,
 * never from this app's environment;</li>
 * <li>{@code repository}, {@code branch}: an https git repository that the server clones (shallow, that branch
 * only, or the default branch when none is given) into the workspace before the agent starts; the first prompt
 * then tells the agent where it is;</li>
 * <li>{@code capabilities}: what the agent may run besides commands, separated by commas or spaces:
 * {@code docker} (a Docker Engine of its own), {@code libvirt} (virtual machines, for Vagrant); the image must
 * have them (bobcrew/bob-power does) and the server must allow them;</li>
 * <li>{@code memory} (such as {@code 8GB}), {@code cpus} (such as {@code 2} or {@code 0.5}): the resources the
 * session's container asks for; without them, the server's defaults (by default no limit).</li>
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
     * is null when the role names none, {@code secrets} holds names only; {@code repository} and
     * {@code branch} are null when not given; {@code capabilities} lists the capabilities asked for
     * (possibly none); {@code memory} and {@code cpus} are null when not given.
     */
    record Role(String id, String name, String description, String prompt, String manifest, String image,
            List<String> secrets, String repository, String branch, List<String> capabilities, String memory,
            Double cpus) {
    }

    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /** What a Docker image reference may look like; the server checks again. */
    static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}");

    /** A repository URL: https, no credentials, no spaces; the server checks it fully. */
    static final Pattern REPOSITORY = Pattern.compile("https://[^\\s@?#]{1,2000}");

    /** The capabilities a role may ask for; the server decides whether they are allowed. */
    static final List<String> CAPABILITIES = List.of("docker", "libvirt");

    /** A memory size as the server reads it: a whole number and a unit. */
    static final Pattern MEMORY = Pattern.compile("[0-9]{1,9}(B|KB|MB|GB|TB)");

    /** A branch name; the server checks it fully. */
    static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/+-]{0,199}");

    /** A secret's name; it becomes a file name in the container, so the server allows no more. */
    static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

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
        String repository = null;
        String branch = null;
        List<String> capabilities = new ArrayList<>();
        String memory = null;
        Double cpus = null;
        List<String> secrets = new ArrayList<>();
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
                    } else if (key.equals("repository")) {
                        if (REPOSITORY.matcher(value).matches()) {
                            repository = value;
                        } else {
                            log.warn("Role {}: ignoring the repository, which must be an https URL without credentials", id);
                        }
                    } else if (key.equals("branch")) {
                        if (BRANCH.matcher(value).matches() && !value.contains("..")) {
                            branch = value;
                        } else {
                            log.warn("Role {}: ignoring the invalid branch name", id);
                        }
                    } else if (key.equals("capabilities")) {
                        for (String capability : value.toLowerCase().split("[,\\s]+")) {
                            if (capability.isEmpty()) {
                                continue;
                            }
                            if (CAPABILITIES.contains(capability)) {
                                if (!capabilities.contains(capability)) {
                                    capabilities.add(capability);
                                }
                            } else {
                                log.warn("Role {}: ignoring the unknown capability {}", id, capability);
                            }
                        }
                    } else if (key.equals("memory")) {
                        String size = value.replace(" ", "").toUpperCase();
                        if (MEMORY.matcher(size).matches()) {
                            memory = size;
                        } else {
                            log.warn("Role {}: ignoring memory, which must be a size such as 8GB or 512MB", id);
                        }
                    } else if (key.equals("cpus")) {
                        try {
                            double parsed = Double.parseDouble(value);
                            if (parsed > 0 && parsed <= 1024) {
                                cpus = parsed;
                            } else {
                                log.warn("Role {}: ignoring cpus, which must be more than 0", id);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Role {}: ignoring cpus, which must be a number such as 2 or 0.5", id);
                        }
                    } else if (key.equals("secrets") || key.equals("env")) {
                        if (key.equals("env")) {
                            log.warn("Role {}: 'env' is now 'secrets'; its values come from the secrets file", id);
                        }
                        for (String secret : value.split("[,\\s]+")) {
                            if (secret.isEmpty()) {
                                continue;
                            }
                            if (SECRET_NAME.matcher(secret).matches()) {
                                if (!secrets.contains(secret)) {
                                    secrets.add(secret);
                                }
                            } else {
                                log.warn("Role {}: ignoring the invalid secret name {}", id, secret);
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
        if (repository == null && branch != null) {
            log.warn("Role {}: ignoring the branch, since there is no repository", id);
            branch = null;
        }
        return new Role(id, name == null || name.isBlank() ? id : name, description, prompt, manifest, image,
                List.copyOf(secrets), repository, branch, List.copyOf(capabilities), memory, cpus);
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
