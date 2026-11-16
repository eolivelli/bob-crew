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
        @DefaultValue("10m") Duration promptTimeout,
        @DefaultValue("2m") Duration permissionTimeout,
        @DefaultValue("12h") Duration idleTimeout,
        @DefaultValue("5000") int eventBufferSize,
        @DefaultValue("false") boolean fileDownloadsEnabled,
        @DefaultValue("true") boolean fileUploadsEnabled,
        @DefaultValue("100MB") DataSize maxUploadSize,
        @DefaultValue Agent agent,
        @DefaultValue Docker docker,
        @DefaultValue Git git,
        List<String> allowedCapabilities,
        @DefaultValue Auth auth) {

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

    /** How API tokens are checked. */
    public record Auth(
            String jwtSecretFile,
            String revokedJtiFile,
            @DefaultValue(Tokens.DEFAULT_ISSUER) String issuer,
            @DefaultValue(Tokens.DEFAULT_AUDIENCE) String audience) {
    }
}
