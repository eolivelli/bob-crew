package bobcrew.server;

import java.util.List;

/**
 * Who makes a request and what they may do on this server, decided from their token once per request
 * ({@link CallerFactory}) and passed to {@link SessionManager} with every call.
 *
 * @param name the user name: what owns sessions, what they are shared with, what events record
 * @param source where the token came from
 * @param allowed whether the user may use this server at all; a caller who may not gets 403 for everything
 * @param admin whether the user may see, use, share and close every session
 * @param capabilities the capabilities this user's sessions may ask for, sorted ({@link Capabilities})
 * @param limits this user's limits
 */
record Caller(String name, Source source, boolean allowed, boolean admin, List<String> capabilities, Limits limits) {

    enum Source {
        /** A token made with {@code issue-token}. */
        TOKEN,
        /** A token from the OpenID Connect provider (Keycloak). */
        OIDC
    }

    /**
     * @param maxSessions most open sessions at a time; 0 for no limit besides the server's total
     * @param maxMemoryBytes most memory one session may ask for, or null for no cap
     * @param maxCpus most CPUs one session may ask for, or null for no cap
     */
    record Limits(int maxSessions, Long maxMemoryBytes, Double maxCpus) {
    }

    Caller {
        capabilities = List.copyOf(capabilities);
    }
}
