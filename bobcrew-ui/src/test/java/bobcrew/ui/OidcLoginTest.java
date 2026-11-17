package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The login flow against a stand-in provider, with a clock the test moves. */
class OidcLoginTest {

    private final FakeOidcProvider provider;

    private final MutableClock clock = new MutableClock();

    private final OidcLogin login;

    OidcLoginTest() throws IOException {
        provider = new FakeOidcProvider();
        login = new OidcLogin(new UiProperties.Oidc(provider.issuer(), null, null), clock);
    }

    @AfterEach
    void stop() {
        provider.stop();
    }

    @Test
    void startsALoginWithStateAndAPkceChallenge() throws Exception {
        Map<String, String> query = query(login.start(8642).location());
        assertThat(query).containsEntry("response_type", "code")
            .containsEntry("client_id", "bobcrew-ui")
            .containsEntry("redirect_uri", "http://127.0.0.1:8642/login/callback")
            .containsEntry("scope", "openid")
            .containsEntry("code_challenge_method", "S256");
        assertThat(query.get("state")).hasSizeGreaterThanOrEqualTo(32);
        assertThat(query.get("code_challenge")).hasSize(43);
        assertThat(login.loggedIn()).isFalse();
    }

    @Test
    void finishesOnlyALoginItStartedAndOnlyOnce() throws Exception {
        Map<String, String> query = query(login.start(8642).location());
        String state = query.get("state");
        assertThat(login.finish("someone-elses-state", FakeOidcProvider.CODE, null)).isFalse();
        assertThat(login.finish(null, FakeOidcProvider.CODE, null)).isFalse();
        assertThat(login.loggedIn()).isFalse();

        assertThat(login.finish(state, FakeOidcProvider.CODE, null)).isTrue();
        assertThat(login.loggedIn()).isTrue();
        Map<String, String> exchange = provider.tokenRequests.get(0);
        assertThat(exchange).containsEntry("grant_type", "authorization_code")
            .containsEntry("code", FakeOidcProvider.CODE)
            .containsEntry("client_id", "bobcrew-ui")
            .containsEntry("redirect_uri", "http://127.0.0.1:8642/login/callback");
        assertThat(OidcLogin.challenge(exchange.get("code_verifier"))).isEqualTo(query.get("code_challenge"));
        assertThat(login.currentToken()).isEqualTo("access-1");

        assertThat(login.finish(state, FakeOidcProvider.CODE, null)).as("used up").isFalse();
    }

    @Test
    void refusesALateOrFailedLogin() throws Exception {
        String late = query(login.start(8642).location()).get("state");
        clock.advance(OidcLogin.LOGIN_TIMEOUT.plusSeconds(1));
        assertThat(login.finish(late, FakeOidcProvider.CODE, null)).isFalse();

        String denied = query(login.start(8642).location()).get("state");
        assertThat(login.finish(denied, null, "access_denied")).isFalse();

        String badCode = query(login.start(8642).location()).get("state");
        assertThat(login.finish(badCode, "stolen-code", null)).isFalse();
        assertThat(login.loggedIn()).isFalse();
    }

    @Test
    void refreshesTheTokenBeforeItExpiresAndEndsWhenTheProviderSaysSo() throws Exception {
        logIn();
        assertThat(login.currentToken()).isEqualTo("access-1");
        clock.advance(Duration.ofSeconds(300).minus(OidcLogin.REFRESH_MARGIN).plusSeconds(1));
        assertThat(login.currentToken()).isEqualTo("access-2");
        assertThat(provider.tokenRequests.get(1)).containsEntry("grant_type", "refresh_token")
            .containsEntry("refresh_token", "refresh-1");
        assertThat(login.currentToken()).as("fresh again").isEqualTo("access-2");

        provider.refuseRefresh = true;
        clock.advance(Duration.ofSeconds(300));
        assertThat(login.currentToken()).isNull();
        assertThat(login.loggedIn()).isFalse();
    }

    @Test
    void logsOutHereAndAtTheProvider() throws Exception {
        logIn();
        Map<String, String> query = query(login.logout(9000).location());
        assertThat(login.loggedIn()).isFalse();
        assertThat(login.currentToken()).isNull();
        assertThat(query).containsEntry("client_id", "bobcrew-ui")
            .containsEntry("id_token_hint", "id-1")
            .containsEntry("post_logout_redirect_uri", "http://127.0.0.1:9000/login/callback");
    }

    @Test
    void refusesAProviderThatIsNotTheConfiguredIssuer() {
        provider.claimedIssuer = "https://elsewhere.example/realms/test";
        assertThatThrownBy(() -> login.start(8642)).isInstanceOf(IOException.class)
            .hasMessageContaining("elsewhere.example");
    }

    @Test
    void needsHttpsExceptOnTheLoopback() {
        assertThatThrownBy(() -> new OidcLogin(new UiProperties.Oidc("http://keycloak.example/realms/x", null, null),
                Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
        assertThat(new OidcLogin(new UiProperties.Oidc(" ", null, null), Clock.systemUTC()).enabled()).isFalse();
    }

    private void logIn() throws Exception {
        String state = query(login.start(8642).location()).get("state");
        assertThat(login.finish(state, FakeOidcProvider.CODE, null)).isTrue();
    }

    private static Map<String, String> query(URI uri) {
        return FakeOidcProvider.parse(uri.getRawQuery());
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
