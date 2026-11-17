package bobcrew.server;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A request's authentication: its checked token and the {@link Caller} made from it. The only authority is
 * {@value #USE}, which callers allowed to use the server have; every endpoint requires it.
 */
final class CallerAuthentication extends AbstractAuthenticationToken {

    static final String USE = "BOBCREW_USE";

    private final transient Jwt jwt;

    private final Caller caller;

    CallerAuthentication(Jwt jwt, Caller caller) {
        super(caller.allowed() ? List.of(new SimpleGrantedAuthority(USE)) : List.of());
        this.jwt = jwt;
        this.caller = caller;
        setAuthenticated(true);
    }

    /** The token; never logged (the base class's {@code toString} does not show credentials). */
    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public Caller getPrincipal() {
        return caller;
    }

    @Override
    public String getName() {
        return caller.name();
    }
}
