package com.learne.backend.student.learning.dto;

import java.util.List;

public record PlacementQuestionResponse(Long id, String type, String questionText, String knowledgePoint,
        String skill, int sequenceOrder, List<StudentQuizOptionResponse> options) {}
