package bobcrew.server;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code bobcrew.*}; see {@code application.yml} for what each one means. */
@ConfigurationProperties("bobcrew")
public record BobCrewProperties(
        Path workspaceRoot,
        @DefaultValue("8") int maxSessions,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("10m") Duration promptTimeout,
        @DefaultValue("2m") Duration permissionTimeout,
        @DefaultValue("12h") Duration idleTimeout,
        @DefaultValue("5000") int eventBufferSize,
        @DefaultValue("false") boolean fileDownloadsEnabled,
        @DefaultValue Agent agent,
        @DefaultValue Auth auth) {

    /** How the agent process is started. */
    public record Agent(
            @DefaultValue("bob") String binary,
            @DefaultValue({"acp", "--trust", "--accept-license", "--disable-mcp", "--disable-subagents"})
            List<String> arguments,
            String apiKey,
            @DefaultValue("false") boolean inheritEnvironment) {

        @Override
        public String toString() {
            // never print the API key
            return "Agent[binary=" + binary + ", arguments=" + arguments + ", apiKey="
                    + (apiKey == null || apiKey.isEmpty() ? "<none>" : "<set>") + "]";
        }
    }

    /** How API tokens are checked. */
    public record Auth(
            String jwtSecretFile,
            String revokedJtiFile,
            @DefaultValue(Tokens.DEFAULT_ISSUER) String issuer,
            @DefaultValue(Tokens.DEFAULT_AUDIENCE) String audience) {
    }
}
