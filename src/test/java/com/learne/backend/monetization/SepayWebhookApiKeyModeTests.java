package com.learne.backend.monetization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learne.backend.testsupport.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.payments.sepay.enabled=true",
        "app.payments.sepay.webhook-auth-mode=API_KEY",
        "app.payments.sepay.bank-code=Vietcombank",
        "app.payments.sepay.bank-name=Vietcombank",
        "app.payments.sepay.account-number=0010000000355",
        "app.payments.sepay.account-name=CONG TY LEARNE"
})
class SepayWebhookApiKeyModeTests {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void apiKeyModeWithMissingConfiguredKeyDoesNotAcceptApikeyNull() throws Exception {
        mockMvc.perform(post("/api/webhooks/sepay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Apikey null")
                        .content("""
                                {
                                  "id": 9100,
                                  "code": "LEANY",
                                  "content": "LEANY",
                                  "transferType": "in",
                                  "transferAmount": 250000,
                                  "referenceCode": "BANK-9100"
                                }
                                """))
                .andExpect(status().isInternalServerError());
    }
}
