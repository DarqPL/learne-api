package com.learne.backend.admin.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectContentRequest(
        @NotBlank @Size(max = 2000) String reason
) {
    public RejectContentRequest {
        if (reason != null) {
            reason = reason.trim();
        }
    }
}
