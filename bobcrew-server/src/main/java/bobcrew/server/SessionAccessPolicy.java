package bobcrew.server;

import org.springframework.stereotype.Component;

/**
 * Decides who may see and drive a session, in this one place. A session belongs to the user who created it; its
 * owner can share it with other users by name, who may then do everything with it but close it or change who it
 * is shared with. Admins ({@link Caller#admin()}: named in {@code bobcrew.admin-users}, or with the identity
 * provider's admin role) may do everything with every session.
 *
 * <p>Using a session means a lot: its agent holds whatever secrets the session was given (a {@code GH_TOKEN}, for
 * one), runs commands with them, and anyone who can prompt it can also answer its permission requests. So
 * nobody gets this by default.
 */
@Component
class SessionAccessPolicy {

    /** Whether {@code caller} may see the session and do anything with it short of closing or re-sharing it. */
    boolean canUse(Caller caller, AgentSession session) {
        return canManage(caller, session) || session.sharedWith().contains(caller.name());
    }

    /** Whether {@code caller} may close the session and decide who it is shared with. */
    boolean canManage(Caller caller, AgentSession session) {
        return session.owner().equals(caller.name()) || caller.admin();
    }

    /**
     * Whether {@code caller} may read an archived session's transcript: those who could use the session when it
     * was archived (its owner, the users it was shared with then) and admins.
     */
    boolean canRead(Caller caller, SessionArchive.Entry archived) {
        return canDelete(caller, archived) || archived.sharedWith().contains(caller.name());
    }

    /** Whether {@code caller} may delete an archived session: its owner and admins, as for closing it. */
    boolean canDelete(Caller caller, SessionArchive.Entry archived) {
        return archived.owner().equals(caller.name()) || caller.admin();
    }
}
