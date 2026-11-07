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
import java.util.List;


@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private final byte[] expectedKey;

    public InternalApiKeyFilter(@Value("${internal.api-token:}") String apiToken) {
        this.expectedKey = apiToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String received = request.getHeader("X-Internal-Token");
        if (expectedKey.length > 0 && received != null
                && SecurityContextHolder.getContext().getAuthentication() == null
                && MessageDigest.isEqual(expectedKey, received.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
        }
        filterChain.doFilter(request, response);
    }
}