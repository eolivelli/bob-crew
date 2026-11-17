package bobcrew.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A JUnit condition ({@code @ExtendWith(KeycloakAvailable.class)}) that starts {@link TestKeycloak} before the test
 * class (and before Spring, which needs its address), and skips the class when it cannot: no Docker daemon, or the
 * image cannot be pulled. With {@code BOBCREW_REQUIRE_DOCKER=true} (as in CI) that fails the tests instead.
 */
public final class KeycloakAvailable implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String reason = TestKeycloak.unavailableReason();
        if (reason == null) {
            return ConditionEvaluationResult.enabled("Keycloak is running");
        }
        if (Boolean.parseBoolean(System.getenv("BOBCREW_REQUIRE_DOCKER"))) {
            throw new IllegalStateException(reason + "; failing, since BOBCREW_REQUIRE_DOCKER is set");
        }
        System.err.println(reason + "; Keycloak tests skipped");
        return ConditionEvaluationResult.disabled(reason + "; Keycloak tests skipped");
    }
}
