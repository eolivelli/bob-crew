package bobcrew.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalOnlyFilterTest {

    private final LocalOnlyFilter filter = new LocalOnlyFilter();

    @Test
    void allowsTheUiItself() throws Exception {
        assertThat(status(request("GET", "127.0.0.1:8642"))).isEqualTo(200);
        assertThat(status(request("GET", "localhost:8642"))).isEqualTo(200);
        assertThat(status(request("GET", "[::1]:8642"))).isEqualTo(200);

        MockHttpServletRequest post = request("POST", "127.0.0.1:8642");
        post.addHeader("Origin", "http://127.0.0.1:8642");
        post.addHeader("Sec-Fetch-Site", "same-origin");
        post.addHeader(LocalOnlyFilter.MARKER_HEADER, "1");
        assertThat(status(post)).isEqualTo(200);
    }

    @Test
    void refusesOtherHostNames() throws Exception {
        // what a DNS rebinding page would send
        assertThat(status(request("GET", "evil.example:8642"))).isEqualTo(403);
        assertThat(status(request("GET", "127.0.0.1.evil.example"))).isEqualTo(403);
        assertThat(status(request("GET", "192.168.1.10:8642"))).isEqualTo(403);
        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/api/me");
        assertThat(status(none)).isEqualTo(403);
    }

    @Test
    void refusesRequestsFromOtherOrigins() throws Exception {
        MockHttpServletRequest foreign = request("GET", "127.0.0.1:8642");
        foreign.addHeader("Origin", "https://evil.example");
        assertThat(status(foreign)).isEqualTo(403);

        MockHttpServletRequest otherPort = request("GET", "127.0.0.1:8642");
        otherPort.addHeader("Origin", "http://127.0.0.1:9999");
        assertThat(status(otherPort)).isEqualTo(403);

        MockHttpServletRequest crossSite = request("GET", "127.0.0.1:8642");
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(status(crossSite)).isEqualTo(403);
    }

    @Test
    void refusesChangesWithoutTheMarkerHeader() throws Exception {
        // a plain cross-site form post carries no custom header
        for (String method : new String[] {"POST", "DELETE", "PUT", "PATCH"}) {
            assertThat(status(request(method, "127.0.0.1:8642"))).as(method).isEqualTo(403);
        }
    }

    @Test
    void setsSecurityHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "127.0.0.1:8642"), response, new MockFilterChain());
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'self'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    private static MockHttpServletRequest request(String method, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/sessions");
        request.addHeader("Host", host);
        return request;
    }

    private int status(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
