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
@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173")
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
    void logoutWithoutOriginOrRefererRemainsCompatible() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk());
    }
}
