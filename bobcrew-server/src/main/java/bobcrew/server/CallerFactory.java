package bobcrew.server;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Turns a checked token into a {@link Caller}: who they are and what they may do here.
 *
 * <p>A token made with {@code issue-token} names its user in {@code sub}, gets every capability the server
 * allows and the server's limits, and is an admin if named in {@code bobcrew.admin-users}: as before there was an
 * identity provider.
 *
 * <p>A token from the OpenID Connect provider names its user in {@code bobcrew.auth.oidc.username-claim}, and its
 * roles decide the rest: without the access role the user may not use the server; the admin role (or being named
 * in {@code admin-users}) makes an admin; {@code <prefix><capability>} roles allow those capabilities, but only
 * those the server allows too, so that privileged containers stay the operator's decision on each machine. The
 * claims {@value #MAX_SESSIONS}, {@value #MAX_MEMORY} and {@value #MAX_CPUS} replace the server's per-user limits,
 * up to {@code bobcrew.limits} (by default the Docker host's memory and CPUs).
 */
@Component
class CallerFactory {

    private static final Logger log = LoggerFactory.getLogger(CallerFactory.class);

    static final String MAX_SESSIONS = "bobcrew_max_sessions";

    static final String MAX_MEMORY = "bobcrew_max_memory";

    static final String MAX_CPUS = "bobcrew_max_cpus";

    private final BobCrewProperties properties;

    private final Set<String> admins;

    private final List<String> serverCapabilities;

    private final Supplier<DockerRuntime.HostResources> host;

    private volatile Ceilings ceilings;

    private record Ceilings(long memoryBytes, double cpus) {
    }

    @Autowired
    CallerFactory(BobCrewProperties properties, DockerRuntime docker) {
        this(properties, docker::hostResources);
    }

    CallerFactory(BobCrewProperties properties, Supplier<DockerRuntime.HostResources> host) {
        this.properties = properties;
        this.host = host;
        List<String> configured = properties.adminUsers() == null ? List.of() : properties.adminUsers();
        this.admins = configured.stream()
            .map(name -> name == null ? "" : name.strip())
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        this.serverCapabilities = Capabilities.allowed(properties.allowedCapabilities());
    }

    /** A caller with a token from {@code issue-token}. */
    Caller fromToken(Jwt jwt) {
        String name = jwt.getSubject();
        return new Caller(name, Caller.Source.TOKEN, true, admins.contains(name), serverCapabilities, serverLimits());
    }

    /** A caller with a token from the OpenID Connect provider, already checked (signature, issuer, audience). */
    Caller fromOidc(Jwt jwt) {
        BobCrewProperties.Oidc oidc = properties.auth().oidc();
        String name = jwt.getClaimAsString(oidc.usernameClaim());
        Set<String> roles = roles(jwt, oidc.rolesClaim());
        boolean allowed = blank(oidc.accessRole()) || roles.contains(oidc.accessRole());
        boolean admin = admins.contains(name) || (!blank(oidc.adminRole()) && roles.contains(oidc.adminRole()));
        List<String> capabilities = serverCapabilities.stream()
            .filter(capability -> roles.contains(oidc.capabilityRolePrefix() + capability))
            .toList();
        Caller.Limits server = serverLimits();
        Caller.Limits limits = new Caller.Limits(
                maxSessions(jwt, name, server.maxSessions()),
                maxMemory(jwt, name, server.maxMemoryBytes()),
                maxCpus(jwt, name, server.maxCpus()));
        return new Caller(name, Caller.Source.OIDC, allowed, admin, capabilities, limits);
    }

    private Caller.Limits serverLimits() {
        BobCrewProperties.Docker docker = properties.docker();
        Long memory = docker.maxMemory() == null || docker.maxMemory().toBytes() <= 0 ? null
                : docker.maxMemory().toBytes();
        Double cpus = docker.maxCpus() > 0 ? docker.maxCpus() : null;
        return new Caller.Limits(Math.max(0, properties.maxSessionsPerUser()), memory, cpus);
    }

    /**
     * The roles at a dotted path in the claims ({@code realm_access.roles}: Keycloak's realm roles), or none.
     */
    static Set<String> roles(Jwt jwt, String path) {
        Object node = jwt.getClaims();
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return Set.of();
            }
            node = map.get(part);
        }
        if (!(node instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> roles = new TreeSet<>();
        for (Object value : values) {
            if (value instanceof String role) {
                roles.add(role);
            }
        }
        return roles;
    }

    private int maxSessions(Jwt jwt, String user, int fallback) {
        String value = claim(jwt, MAX_SESSIONS);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        warnMalformed(user, MAX_SESSIONS);
        return fallback;
    }

    private Long maxMemory(Jwt jwt, String user, Long fallback) {
        String value = claim(jwt, MAX_MEMORY);
        if (value == null) {
            return fallback;
        }
        long bytes;
        try {
            bytes = DataSize.parse(value.replace(" ", "").toUpperCase(Locale.ROOT)).toBytes();
        } catch (IllegalArgumentException e) {
            warnMalformed(user, MAX_MEMORY);
            return fallback;
        }
        if (bytes < Resources.MIN_MEMORY) {
            warnMalformed(user, MAX_MEMORY);
            return fallback;
        }
        long ceiling = ceilings().memoryBytes();
        return ceiling > 0 ? Math.min(bytes, ceiling) : bytes;
    }

    private Double maxCpus(Jwt jwt, String user, Double fallback) {
        String value = claim(jwt, MAX_CPUS);
        if (value == null) {
            return fallback;
        }
        double cpus;
        try {
            cpus = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            warnMalformed(user, MAX_CPUS);
            return fallback;
        }
        if (Double.isNaN(cpus) || cpus <= 0 || cpus > Resources.MAX_CPUS) {
            warnMalformed(user, MAX_CPUS);
            return fallback;
        }
        double ceiling = ceilings().cpus();
        return ceiling > 0 ? Math.min(cpus, ceiling) : cpus;
    }

    /** A claim as a string; Keycloak's attribute mappers send strings, but a number is fine too. */
    private static String claim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        if (value instanceof Collection<?> list) {
            value = list.isEmpty() ? null : list.iterator().next();
        }
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static void warnMalformed(String user, String claim) {
        // the value itself stays out of the log: it is the identity provider's data, and could be anything
        log.warn("Ignoring the malformed {} claim in {}'s token; the server's limit applies", claim, user);
    }

    private Ceilings ceilings() {
        Ceilings known = ceilings;
        if (known == null) {
            BobCrewProperties.Limits configured = properties.limits();
            long memory = configured.ceilingMemory() == null ? 0 : configured.ceilingMemory().toBytes();
            double cpus = configured.ceilingCpus();
            if (memory <= 0 || cpus <= 0) {
                DockerRuntime.HostResources resources = host.get();
                memory = memory > 0 ? memory : resources.memoryBytes();
                cpus = cpus > 0 ? cpus : resources.cpus();
            }
            known = new Ceilings(memory, cpus);
            ceilings = known;
        }
        return known;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
