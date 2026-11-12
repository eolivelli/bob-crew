package bobcrew.server;

import java.io.PrintStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.convert.DurationStyle;

/**
 * {@code issue-token --user NAME [--ttl 30d] [--secret-file FILE] [--issuer I] [--audience A]}:
 * prints a token for the API. It runs without starting the server and needs read access to the
 * secret file, so issuing tokens is something only an operator of the server machine can do.
 */
final class IssueTokenCommand {

    static final String NAME = "issue-token";

    private IssueTokenCommand() {
    }

    /** Runs the command and returns the process exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) {
                return usage(err, "Unexpected argument: " + arg);
            }
            options.put(arg.substring(2), args[++i]);
        }
        String user = options.remove("user");
        String ttlText = options.getOrDefault("ttl", "30d");
        options.remove("ttl");
        String secretFile = options.containsKey("secret-file") ? options.remove("secret-file")
                : System.getenv("BOBCREW_JWT_SECRET_FILE");
        String issuer = options.containsKey("issuer") ? options.remove("issuer") : Tokens.DEFAULT_ISSUER;
        String audience = options.containsKey("audience") ? options.remove("audience") : Tokens.DEFAULT_AUDIENCE;
        if (!options.isEmpty()) {
            return usage(err, "Unknown option: --" + options.keySet().iterator().next());
        }
        if (user == null) {
            return usage(err, "--user is required");
        }
        try {
            Duration ttl = DurationStyle.detectAndParse(ttlText);
            byte[] secret = Tokens.readSecret(secretFile);
            out.println(Tokens.issue(secret, user, ttl, issuer, audience));
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream err, String problem) {
        err.println(problem);
        err.println("Usage: " + NAME + " --user NAME [--ttl 30d] [--secret-file FILE] [--issuer ISSUER] "
                + "[--audience AUDIENCE]");
        err.println("The secret file defaults to $BOBCREW_JWT_SECRET_FILE.");
        return 2;
    }
}
