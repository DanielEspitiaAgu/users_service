package co.edu.uptc.users_service.config;

import co.edu.uptc.users_service.security.GatewayIdentityFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final GatewayIdentityFilter gatewayIdentityFilter;

    @Bean
    public PasswordEncoder metricsPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService metricsUserDetailsService(
            @Value("${METRICS_SCRAPE_USERNAME:metrics}") String username,
            @Value("${METRICS_SCRAPE_PASSWORD:}") String password,
            PasswordEncoder passwordEncoder) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("La credencial local de scraping debe estar configurada");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("METRICS")
                .build());
    }

    @Bean
    @Order(1)
    public SecurityFilterChain metricsFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/prometheus")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(gatewayIdentityFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/v3/api-docs",
                                "/swagger-resources/**", "/webjars/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/users/*/results").hasRole("SERVICE")
                        .anyRequest().authenticated())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(gatewayIdentityFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
