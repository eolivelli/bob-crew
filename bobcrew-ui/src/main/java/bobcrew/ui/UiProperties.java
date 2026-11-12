package bobcrew.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code bobcrew.ui.*}; see {@code application.yml}. */
@ConfigurationProperties("bobcrew.ui")
public record UiProperties(String serverUrl, String token, String tokenFile, String caFile) {

    @Override
    public String toString() {
        // never print the token
        return "UiProperties[serverUrl=" + serverUrl + ", caFile=" + caFile + "]";
    }
}
