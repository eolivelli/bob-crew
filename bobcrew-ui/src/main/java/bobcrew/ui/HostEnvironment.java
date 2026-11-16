package bobcrew.ui;

import org.springframework.stereotype.Component;

/**
 * Where the values of a role's environment variables come from: this UI app's own environment. A separate
 * bean so that tests can supply their own.
 */
@Component
class HostEnvironment {

    /** The variable's value, or null if it is not set. */
    String get(String name) {
        return System.getenv(name);
    }
}
