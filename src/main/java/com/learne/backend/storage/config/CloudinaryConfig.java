package com.learne.backend.storage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CloudinaryProperties.class)
public class CloudinaryConfig {
}
