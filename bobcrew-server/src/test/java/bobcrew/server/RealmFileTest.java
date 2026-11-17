package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import bobcrew.testsupport.TestKeycloak;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code keycloak/bobcrew-realm.json} in step with what the server expects of its tokens: the roles it looks
 * for, the audience, and the limit claims. Needs no Keycloak ({@link OidcIntegrationTest} imports the file into a
 * real one).
 */
class RealmFileTest {

    private final JsonNode realm;

    RealmFileTest() throws Exception {
        realm = JsonMapper.builder().build().readTree(Files.readString(TestKeycloak.realmFile()));
    }

    @Test
    void definesTheRolesTheServerLooksFor() throws Exception {
        BobCrewProperties.Oidc defaults = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        java.util.Map.of("bobcrew.auth.oidc.issuer-uri", "x")))
            .bind("bobcrew.auth.oidc", BobCrewProperties.Oidc.class)
            .get();
        List<String> expected = new ArrayList<>(List.of(defaults.accessRole(), defaults.adminRole()));
        Capabilities.KNOWN.forEach(capability -> expected.add(defaults.capabilityRolePrefix() + capability));
        assertThat(TestKeycloak.realmRoles()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(defaults.rolesClaim()).isEqualTo("realm_access.roles");
        assertThat(defaults.usernameClaim()).isEqualTo("preferred_username");
    }

    @Test
    void theUiClientIsPublicWithPkceAndNoPasswordGrant() {
        JsonNode ui = client("bobcrew-ui");
        assertThat(ui.get("publicClient").asBoolean()).isTrue();
        assertThat(ui.get("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(ui.get("directAccessGrantsEnabled").asBoolean()).isFalse();
        assertThat(ui.get("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(ui.get("attributes").get("pkce.code.challenge.method").asString()).isEqualTo("S256");
        // a loopback address without a port: Keycloak then accepts any port (RFC 8252), so UIs can use any port
        assertThat(ui.get("redirectUris").get(0).asString()).isEqualTo("http://127.0.0.1/login/callback");
        assertThat(ui.get("redirectUris")).hasSize(1);
    }

    @Test
    void tokensCarryOurAudienceAndTheLimitClaims() {
        List<String> claims = new ArrayList<>();
        String audience = null;
        for (JsonNode mapper : client("bobcrew-ui").get("protocolMappers")) {
            JsonNode config = mapper.get("config");
            if (mapper.get("protocolMapper").asString().equals("oidc-audience-mapper")) {
                audience = config.get("included.custom.audience").asString();
            } else {
                claims.add(config.get("claim.name").asString());
                assertThat(config.get("user.attribute").asString()).isEqualTo(config.get("claim.name").asString());
            }
            assertThat(config.get("access.token.claim").asString()).isEqualTo("true");
        }
        assertThat(audience).isEqualTo(Tokens.DEFAULT_AUDIENCE);
        assertThat(claims).containsExactlyInAnyOrder(CallerFactory.MAX_SESSIONS, CallerFactory.MAX_MEMORY,
                CallerFactory.MAX_CPUS);
        // the user profile must declare them, or Keycloak drops the attributes
        String profile = realm.get("components").get("org.keycloak.userprofile.UserProfileProvider").get(0)
            .get("config").get("kc.user.profile.config").get(0).asString();
        assertThat(profile).contains(CallerFactory.MAX_SESSIONS, CallerFactory.MAX_MEMORY, CallerFactory.MAX_CPUS);
    }

    @Test
    void theDeployScriptRunsTheVersionTheRealmIsTestedWith() throws Exception {
        String script = Files.readString(TestKeycloak.realmFile().getParent().resolveSibling("scripts")
            .resolve("deploy-keycloak.sh"));
        assertThat(script).contains("KEYCLOAK_IMAGE=\"" + TestKeycloak.IMAGE + "\"");
    }

    private JsonNode client(String id) {
        for (JsonNode client : realm.get("clients")) {
            if (client.get("clientId").asString().equals(id)) {
                return client;
            }
        }
        throw new AssertionError("no client " + id);
    }
}
