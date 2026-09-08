package com.learne.backend.ai.tutor.controller;

import com.learne.backend.ai.tutor.dto.AiConversationSummaryResponse;
import com.learne.backend.ai.tutor.service.AiTutorService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ai-tutor/conversations")
class AdminAiTutorController {
    private final AiTutorService aiTutorService;

    AdminAiTutorController(AiTutorService aiTutorService) {
        this.aiTutorService = aiTutorService;
    }

    @PostMapping("/{id}/restore")
    AiConversationSummaryResponse restore(@PathVariable Long id) {
        return aiTutorService.restoreArchived(id);
    }
}
