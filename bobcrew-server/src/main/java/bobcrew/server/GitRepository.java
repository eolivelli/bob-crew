package bobcrew.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * A repository to clone into a new session's workspace, checked: the URL is one of the allowed schemes
 * (by default https only) and carries no credentials, which would end up in logs, events and the session's
 * details; private repositories are reached with credentials the image sets up instead, such as bob-base's
 * {@code GH_TOKEN}. The branch is a plain ref name. git gets both as separate arguments after {@code --}, never
 * through a shell.
 *
 * @param url the repository URL, as given
 * @param branch the branch (or tag) to check out, or null for the repository's default branch
 * @param directory the clone's directory name inside the workspace, taken from the URL's last segment
 */
record GitRepository(String url, String branch, String directory) {

    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/+-]{0,199}");

    private static final Pattern DIRECTORY_UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    /** The clone's absolute path in the container. */
    String path() {
        return DockerRuntime.WORKSPACE + "/" + directory;
    }

    /**
     * @param allowedSchemes URL schemes accepted, lower case
     * @return the checked repository, or null when {@code url} is null or blank
     * @throws ApiException 400 if the URL or the branch is not acceptable
     */
    static GitRepository of(String url, String branch, List<String> allowedSchemes) {
        if (url == null || url.isBlank()) {
            if (branch != null && !branch.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A branch was given without a repository");
            }
            return null;
        }
        String trimmed = url.strip();
        if (trimmed.length() > 2048 || !trimmed.codePoints().allMatch(c -> c > 0x20 && c != 0x7f)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid repository URL");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid repository URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The repository URL must start with "
                    + String.join("://, ", allowedSchemes) + "://");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The repository URL must not contain credentials; pass a token to the session instead");
        }
        if (!scheme.equals("file") && (uri.getHost() == null || uri.getHost().isEmpty())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The repository URL has no host");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The repository URL must not have a query or fragment");
        }
        String checkedBranch = null;
        if (branch != null && !branch.isBlank()) {
            checkedBranch = branch.strip();
            if (!BRANCH.matcher(checkedBranch).matches() || checkedBranch.contains("..")
                    || checkedBranch.contains("//") || checkedBranch.endsWith("/") || checkedBranch.endsWith(".")
                    || checkedBranch.endsWith(".lock")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid branch name");
            }
        }
        return new GitRepository(trimmed, checkedBranch, directoryName(uri.getPath()));
    }

    /** {@code https://github.com/datastax/cassandra.git} gives {@code cassandra}. */
    static String directoryName(String path) {
        String name = path == null ? "" : path;
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        name = DIRECTORY_UNSAFE.matcher(name).replaceAll("_");
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) {
            return "repository";
        }
        return name;
    }
}
