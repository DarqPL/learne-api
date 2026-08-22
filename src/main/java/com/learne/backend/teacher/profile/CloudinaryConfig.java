package com.learne.backend.teacher.profile;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CloudinaryProperties.class)
class CloudinaryConfig {
}
