package bobcrew.server;

import org.springframework.stereotype.Component;

/**
 * Decides who may see and drive a session. For now every authenticated user may use every
 * session; the owner is recorded so that this can be narrowed here, in one place.
 */
@Component
class SessionAccessPolicy {

    boolean canAccess(String user, AgentSession session) {
        return true;
    }
}
