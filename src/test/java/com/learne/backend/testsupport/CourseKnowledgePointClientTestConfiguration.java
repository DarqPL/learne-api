package com.learne.backend.testsupport;

import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class CourseKnowledgePointClientTestConfiguration {
    @Bean
    @Primary
    CourseKnowledgePointClient courseKnowledgePointClient() {
        return request -> new CourseKnowledgePointResponse(request.questions().stream()
                .map(question -> new CourseKnowledgePointResponse.Item(
                        question.questionId(), "test_knowledge_point"))
                .toList());
    }
}
