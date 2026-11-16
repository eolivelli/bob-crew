package bobcrew.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where one bob-crew server is and how to reach it: its name, base URL, the token to present, and an
 * HTTP client that trusts the configured CA. Certificate and host name checks are always on.
 */
class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    private final String name;

    private final URI baseUri;

    private final String token;

    private final HttpClient client;

    ServerConnection(UiProperties properties) {
        this(null, properties.serverUrl(), properties.token(), properties.tokenFile(), properties.caFile());
    }

    ServerConnection(String name, String url, String token, String tokenFile, String caFile) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Server URL is not set for " + (name != null ? name : "server"));
        }
        URI uri = URI.create(url.strip().replaceAll("/+$", ""));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("Server URL must be an https URL, but is " + url);
        }
        this.baseUri = uri;
        this.name = name != null && !name.isBlank() ? name.strip() : uri.getHost();
        this.token = readToken(token, tokenFile, this.name);
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

    String token() {
        return token;
    }

    HttpClient client() {
        return client;
    }

    private static String readToken(String token, String tokenFile, String serverName) {
        if ((token == null || token.isBlank()) && tokenFile != null && !tokenFile.isBlank()) {
            try {
                token = Files.readString(Path.of(tokenFile));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read the token file " + tokenFile + " for server " + serverName + ": " + e, e);
            }
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("No API token for server " + serverName + ": set token or token-file");
        }
        token = token.strip();
        if (token.chars().anyMatch(c -> c <= ' ' || c > '~')) {
            throw new IllegalStateException("The API token for server " + serverName + " contains characters that cannot be sent in a header");
        }
        return token;
    }

    /** An SSL context that trusts exactly the certificates in the PEM file. */
    private static SSLContext trusting(Path caFile) {
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
