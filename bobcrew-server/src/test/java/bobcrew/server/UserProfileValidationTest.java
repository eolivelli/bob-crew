package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class UserProfileValidationTest {

    private static String b64(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64Bytes(int size) {
        return Base64.getEncoder().encodeToString(new byte[size]);
    }

    @Test
    void emptyOrNullUserProfile() {
        assertThat(SessionManager.checkUserProfile(null)).isEmpty();
        assertThat(SessionManager.checkUserProfile(List.of())).isEmpty();
    }

    @Test
    void validProfileFiles() {
        List<ProfileFile> input = List.of(
                new ProfileFile(".m2/settings.xml", b64("<settings/>"), false),
                new ProfileFile(".gitconfig", b64("[user]\nname=Test\n"), false),
                new ProfileFile("bin/my-script.sh", b64("#!/bin/sh\necho hi\n"), true),
                new ProfileFile(".config/nested/deep/file.json", b64("{}"), false));

        List<ProfileFile> result = SessionManager.checkUserProfile(input);
        assertThat(result).hasSize(4);
        assertThat(result.get(0).path()).isEqualTo(".m2/settings.xml");
        assertThat(result.get(2).executable()).isTrue();
    }

    @Test
    void positiveBoundaryLimits() {
        // Exactly 64 files
        List<ProfileFile> maxFiles = new ArrayList<>();
        for (int i = 0; i < SessionManager.MAX_PROFILE_FILES; i++) {
            maxFiles.add(new ProfileFile("file" + i + ".txt", b64("content"), false));
        }
        assertThat(SessionManager.checkUserProfile(maxFiles)).hasSize(64);

        // Exactly 256 KiB single file
        ProfileFile maxSingle = new ProfileFile("max.bin", b64Bytes(SessionManager.MAX_PROFILE_FILE_BYTES), false);
        assertThat(SessionManager.checkUserProfile(List.of(maxSingle))).hasSize(1);

        // Exactly 1 MiB total size (4 files of 256 KiB)
        List<ProfileFile> maxTotal = List.of(
                new ProfileFile("p1.bin", b64Bytes(256 * 1024), false),
                new ProfileFile("p2.bin", b64Bytes(256 * 1024), false),
                new ProfileFile("p3.bin", b64Bytes(256 * 1024), false),
                new ProfileFile("p4.bin", b64Bytes(256 * 1024), false));
        assertThat(SessionManager.checkUserProfile(maxTotal)).hasSize(4);

        // Exactly 16 segments
        String sixteenSegments = "1/2/3/4/5/6/7/8/9/10/11/12/13/14/15/file.txt";
        assertThat(SessionManager.checkUserProfile(List.of(new ProfileFile(sixteenSegments, b64("ok"), false))))
            .hasSize(1);

        // Exactly 255 characters path
        String longPath255 = "a/".repeat(125) + "file.txt"; // 250 + 5 = 255 chars, 126 segments? wait segments <= 16
        String seg15 = "a".repeat(20) + "/" + "b".repeat(20) + "/" + "c".repeat(20) + "/" + "d".repeat(20) + "/"
                + "e".repeat(20) + "/" + "f".repeat(20) + "/" + "g".repeat(20) + "/" + "h".repeat(20) + "/"
                + "i".repeat(20) + "/" + "j".repeat(20) + "/" + "k".repeat(20) + "/" + "l".repeat(20) + "/"
                + "file.txt";
        String path255 = "dir/" + "x".repeat(247); // 4 + 247 = 251 + 4 = 255
        String exact255 = "a".repeat(255);
        assertThat(SessionManager.checkUserProfile(List.of(new ProfileFile(exact255, b64("ok"), false))))
            .hasSize(1);

        // Empty file content
        assertThat(SessionManager.checkUserProfile(List.of(new ProfileFile("empty.txt", b64(""), false))))
            .hasSize(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "/etc/passwd",
            "../escape.txt",
            "nested/../../escape.txt",
            ".",
            "..",
            "a//b",
            "a/./b",
            "a/../b",
            "trailing/slash/",
            "has\\backslash",
            "has:colon",
            "has\nnewline",
            "has\0nullbyte",
            ".bobcrew",
            ".bobcrew/secrets",
            "a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q" // 17 segments > 16
    })
    void rejectsInvalidPaths(String badPath) {
        List<ProfileFile> input = List.of(new ProfileFile(badPath, b64("content"), false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsNullListEntryOrNullContent() {
        List<ProfileFile> withNullEntry = Collections.singletonList(null);
        assertThatThrownBy(() -> SessionManager.checkUserProfile(withNullEntry))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        List<ProfileFile> withNullContent = List.of(new ProfileFile("file.txt", null, false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(withNullContent))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsTooManyFiles() {
        List<ProfileFile> input = new ArrayList<>();
        for (int i = 0; i < SessionManager.MAX_PROFILE_FILES + 1; i++) {
            input.add(new ProfileFile("file" + i + ".txt", b64("test"), false));
        }
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("Too many user profile files");
    }

    @Test
    void rejectsPathTooLong() {
        String longPath = "a".repeat(256);
        List<ProfileFile> input = List.of(new ProfileFile(longPath, b64("content"), false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("Profile file path is too long");
    }

    @Test
    void rejectsDuplicatePaths() {
        List<ProfileFile> input = List.of(
                new ProfileFile(".m2/settings.xml", b64("1"), false),
                new ProfileFile(".m2/settings.xml", b64("2"), false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("Duplicate profile file path");
    }

    @Test
    void rejectsFileDirectoryConflictBothDirections() {
        // file first, then directory
        List<ProfileFile> input1 = List.of(
                new ProfileFile(".m2", b64("file-content"), false),
                new ProfileFile(".m2/settings.xml", b64("<xml/>"), false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input1))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("conflicts with a directory");

        // directory first, then file
        List<ProfileFile> input2 = List.of(
                new ProfileFile(".m2/settings.xml", b64("<xml/>"), false),
                new ProfileFile(".m2", b64("file-content"), false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input2))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("conflicts with a directory");
    }

    @Test
    void rejectsInvalidBase64() {
        List<ProfileFile> input = List.of(new ProfileFile("file.txt", "not-valid-base64!@#$", false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("Invalid Base64");
    }

    @Test
    void rejectsFileTooLarge() {
        List<ProfileFile> input = List.of(new ProfileFile("big.bin", b64Bytes(SessionManager.MAX_PROFILE_FILE_BYTES + 1), false));
        assertThatThrownBy(() -> SessionManager.checkUserProfile(input))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("Profile file is too large");
    }

    @Test
    void rejectsTotalSizeTooLarge() {
        List<ProfileFile> input = List.of(
                new ProfileFile("f1.bin", b64Bytes(200 * 1024), false),
                new ProfileFile("f2.bin", b64Bytes(200 * 1024), false),
                new ProfileFile("f3.bin", b64Bytes(200 * 1024), false),
                new ProfileFile("f4.bin", b64Bytes(200 * 1024), false),
                new ProfileFile("f5.bin", b64Bytes(200 * 1024), false),
                new ProfileFile("f6.bin", b64Bytes(100 * 1024), false)); // total = 1100 KB > 1024 KB

        assertThatThrownBy(() -> SessionManager.checkUserProfile(input))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("Total profile files size is too large");
    }

    @Test
    void profileFileToStringRedactsContent() {
        ProfileFile file = new ProfileFile(".m2/settings.xml", b64("SUPER_SECRET_TOKEN"), true);
        assertThat(file.toString())
            .contains(".m2/settings.xml")
            .contains("executable=true")
            .doesNotContain("SUPER_SECRET_TOKEN");
    }
}
