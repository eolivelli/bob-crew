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
        String caFile) {

    @ConstructorBinding
    public UiProperties {
    }

    public UiProperties(String serverUrl, String token, String tokenFile, String caFile) {
        this(null, serverUrl, token, tokenFile, caFile);
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
