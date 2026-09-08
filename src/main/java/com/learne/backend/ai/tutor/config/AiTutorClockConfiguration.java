package com.learne.backend.ai.tutor.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AiTutorClockConfiguration {
    @Bean
    Clock aiTutorClock() {
        return Clock.systemUTC();
    }
}
