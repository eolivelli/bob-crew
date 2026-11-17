package bobcrew.server;

/**
 * A file to be written into the session container's user home directory (/home/bob) before the agent runs.
 *
 * @param path relative path under the container user's home (e.g. {@code .m2/settings.xml})
 * @param contentBase64 Base64-encoded file contents
 * @param executable whether the file should be given executable permissions (mode 0700 vs 0600)
 */
public record ProfileFile(String path, String contentBase64, boolean executable) {

    @Override
    public String toString() {
        return "ProfileFile[path=" + path + ", executable=" + executable + "]";
    }
}
