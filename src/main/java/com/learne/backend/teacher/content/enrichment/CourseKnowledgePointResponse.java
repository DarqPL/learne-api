package com.learne.backend.teacher.content.enrichment;

import java.util.List;

public record CourseKnowledgePointResponse(List<Item> items) {
    public record Item(Long questionId, String knowledgePoint) {}
}
