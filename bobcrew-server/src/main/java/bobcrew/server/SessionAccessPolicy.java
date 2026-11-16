package bobcrew.server;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Decides who may see and drive a session, in this one place. A session belongs to the user who created it; its
 * owner can share it with other users by name, who may then do everything with it but close it or change who it
 * is shared with. The users named in {@code bobcrew.admin-users} may do everything with every session.
 *
 * <p>Using a session means a lot: its agent holds whatever secrets the session was given (a {@code GH_TOKEN}, for
 * one), runs commands with them, and anyone who can prompt it can also answer its permission requests. So
 * nobody gets this by default.
 */
@Component
class SessionAccessPolicy {

    private final Set<String> admins;

    SessionAccessPolicy(BobCrewProperties properties) {
        List<String> configured = properties.adminUsers() == null ? List.of() : properties.adminUsers();
        this.admins = configured.stream()
            .map(name -> name == null ? "" : name.strip())
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    boolean isAdmin(String user) {
        return admins.contains(user);
    }

    /** Whether {@code user} may see the session and do anything with it short of closing or re-sharing it. */
    boolean canUse(String user, AgentSession session) {
        return canManage(user, session) || session.sharedWith().contains(user);
    }

    /** Whether {@code user} may close the session and decide who it is shared with. */
    boolean canManage(String user, AgentSession session) {
        return session.owner().equals(user) || isAdmin(user);
    }
}
