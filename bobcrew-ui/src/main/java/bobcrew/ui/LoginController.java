package bobcrew.ui;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The user's login ({@link OidcLogin}): {@code /login} sends the browser to the identity provider, which sends it
 * back to {@code /login/callback}; {@code /logout} ends the login here and at the provider; {@code /api/login} tells
 * the page whether there is one. All behind {@link LocalOnlyFilter}, which lets only the callback come from another
 * site, since the provider sends the browser there.
 */
@RestController
class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final OidcLogin login;

    private final JsonMapper json = JsonMapper.builder().build();

    LoginController(OidcLogin login) {
        this.login = login;
    }

    @GetMapping("/api/login")
    void status(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(json.writeValueAsBytes(Map.of("enabled", login.enabled(),
                "loggedIn", login.loggedIn())));
    }

    @GetMapping("/login")
    void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!login.enabled()) {
            plain(response, HttpServletResponse.SC_NOT_FOUND, "This UI has no login: it uses configured tokens.");
            return;
        }
        try {
            redirect(response, login.start(request.getLocalPort()).location().toString());
        } catch (IOException e) {
            log.warn("Cannot reach the identity provider: {}", e.toString());
            plain(response, HttpServletResponse.SC_BAD_GATEWAY, "Cannot reach the identity provider: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @GetMapping("/login/callback")
    void callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error, HttpServletResponse response) throws IOException {
        if (!login.enabled()) {
            plain(response, HttpServletResponse.SC_NOT_FOUND, "This UI has no login.");
            return;
        }
        if (state == null && code == null && error == null) {
            // back from the provider's logout
            redirect(response, "/");
            return;
        }
        try {
            if (login.finish(state, code, error)) {
                redirect(response, "/");
            } else {
                plain(response, HttpServletResponse.SC_BAD_REQUEST, "The login did not complete (it was cancelled, "
                        + "took too long, or did not start here). Go back to the bob-crew page and log in again.");
            }
        } catch (IOException e) {
            log.warn("Cannot complete the login with the identity provider: {}", e.toString());
            plain(response, HttpServletResponse.SC_BAD_GATEWAY, "Cannot reach the identity provider: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @GetMapping("/logout")
    void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!login.enabled()) {
            redirect(response, "/");
            return;
        }
        try {
            OidcLogin.Redirect next = login.logout(request.getLocalPort());
            redirect(response, next == null ? "/" : next.location().toString());
        } catch (IOException e) {
            // logged out here anyway; the provider's session ends on its own
            log.warn("Cannot reach the identity provider to log out there: {}", e.toString());
            redirect(response, "/");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void redirect(HttpServletResponse response, String location) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location);
        response.setHeader("Cache-Control", "no-store");
    }

    private static void plain(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(message);
    }
}
