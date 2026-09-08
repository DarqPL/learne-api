package com.learne.backend.ai.tutor.controller;

import com.learne.backend.ai.tutor.dto.AiConversationDetailResponse;
import com.learne.backend.ai.tutor.dto.AiConversationPageResponse;
import com.learne.backend.ai.tutor.dto.AiConversationSummaryResponse;
import com.learne.backend.ai.tutor.dto.CreateAiConversationRequest;
import com.learne.backend.ai.tutor.dto.SendAiTutorMessageRequest;
import com.learne.backend.ai.tutor.dto.SendAiTutorMessageResponse;
import com.learne.backend.ai.tutor.service.AiTutorService;
import com.learne.backend.domain.auth.User;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-tutor/conversations")
class AiTutorController {
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;

    private final AiTutorService aiTutorService;

    AiTutorController(AiTutorService aiTutorService) {
        this.aiTutorService = aiTutorService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AiConversationSummaryResponse create(
            @Valid @RequestBody CreateAiConversationRequest request,
            Authentication authentication) {
        return aiTutorService.create(currentUser(authentication), request);
    }

    @GetMapping
    AiConversationPageResponse list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.max(MIN_PAGE_SIZE, Math.min(size, MAX_PAGE_SIZE));
        Page<AiConversationSummaryResponse> result = aiTutorService.list(
                currentUser(authentication), PageRequest.of(boundedPage, boundedSize));
        return new AiConversationPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @GetMapping("/{id}")
    AiConversationDetailResponse get(@PathVariable Long id, Authentication authentication) {
        return aiTutorService.get(currentUser(authentication), id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    SendAiTutorMessageResponse sendMessage(
            @PathVariable Long id,
            @Valid @RequestBody SendAiTutorMessageRequest request,
            Authentication authentication) {
        return aiTutorService.sendMessage(currentUser(authentication), id, request);
    }

    @PostMapping("/{id}/archive")
    AiConversationSummaryResponse archive(@PathVariable Long id, Authentication authentication) {
        return aiTutorService.archive(currentUser(authentication), id);
    }

    @DeleteMapping("/{id}")
    AiConversationSummaryResponse delete(@PathVariable Long id, Authentication authentication) {
        return aiTutorService.archive(currentUser(authentication), id);
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
