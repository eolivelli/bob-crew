package bobcrew.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Reads the signing secret and issues the HS256 tokens that the API accepts. */
public final class Tokens {

    public static final String DEFAULT_ISSUER = "bobcrew";

    public static final String DEFAULT_AUDIENCE = "bobcrew-api";

    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;

    private Tokens() {
    }

    /**
     * Reads the secret: the content of the file without surrounding whitespace, as UTF-8 bytes.
     * @throws IllegalStateException if the file is not configured, unreadable or too short
     */
    public static byte[] readSecret(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("No JWT secret file configured: set bobcrew.auth.jwt-secret-file "
                    + "(or BOBCREW_JWT_SECRET_FILE), for example to a file made with `openssl rand -base64 48`");
        }
        Path path = Path.of(file);
        byte[] secret;
        try {
            secret = Files.readString(path).strip().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the JWT secret file " + path + ": " + e, e);
        }
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("The JWT secret in " + path + " has " + secret.length
                    + " bytes; at least " + MIN_SECRET_BYTES + " are required");
        }
        return secret;
    }

    /** Whether users other than the owner can read the file; false where POSIX permissions do not apply. */
    public static boolean readableByOthers(String file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(Path.of(file));
            return permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /** Issues a signed token for {@code user} that expires after {@code ttl}. */
    public static String issue(byte[] secret, String user, Duration ttl, String issuer, String audience) {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("The user name must not be empty");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("The time to live must be positive");
        }
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(user)
            .issuer(issuer)
            .audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the token: " + e.getMessage(), e);
        }
        return jwt.serialize();
    }
}
