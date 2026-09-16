package com.learne.backend.admin.user.dto;

import com.learne.backend.student.learning.dto.StudentStatsResponse;
import java.util.List;

public record AdminStudentDetailResponse(
        Long userId,
        String fullName,
        String email,
        boolean locked,
        boolean mustChangePassword,
        StudentStatsResponse stats,
        List<AdminStudentEnrollmentResponse> enrollments,
        List<AdminStudentQuizAttemptResponse> recentQuizAttempts,
        List<AdminStudentGrammarCheckResponse> recentGrammarChecks,
        List<AdminStudentAiConversationResponse> recentAiConversations) {
}
