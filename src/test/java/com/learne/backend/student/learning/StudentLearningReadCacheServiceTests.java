package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.cache.CacheNames;
import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.service.StudentLearningReadCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

class StudentLearningReadCacheServiceTests {

    @Test
    void sharedCourseDetailReadIsCachedByCourseAndCatalogVersion() throws NoSuchMethodException {
        Cacheable cacheable = StudentLearningReadCacheService.class
                .getMethod("getSharedCourseDetail", Long.class)
                .getAnnotation(Cacheable.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.cacheNames()).containsExactly(CacheNames.STUDENT_COURSE_DETAIL);
        assertThat(cacheable.key()).contains("studentCourseDetail(#courseId");
        assertThat(cacheable.key()).contains("catalogVersion()");
    }

    @Test
    void finalCourseDetailReadIsNotCachedBecauseItContainsUserSpecificFields() throws NoSuchMethodException {
        assertThat(StudentLearningReadCacheService.class
                .getMethod("getCourse", User.class, Long.class)
                .getAnnotation(Cacheable.class))
                .isNull();
    }
}
