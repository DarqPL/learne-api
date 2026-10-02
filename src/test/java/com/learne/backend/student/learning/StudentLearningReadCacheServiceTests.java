package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.service.StudentLearningReadCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

class StudentLearningReadCacheServiceTests {

    @Test
    void courseDetailReadIsNotCachedBecauseAccessCanExpireWithoutWrite() throws NoSuchMethodException {
        assertThat(StudentLearningReadCacheService.class
                .getMethod("getCourse", User.class, Long.class)
                .getAnnotation(Cacheable.class))
                .isNull();
    }
}
