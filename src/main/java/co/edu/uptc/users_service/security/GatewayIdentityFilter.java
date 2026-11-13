package co.edu.uptc.users_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Component
public class GatewayIdentityFilter extends OncePerRequestFilter {

    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern RESULTS_PATH = Pattern.compile("/users/[^/]+/results");
    private static final Set<String> PLAYER_ROLES = Set.of("USER", "ADMIN");
    private static final List<String> DOCUMENTATION_PATHS = List.of(
            "/swagger-ui/", "/swagger-ui.html", "/v3/api-docs", "/swagger-resources/", "/webjars/");

    private final byte[] gatewaySecretDigest;
    private final byte[] internalTokenDigest;

    public GatewayIdentityFilter(
            @Value("${security.gateway-secret:}") String gatewaySecret,
            @Value("${internal.api-token:}") String internalToken) {
        if (gatewaySecret == null || gatewaySecret.isBlank()) {
            throw new IllegalStateException("GATEWAY_SECRET debe estar configurado");
        }
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN debe estar configurado");
        }
        this.gatewaySecretDigest = digest(gatewaySecret);
        this.internalTokenDigest = digest(internalToken);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ("GET".equals(request.getMethod()) && "/health".equals(request.getRequestURI()))
                || ("/prometheus".equals(request.getRequestURI())
                && request.getHeader("X-Gateway-Secret") == null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/prometheus".equals(path) && request.getHeader("X-Gateway-Secret") != null) {
            reject(response);
            return;
        }

        if (isResultsRequest(request) && hasValidSecret(request.getHeader("X-Internal-Token"), internalTokenDigest)) {
            setAuthentication("internal-service", List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            filterChain.doFilter(request, response);
            return;
        }

        if (!hasValidSecret(request.getHeader("X-Gateway-Secret"), gatewaySecretDigest)) {
            reject(response);
            return;
        }

        if (isDocumentationPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String externalId = request.getHeader("X-User-Id");
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            reject(response);
            return;
        }

        Set<String> roles = parseRoles(request.getHeader("X-Roles"));
        if (roles.isEmpty()) {
            reject(response);
            return;
        }

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        setAuthentication(externalId, authorities);
        filterChain.doFilter(request, response);
    }

    private boolean isResultsRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && RESULTS_PATH.matcher(request.getRequestURI()).matches();
    }

    private boolean isDocumentationPath(String path) {
        return DOCUMENTATION_PATHS.stream().anyMatch(path::startsWith);
    }

    private Set<String> parseRoles(String header) {
        if (header == null) {
            return Set.of();
        }
        Set<String> roles = new TreeSet<>();
        Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(PLAYER_ROLES::contains)
                .forEach(roles::add);
        return roles;
    }

    private boolean hasValidSecret(String received, byte[] expectedDigest) {
        return received != null && MessageDigest.isEqual(expectedDigest, digest(received));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }

    private void setAuthentication(String principal, List<SimpleGrantedAuthority> authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
