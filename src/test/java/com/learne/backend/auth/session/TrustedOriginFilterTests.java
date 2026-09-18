package com.learne.backend.auth.session;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173,https://app.example.com")
class TrustedOriginFilterTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void refreshRejectsDisallowedOrigin() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshAllowsConfiguredOriginToReachNormalAuthHandling() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRejectsDisallowedReferer() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.REFERER, "https://evil.example.com/session"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRejectsMalformedRefererWithoutTrustedOrigin() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.REFERER, "https://["))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshAllowsTrustedOriginWithMalformedRefererToReachNormalAuthHandling() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.REFERER, "https://["))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllowsRefererWithDefaultHttpsPort() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.REFERER, "https://app.example.com:443/session"))
                .andExpect(status().isOk());
    }

    @Test
    void refreshAllowsOriginWithCaseNormalizedSchemeAndHost() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "HTTPS://APP.EXAMPLE.COM"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithDisallowedRefererIsNotBlockedByTrustedOriginFilter() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.REFERER, "https://evil.example.com/session"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutWithoutOriginOrRefererRemainsCompatible() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk());
    }
}
