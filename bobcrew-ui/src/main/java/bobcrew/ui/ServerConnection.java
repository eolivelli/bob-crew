package bobcrew.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where one bob-crew server is and how to reach it: its name, base URL, the token to present (configured, or
 * none when the user logs in, {@link OidcLogin}), and an HTTP client that trusts the configured CA. Certificate and
 * host name checks are always on.
 */
class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    private final String name;

    private final URI baseUri;

    private final String token;

    private final HttpClient client;

    ServerConnection(UiProperties properties) {
        this(null, properties.serverUrl(), properties.token(), properties.tokenFile(), properties.caFile(), false);
    }

    /** @param loginInstead whether a missing token is fine: the user's login provides one */
    ServerConnection(String name, String url, String token, String tokenFile, String caFile, boolean loginInstead) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Server URL is not set for " + (name != null ? name : "server"));
        }
        URI uri = URI.create(url.strip().replaceAll("/+$", ""));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("Server URL must be an https URL, but is " + url);
        }
        this.baseUri = uri;
        this.name = name != null && !name.isBlank() ? name.strip() : uri.getHost();
        this.token = readToken(token, tokenFile, this.name, loginInstead);
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER);
        if (caFile != null && !caFile.isBlank()) {
            builder.sslContext(trusting(Path.of(caFile)));
        }
        this.client = builder.build();
        log.info("Registered server '{}' at {}", this.name, baseUri);
    }

    String name() {
        return name;
    }

    URI baseUri() {
        return baseUri;
    }

    /** The configured token, or null if the user's login provides it. */
    String token() {
        return token;
    }

    HttpClient client() {
        return client;
    }

    private static String readToken(String token, String tokenFile, String serverName, boolean loginInstead) {
        if ((token == null || token.isBlank()) && tokenFile != null && !tokenFile.isBlank()) {
            if (readableByOthers(Path.of(tokenFile))) {
                // any other user of this machine could act as you on the server
                log.warn("The token file {} for server {} is readable by other users; restrict it with chmod 600",
                        tokenFile, serverName);
            }
            try {
                token = Files.readString(Path.of(tokenFile));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read the token file " + tokenFile + " for server " + serverName + ": " + e, e);
            }
        }
        if (token == null || token.isBlank()) {
            if (loginInstead) {
                return null;
            }
            throw new IllegalStateException("No API token for server " + serverName + ": set token or token-file, "
                    + "or bobcrew.ui.oidc.issuer-uri for users to log in");
        }
        token = token.strip();
        if (token.chars().anyMatch(c -> c <= ' ' || c > '~')) {
            throw new IllegalStateException("The API token for server " + serverName + " contains characters that cannot be sent in a header");
        }
        return token;
    }

    /** Whether users other than the owner can read the file; false where POSIX permissions do not apply. */
    static boolean readableByOthers(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            return permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /** An SSL context that trusts exactly the certificates in the PEM file. */
    static SSLContext trusting(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trustStore.setCertificateEntry("ca-" + index++, certificate);
            }
            if (index == 0) {
                throw new IllegalStateException("No certificate found in " + caFile);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load the CA file " + caFile + ": " + e, e);
        }
    }
}
