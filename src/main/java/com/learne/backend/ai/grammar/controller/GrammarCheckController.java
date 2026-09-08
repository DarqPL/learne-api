package com.learne.backend.ai.grammar.controller;

import com.learne.backend.ai.grammar.dto.GrammarCheckListItemResponse;
import com.learne.backend.ai.grammar.dto.GrammarCheckPageResponse;
import com.learne.backend.ai.grammar.dto.GrammarCheckResponse;
import com.learne.backend.ai.grammar.dto.SubmitGrammarCheckRequest;
import com.learne.backend.ai.grammar.service.GrammarCheckService;
import com.learne.backend.domain.auth.User;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grammar-checks")
class GrammarCheckController {
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;

    private final GrammarCheckService grammarCheckService;

    GrammarCheckController(GrammarCheckService grammarCheckService) {
        this.grammarCheckService = grammarCheckService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GrammarCheckResponse submit(
            @Valid @RequestBody SubmitGrammarCheckRequest request,
            Authentication authentication) {
        return grammarCheckService.submit(currentUser(authentication), request);
    }

    @GetMapping
    GrammarCheckPageResponse list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int boundedSize = Math.max(MIN_PAGE_SIZE, Math.min(size, MAX_PAGE_SIZE));
        Page<GrammarCheckListItemResponse> result = grammarCheckService.list(
                currentUser(authentication), PageRequest.of(Math.max(page, 0), boundedSize));
        return new GrammarCheckPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @GetMapping("/{id}")
    GrammarCheckResponse get(@PathVariable Long id, Authentication authentication) {
        return grammarCheckService.get(currentUser(authentication), id);
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
