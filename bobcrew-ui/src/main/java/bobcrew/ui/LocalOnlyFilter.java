package bobcrew.ui;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The UI has no login: whoever can send it a request acts with the user's token. Binding to the
 * loopback interface keeps other machines out; this filter keeps out the other thing that can
 * reach a loopback port, which is any web page open in the user's browser.
 *
 * <ul>
 * <li>The {@code Host} header must name the loopback interface. A page that points its own domain
 * at 127.0.0.1 (DNS rebinding) sends its own host name and is refused.</li>
 * <li>A request that says where it comes from ({@code Origin}, {@code Sec-Fetch-Site}) must come
 * from this UI itself.</li>
 * <li>Requests that change something must carry the {@code X-BobCrew} header. A page from another
 * origin cannot add a custom header without a CORS preflight, which is never granted.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LocalOnlyFilter extends OncePerRequestFilter {

    static final String MARKER_HEADER = "X-BobCrew";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader("Host");
        if (host == null || !LOOPBACK_HOSTS.contains(hostName(host))) {
            refuse(response, "This UI only answers on the loopback interface");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.equalsIgnoreCase("http://" + host)) {
            refuse(response, "Requests from other origins are not allowed");
            return;
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.equals("same-origin") && !fetchSite.equals("none")) {
            refuse(response, "Requests from other sites are not allowed");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod()) && request.getHeader(MARKER_HEADER) == null) {
            refuse(response, "Missing " + MARKER_HEADER + " header");
            return;
        }
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        chain.doFilter(request, response);
    }

    /** The host of a {@code Host} header value, without the port. */
    private static String hostName(String host) {
        String value = host.strip().toLowerCase();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(0, end + 1);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private static void refuse(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain");
        response.getWriter().write(reason);
    }
}
