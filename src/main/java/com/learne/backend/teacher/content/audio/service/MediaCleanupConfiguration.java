package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.jobs.service.JobStore;
import com.learne.backend.storage.config.CloudinaryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Activation advertises only the fully implemented v1 VIDEO cleanup protocol. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.jobs.media-cleanup-enabled",havingValue="true")
public class MediaCleanupConfiguration {
    @Bean
    CloudinaryMediaDeleteAdapter cloudinaryMediaDeleteAdapter(CloudinaryProperties properties,ObjectMapper mapper) {
        return new CloudinaryMediaDeleteAdapter(properties,mapper);
    }
    @Bean
    MediaCleanupHandler mediaCleanupHandler(JobStore store,LessonAudioCleanupDecisionService decisions,
            CloudinaryMediaDeleteAdapter storage,ObjectMapper mapper) {
        return new MediaCleanupHandler(store,decisions,storage,mapper);
    }
}
