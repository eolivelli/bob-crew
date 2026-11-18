package bobcrew.server;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Settings under {@code bobcrew.*}; see {@code application.yml} for what each one means. */
@ConfigurationProperties("bobcrew")
public record BobCrewProperties(
        @DefaultValue("8") int maxSessions,
        @DefaultValue("4") int maxSessionsPerUser,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("0") Duration promptTimeout,
        @DefaultValue("2m") Duration permissionTimeout,
        @DefaultValue("12h") Duration idleTimeout,
        @DefaultValue("5000") int eventBufferSize,
        @DefaultValue("false") boolean fileDownloadsEnabled,
        @DefaultValue("true") boolean fileUploadsEnabled,
        @DefaultValue("200MB") DataSize maxUploadSize,
        @DefaultValue Agent agent,
        @DefaultValue Docker docker,
        @DefaultValue Git git,
        List<String> allowedCapabilities,
        List<String> adminUsers,
        @DefaultValue Auth auth,
        @DefaultValue Limits limits) {

    /** What runs inside a session's container. */
    public record Agent(
            @DefaultValue({"bob", "acp", "--trust", "--accept-license", "--disable-mcp", "--disable-subagents"})
            List<String> command,
            String apiKey) {

        @Override
        public String toString() {
            // never print the API key
            return "Agent[command=" + command + ", apiKey="
                    + (apiKey == null || apiKey.isEmpty() ? "<none>" : "<set>") + "]";
        }
    }

    /** How session containers are created. */
    public record Docker(
            @DefaultValue("unix:///var/run/docker.sock") String host,
            @DefaultValue("1.44") String apiVersion,
            @DefaultValue("default") String instanceId,
            @DefaultValue("bobcrew/bob-base:latest") String defaultImage,
            @DefaultValue("1000:1000") String user,
            @DefaultValue("8GB") DataSize memoryLimit,
            @DefaultValue("0") double cpus,
            @DefaultValue("4096") long pidsLimit,
            DataSize maxMemory,
            @DefaultValue("0") double maxCpus,
            @DefaultValue(DockerRuntime.DEFAULT_NETWORK) String network,
            @DefaultValue("5s") Duration stopTimeout,
            @DefaultValue("32") int maxEnvVars,
            @DefaultValue("32768") int maxEnvValueLength,
            @DefaultValue("bobcrew/*") List<String> allowedImages) {
    }

    /** Cloning a repository into a new session's workspace. */
    public record Git(
            @DefaultValue("10m") Duration cloneTimeout,
            @DefaultValue("https") List<String> allowedSchemes) {
    }

    /**
     * How API tokens are checked. Two kinds can be trusted, each when configured: tokens this project issues
     * ({@code issue-token}, HS256 with {@code jwtSecretFile}), and tokens from an OpenID Connect provider such as
     * Keycloak ({@link Oidc}).
     */
    public record Auth(
            String jwtSecretFile,
            String revokedJtiFile,
            @DefaultValue(Tokens.DEFAULT_ISSUER) String issuer,
            @DefaultValue(Tokens.DEFAULT_AUDIENCE) String audience,
            @DefaultValue Oidc oidc) {
    }

    /**
     * Tokens from an OpenID Connect provider (Keycloak): checked with the provider's published keys, the user
     * named by {@code usernameClaim}, and roles (from {@code rolesClaim}, a dotted path) deciding who may use the
     * server at all, who is an admin and which capabilities each user may ask for.
     *
     * @param issuerUri the realm's issuer, such as {@code https://keycloak:8543/realms/bobcrew}; empty to trust
     * no provider
     * @param caFile PEM certificates to trust for the provider's HTTPS instead of the JDK's (a private CA); empty for
     * the JDK's
     */
    public record Oidc(
            String issuerUri,
            String caFile,
            @DefaultValue(Tokens.DEFAULT_AUDIENCE) String audience,
            @DefaultValue("preferred_username") String usernameClaim,
            @DefaultValue("realm_access.roles") String rolesClaim,
            @DefaultValue("bobcrew-user") String accessRole,
            @DefaultValue("bobcrew-admin") String adminRole,
            @DefaultValue("bobcrew-cap-") String capabilityRolePrefix) {

        boolean enabled() {
            return issuerUri != null && !issuerUri.isBlank();
        }
    }

    /**
     * Hard limits on what a user's own limits (from their identity provider) may raise a session to; empty for the
     * Docker host's total memory and CPUs.
     */
    public record Limits(DataSize ceilingMemory, @DefaultValue("0") double ceilingCpus) {
    }
}
