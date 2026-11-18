package bobcrew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where the values of a role's {@code secrets} come from: one JSON object of names and string values, by default
 * {@code ~/.bobcrew/secrets.json}, such as {@code {"GH_TOKEN": "github_pat_...", "GIT_USER_NAME": "Ada"}}. It
 * also holds the user's own Bob API key, {@link #API_KEY}, which is passed with every session.
 *
 * <p>A file of its own rather than this app's environment, so that a session gets exactly what the user put there
 * for sessions, never whatever else happens to be exported in the shell that started the UI. It is read again for
 * every session created, so edits apply at once. It must be readable by its owner only (chmod 600), or it is not
 * used at all. Nothing read from it is ever logged, and error messages never quote it, since a malformed file may
 * have a value where a name should be.
 */
@Component
class SecretStore {

    /** A secret's name: it becomes a file name in the container, so no separators or dots. */
    static final Pattern NAME = RoleRegistry.SECRET_NAME;

    static final long MAX_BYTES = 1024 * 1024;

    /**
     * The one name that is not a role's secret: the user's own Bob API key. When the file has it, every session
     * this UI creates uses it in place of the server's key, so that users of a shared server each work with their
     * own. It is not written as a file in the container: Bob reads its key from its environment only, so the
     * server puts it there ({@code apiKey} in the request).
     */
    static final String API_KEY = "BOB_API_KEY";

    private final Path file;

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    SecretStore(UiProperties properties) {
        this(properties.secretsFile() != null && !properties.secretsFile().isBlank()
                ? Path.of(properties.secretsFile())
                : Path.of(System.getProperty("user.home"), ".bobcrew", "secrets.json"));
    }

    SecretStore(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    /**
     * All the secrets in the file; none when there is no file.
     * @throws IllegalStateException with a message for the user if the file cannot be used
     */
    Map<String, String> load() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("The secrets file " + file + " is not a regular file");
        }
        if (ServerConnection.readableByOthers(file)) {
            throw new IllegalStateException("The secrets file " + file
                    + " can be read by other users of this computer; restrict it with chmod 600 and try again");
        }
        JsonNode root;
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw new IllegalStateException("The secrets file " + file + " is larger than 1 MB");
            }
            root = json.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the secrets file " + file + ": " + e.getClass().getSimpleName());
        } catch (JacksonException e) {
            // the parser's message can quote the file
            throw new IllegalStateException("The secrets file " + file + " is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("The secrets file " + file
                    + " must hold one JSON object of names and values, such as {\"GH_TOKEN\": \"...\"}");
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (!NAME.matcher(entry.getKey()).matches()) {
                throw new IllegalStateException("The secrets file " + file + " has a name that is not valid (letters, "
                        + "digits and _, not starting with a digit)");
            }
            if (!entry.getValue().isString()) {
                throw new IllegalStateException("In the secrets file " + file + ", the value of " + entry.getKey()
                        + " must be a string");
            }
            secrets.put(entry.getKey(), entry.getValue().asString());
        }
        return secrets;
    }
}
