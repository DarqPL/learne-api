package com.learne.backend.student.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SubmitPlacementRequest(
        @NotNull List<@Valid SubmitPlacementAnswerRequest> answers
) {}
