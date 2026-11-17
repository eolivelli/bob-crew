package bobcrew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Loads files from {@code ~/.bobcrew/user-profile} (or {@code bobcrew.ui.user-profile-dir}) to be written into the
 * container user's home directory (/home/bob) when creating a session.
 */
@Component
class UserProfileStore {

    private static final Logger log = LoggerFactory.getLogger(UserProfileStore.class);

    private static final int MAX_FILES = 64;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_TOTAL_BYTES = 1024 * 1024;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private final Path directory;

    @Autowired
    UserProfileStore(UiProperties properties) {
        this(resolveDirectory(properties));
    }

    UserProfileStore(Path directory) {
        this.directory = directory;
    }

    private static Path resolveDirectory(UiProperties properties) {
        return properties != null && properties.userProfileDir() != null && !properties.userProfileDir().isBlank()
                ? Path.of(properties.userProfileDir())
                : Path.of(System.getProperty("user.home"), ".bobcrew", "user-profile");
    }

    /**
     * Reads all regular files from the user profile directory. Returns an empty list if the directory does not exist.
     *
     * @throws IllegalStateException with a user-facing error message if the directory or files violate security or size limits
     */
    List<ProfileFile> load() {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The user-profile path " + directory + " is not a directory");
        }
        if (ServerConnection.readableByOthers(directory)) {
            throw new IllegalStateException("The user-profile directory " + directory
                    + " can be read by other users of this computer; restrict it with chmod 700 and try again");
        }
        List<ProfileFile> files = new ArrayList<>();
        long[] totalBytes = new long[1];
        try {
            java.nio.file.Files.walkFileTree(directory, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 16,
                    new java.nio.file.SimpleFileVisitor<Path>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                            if (attrs.isSymbolicLink() || attrs.isOther()) {
                                log.warn("user-profile: skipping non-regular file or symlink {}", directory.relativize(path));
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                            if (!attrs.isRegularFile()) {
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                            if (files.size() >= MAX_FILES) {
                                throw new IllegalStateException("Too many files in " + directory + " (at most " + MAX_FILES + ")");
                            }
                            String rel = directory.relativize(path).toString().replace('\\', '/');
                            if (attrs.size() > MAX_FILE_BYTES) {
                                throw new IllegalStateException("The user-profile file " + rel + " is larger than 256 KiB");
                            }
                            byte[] bytes;
                            try {
                                bytes = Files.readAllBytes(path);
                            } catch (IOException e) {
                                throw new IllegalStateException("Cannot read user-profile file " + rel + ": " + e.getMessage());
                            }
                            totalBytes[0] += bytes.length;
                            if (totalBytes[0] > MAX_TOTAL_BYTES) {
                                throw new IllegalStateException("Total size of files in " + directory + " exceeds 1 MB");
                            }
                            boolean executable = !IS_WINDOWS && Files.isExecutable(path);
                            files.add(new ProfileFile(rel, Base64.getEncoder().encodeToString(bytes), executable));
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                            throw new IllegalStateException("Cannot read user-profile file or directory "
                                    + directory.relativize(file) + ": " + exc.getMessage());
                        }
                    });
        } catch (java.io.UncheckedIOException e) {
            throw new IllegalStateException("Cannot read user-profile directory " + directory + ": " + e.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read user-profile directory " + directory + ": " + e.getMessage());
        }
        files.sort(java.util.Comparator.comparing(ProfileFile::path));
        return List.copyOf(files);
    }
}
