package co.edu.uptc.users_service.security;

import co.edu.uptc.users_service.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PrometheusSecurityTest.TestEndpoint.class)
@Import({SecurityConfig.class, GatewayIdentityFilter.class, PrometheusSecurityTest.TestEndpoint.class})
@TestPropertySource(properties = {
        "security.gateway-secret=test-gateway-secret",
        "internal.api-token=test-internal-token",
        "METRICS_SCRAPE_USERNAME=metrics",
        "METRICS_SCRAPE_PASSWORD=test-scrape-password"
})
class PrometheusSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void metricsRequireBasicAuthentication() throws Exception {
        mockMvc.perform(get("/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsRejectIncorrectBasicAuthentication() throws Exception {
        mockMvc.perform(get("/prometheus").with(httpBasic("metrics", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsAcceptConfiguredScrapeCredential() throws Exception {
        mockMvc.perform(get("/prometheus").with(httpBasic("metrics", "test-scrape-password")))
                .andExpect(status().isOk())
                .andExpect(content().string("sample_metrics 1\n"));
    }

    @Test
    void gatewayForwardedMetricsRequestIsRejectedEvenWithScrapeCredential() throws Exception {
        mockMvc.perform(get("/prometheus")
                        .with(httpBasic("metrics", "test-scrape-password"))
                        .header("X-Gateway-Secret", "test-gateway-secret")
                        .header("X-User-Id", "test-user")
                        .header("X-Roles", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthRouteRemainsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @RestController
    static class TestEndpoint {

        @GetMapping("/prometheus")
        String metrics() {
            return "sample_metrics 1\n";
        }

        @GetMapping("/health")
        String health() {
            return "UP";
        }
    }
}
