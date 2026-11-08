package co.edu.uptc.users_service.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class GatewayIdentityFilterTest {

    private final GatewayIdentityFilter filter = new GatewayIdentityFilter("gateway-secret", "internal-token");

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWhenGatewaySecretIsMissing() throws Exception {
        MockHttpServletRequest request = request("GET", "/users/me");
        request.addHeader("X-User-Id", "user-123");
        request.addHeader("X-Roles", "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void authenticatesGatewayIdentityAndOnlyKnownRoles() throws Exception {
        MockHttpServletRequest request = request("GET", "/users/me");
        request.addHeader("X-Gateway-Secret", "gateway-secret");
        request.addHeader("X-User-Id", "user-123");
        request.addHeader("X-Roles", "default-roles-hopedoku,USER,offline_access,ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("user-123", authentication.getName());
        assertEquals(2, authentication.getAuthorities().size());
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertEquals(200, response.getStatus());
    }

    @Test
    void acceptsInternalTokenOnlyForResultsAsServiceRole() throws Exception {
        MockHttpServletRequest request = request("POST", "/users/external-123/results");
        request.addHeader("X-Internal-Token", "internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("internal-service", authentication.getName());
        assertEquals("ROLE_SERVICE", authentication.getAuthorities().iterator().next().getAuthority());
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsForgedIdentityHeadersWithoutGatewaySecret() throws Exception {
        MockHttpServletRequest request = request("GET", "/users/me");
        request.addHeader("X-User-Id", "999");
        request.addHeader("X-Roles", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rejectsMalformedExternalIdBeforeAuthentication() throws Exception {
        MockHttpServletRequest request = request("GET", "/users/me");
        request.addHeader("X-Gateway-Secret", "gateway-secret");
        request.addHeader("X-User-Id", "x".repeat(65));
        request.addHeader("X-Roles", "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingInternalTokenDoesNotAuthorizeResults() throws Exception {
        MockHttpServletRequest request = request("POST", "/users/external-123/results");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
