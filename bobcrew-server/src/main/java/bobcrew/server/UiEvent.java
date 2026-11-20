package bobcrew.server;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of a session's event stream, as clients receive it.
 *
 * @param seq position in the session's stream, starting at 1; clients resume after a seq
 * @param time when the server recorded it
 * @param type what happened: {@code update} (an ACP session update, as the agent sent it),
 * {@code user_prompt}, {@code permission_request}, {@code permission_resolved}, {@code turn_end},
 * {@code status}, {@code error}, {@code cleared} (the conversation was started over; every
 * event before it is gone from the log, and clients show an empty transcript from here),
 * {@code file_uploaded} (a user put a file in the workspace) or {@code repository_cloned} (the repository
 * the session was created with is checked out in the workspace) or {@code shared} (the owner changed who else
 * may use the session; {@code data.users} is the new list) or {@code secrets_refreshed} (a user replaced the
 * values of the secrets {@code data.names}) or {@code resumed} (a user started the session's agent again:
 * {@code data.from} is {@code crash}, after it stopped unexpectedly, or {@code archive}, from the session's archive)
 * or {@code user_profile_refreshed} (a user re-sent the user profile files; {@code data.count} is the number of
 * files written) or {@code compacting} (a user asked for the conversation to be compacted; {@code data.instructions},
 * if any, are theirs; the agent's summary follows, as a turn) or {@code compacted} (the agent goes on from that
 * summary, in a new session of its own)
 * @param user who caused it, for events caused by a user
 * @param data the details, depending on the type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UiEvent(long seq, Instant time, String type, String user, Object data) {

    public static final String UPDATE = "update";

    public static final String USER_PROMPT = "user_prompt";

    public static final String PERMISSION_REQUEST = "permission_request";

    public static final String PERMISSION_RESOLVED = "permission_resolved";

    public static final String TURN_END = "turn_end";

    public static final String STATUS = "status";

    public static final String ERROR = "error";

    public static final String CLEARED = "cleared";

    public static final String FILE_UPLOADED = "file_uploaded";

    public static final String REPOSITORY_CLONED = "repository_cloned";

    public static final String SHARED = "shared";

    public static final String SECRETS_REFRESHED = "secrets_refreshed";

    /** The session's agent was started again and went on with its task: {@code data.from} is crash or archive. */
    public static final String RESUMED = "resumed";

    public static final String USER_PROFILE_REFRESHED = "user_profile_refreshed";

    /** A user asked for the conversation to be compacted; the agent's summary turn follows. */
    public static final String COMPACTING = "compacting";

    /** The conversation was compacted: the agent goes on from the summary, in a new session. */
    public static final String COMPACTED = "compacted";
}
