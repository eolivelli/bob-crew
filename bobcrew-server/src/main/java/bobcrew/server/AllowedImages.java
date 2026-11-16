package bobcrew.server;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * The images a client may name for a session ({@code bobcrew.docker.allowed-images}), checked by the server itself.
 * The UI's roles decide which image a browser session gets, but roles are a convenience of the UI: anyone with a
 * token can call the API directly and name any image on the Docker host, including ones never meant to run
 * sessions. Patterns are matched against the name as given; {@code *} matches any characters, {@code /} and
 * {@code :} included. The server's default image is always allowed.
 */
final class AllowedImages {

    private final List<Pattern> patterns;

    private final String defaultImage;

    AllowedImages(List<String> configured, String defaultImage) {
        List<Pattern> compiled = new ArrayList<>();
        if (configured != null) {
            for (String glob : configured) {
                String trimmed = glob == null ? "" : glob.strip();
                if (!trimmed.isEmpty()) {
                    compiled.add(toRegex(trimmed));
                }
            }
        }
        this.patterns = List.copyOf(compiled);
        this.defaultImage = defaultImage;
    }

    /**
     * @throws ApiException 400 if the name is malformed, 403 if the server does not allow it
     */
    void check(String image) {
        if (!DockerRuntime.IMAGE.matcher(image).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid image name");
        }
        if (image.equals(defaultImage) || patterns.stream().anyMatch(pattern -> pattern.matcher(image).matches())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "The image " + image + " is not allowed on this server");
    }

    private static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int start = 0;
        for (int star = glob.indexOf('*'); star >= 0; star = glob.indexOf('*', start)) {
            if (star > start) {
                regex.append(Pattern.quote(glob.substring(start, star)));
            }
            regex.append(".*");
            start = star + 1;
        }
        if (start < glob.length()) {
            regex.append(Pattern.quote(glob.substring(start)));
        }
        return Pattern.compile(regex.toString());
    }
}
