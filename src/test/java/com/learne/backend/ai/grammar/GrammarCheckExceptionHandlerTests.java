package com.learne.backend.ai.grammar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learne.backend.ai.grammar.service.GrammarCheckFailedException;
import com.learne.backend.shared.api.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GrammarCheckExceptionHandlerTests {
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void grammarCheckFailedExceptionReturnsFailurePayload() throws Exception {
        mockMvc.perform(get("/test/grammar-check-failure"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.checkId").value(42))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("AI unavailable"));
    }

    @RestController
    static class TestController {
        @GetMapping("/test/grammar-check-failure")
        void fail() {
            throw new GrammarCheckFailedException(HttpStatus.BAD_GATEWAY, "AI unavailable", 42L);
        }
    }
}
