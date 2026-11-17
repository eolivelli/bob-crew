package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.oauth2.jwt.Jwt;

class CallerFactoryTest {

    private static final long GB = 1L << 30;

    private final AtomicInteger hostQueries = new AtomicInteger();

    private CallerFactory factory(Map<String, String> settings) {
        Map<String, String> all = new HashMap<>(Map.of(
                "bobcrew.allowed-capabilities", "docker",
                "bobcrew.admin-users", "root-by-list",
                "bobcrew.max-sessions-per-user", "4",
                "bobcrew.docker.max-memory", "8GB",
                "bobcrew.docker.max-cpus", "2",
                "bobcrew.auth.oidc.issuer-uri", "https://keycloak.example/realms/bobcrew"));
        all.putAll(settings);
        BobCrewProperties properties = new Binder(new MapConfigurationPropertySource(all))
            .bind("bobcrew", BobCrewProperties.class)
            .get();
        return new CallerFactory(properties, () -> {
            hostQueries.incrementAndGet();
            return new DockerRuntime.HostResources(64 * GB, 16);
        });
    }

    private static Jwt oidc(String user, List<String> roles, Map<String, Object> claims) {
        Jwt.Builder jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("0f3c-uuid")
            .claim("preferred_username", user)
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(jwt::claim);
        return jwt.build();
    }

    @Test
    void aTokenFromIssueTokenGetsTheServerSettings() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("root-by-list").build();
        Caller caller = factory(Map.of()).fromToken(jwt);
        assertThat(caller).isEqualTo(new Caller("root-by-list", Caller.Source.TOKEN, true, true, List.of("docker"),
                new Caller.Limits(4, 8 * GB, 2.0)));
        assertThat(hostQueries).hasValue(0);
    }

    @Test
    void theIdentityProvidersRolesDecideAccessAdminAndCapabilities() {
        CallerFactory factory = factory(Map.of());
        Caller plain = factory.fromOidc(oidc("alice", List.of("bobcrew-user", "offline_access"), Map.of()));
        assertThat(plain.name()).isEqualTo("alice");
        assertThat(plain.source()).isEqualTo(Caller.Source.OIDC);
        assertThat(plain.allowed()).isTrue();
        assertThat(plain.admin()).isFalse();
        assertThat(plain.capabilities()).isEmpty();
        assertThat(plain.limits()).isEqualTo(new Caller.Limits(4, 8 * GB, 2.0));

        assertThat(factory.fromOidc(oidc("mallory", List.of("offline_access"), Map.of())).allowed()).isFalse();
        assertThat(factory.fromOidc(oidc("boss", List.of("bobcrew-user", "bobcrew-admin"), Map.of())).admin())
            .isTrue();
        assertThat(factory.fromOidc(oidc("root-by-list", List.of("bobcrew-user"), Map.of())).admin()).isTrue();

        // the server's list is the ceiling: libvirt is not allowed here, whatever the roles say
        Caller dave = factory.fromOidc(oidc("dave", List.of("bobcrew-user", "bobcrew-cap-docker",
                "bobcrew-cap-libvirt"), Map.of()));
        assertThat(dave.capabilities()).containsExactly("docker");
    }

    @Test
    void anEmptyAccessRoleLetsEveryoneIn() {
        CallerFactory factory = factory(Map.of("bobcrew.auth.oidc.access-role", ""));
        assertThat(factory.fromOidc(oidc("anyone", List.of(), Map.of())).allowed()).isTrue();
    }

    @Test
    void rolesAtAnotherPathOrMissing() {
        CallerFactory factory = factory(Map.of("bobcrew.auth.oidc.roles-claim", "groups"));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("preferred_username", "carol")
            .claim("groups", List.of("bobcrew-user")).build();
        assertThat(factory.fromOidc(jwt).allowed()).isTrue();
        assertThat(CallerFactory.roles(jwt, "realm_access.roles")).isEmpty();
        assertThat(CallerFactory.roles(jwt, "preferred_username")).isEmpty();
    }

    @Test
    void theUsersOwnLimitsApplyUpToTheCeilings() {
        CallerFactory factory = factory(Map.of());
        Caller dave = factory.fromOidc(oidc("dave", List.of("bobcrew-user"), Map.of(
                CallerFactory.MAX_SESSIONS, "10", CallerFactory.MAX_MEMORY, "16gb", CallerFactory.MAX_CPUS, "6.5")));
        assertThat(dave.limits()).isEqualTo(new Caller.Limits(10, 16 * GB, 6.5));

        // more than the Docker host has
        Caller greedy = factory.fromOidc(oidc("greedy", List.of("bobcrew-user"), Map.of(
                CallerFactory.MAX_MEMORY, "1TB", CallerFactory.MAX_CPUS, 99)));
        assertThat(greedy.limits()).isEqualTo(new Caller.Limits(4, 64 * GB, 16.0));
        assertThat(hostQueries).as("the host is asked once").hasValue(1);

        CallerFactory configured = factory(Map.of("bobcrew.limits.ceiling-memory", "32GB",
                "bobcrew.limits.ceiling-cpus", "8"));
        assertThat(configured.fromOidc(oidc("greedy", List.of("bobcrew-user"), Map.of(
                CallerFactory.MAX_MEMORY, "1TB", CallerFactory.MAX_CPUS, "99"))).limits())
            .isEqualTo(new Caller.Limits(4, 32 * GB, 8.0));
        assertThat(hostQueries).hasValue(1);
    }

    @Test
    void malformedLimitsAreIgnored() {
        Caller odd = factory(Map.of()).fromOidc(oidc("odd", List.of("bobcrew-user"), Map.of(
                CallerFactory.MAX_SESSIONS, "-1", CallerFactory.MAX_MEMORY, "lots", CallerFactory.MAX_CPUS, "NaN")));
        assertThat(odd.limits()).isEqualTo(new Caller.Limits(4, 8 * GB, 2.0));
        Caller tiny = factory(Map.of()).fromOidc(oidc("tiny", List.of("bobcrew-user"), Map.of(
                CallerFactory.MAX_MEMORY, "1MB", CallerFactory.MAX_CPUS, "0")));
        assertThat(tiny.limits()).isEqualTo(new Caller.Limits(4, 8 * GB, 2.0));
    }
}
