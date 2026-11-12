package bobcrew.server;

import org.springframework.http.HttpStatus;

/** A failure that is reported to the API client with this status and message. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
