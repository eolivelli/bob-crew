package bobcrew.ui;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Settings under {@code bobcrew.ui.*}; see {@code application.yml}. */
@ConfigurationProperties("bobcrew.ui")
public record UiProperties(
        Map<String, ServerConfig> servers,
        String serverUrl,
        String token,
        String tokenFile,
        String caFile,
        String rolesDir,
        String secretsFile,
        Oidc oidc) {

    @ConstructorBinding
    public UiProperties {
    }

    public UiProperties(String serverUrl, String token, String tokenFile, String caFile) {
        this(null, serverUrl, token, tokenFile, caFile, null, null, null);
    }

    /**
     * Logging the user in with an OpenID Connect provider (Keycloak), for the servers that have no token of their
     * own ({@link OidcLogin}).
     *
     * @param issuerUri the realm's issuer, such as {@code https://keycloak.example:8543/realms/bobcrew}; empty for
     * no login
     * @param clientId the realm's client for UIs ({@code bobcrew-ui} by default)
     * @param caFile PEM certificates to trust for the provider's HTTPS instead of the JDK's (a private CA)
     */
    public record Oidc(String issuerUri, String clientId, String caFile) {

        boolean enabled() {
            return issuerUri != null && !issuerUri.isBlank();
        }
    }

    public record ServerConfig(
            String name,
            String url,
            String serverUrl,
            String token,
            String tokenFile,
            String caFile) {

        public ServerConfig(String url, String token, String tokenFile, String caFile) {
            this(null, url, null, token, tokenFile, caFile);
        }

        public String effectiveUrl() {
            return url != null && !url.isBlank() ? url : serverUrl;
        }

        @Override
        public String toString() {
            // never print the token
            return "ServerConfig[name=" + name + ", url=" + effectiveUrl() + ", caFile=" + caFile + "]";
        }
    }

    @Override
    public String toString() {
        // never print the token
        return "UiProperties[servers=" + (servers != null ? servers.keySet() : "none") + ", serverUrl=" + serverUrl + "]";
    }
}
