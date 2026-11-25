package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.teacher.content.repository.CourseRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class CourseCoverCleanupConfiguration {
    @Bean
    CourseCoverCleanupDecisionService courseCoverCleanupDecisionService(
            CourseRepository courses, JdbcTemplate jdbc) {
        return new CourseCoverCleanupDecisionService(courses, jdbc);
    }
}
