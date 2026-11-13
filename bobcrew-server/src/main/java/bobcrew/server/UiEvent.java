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
 * {@code status}, {@code error} or {@code cleared} (the conversation was started over; every
 * event before it is gone from the log, and clients show an empty transcript from here)
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
}
