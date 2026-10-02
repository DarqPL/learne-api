package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.dashboard.dto.LeaderboardStudentResponse;
import com.learne.backend.student.learning.dto.SharedStudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentLessonResponse;
import com.learne.backend.student.learning.dto.StudentQuestionSummaryResponse;
import com.learne.backend.student.learning.dto.StudentTopicResponse;
import com.learne.backend.student.learning.enums.CourseAccessStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class CacheConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestCachePropertiesConfiguration.class);

    private final ApplicationContextRunner cacheContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestCacheConfiguration.class);

    @Test
    void bindsCacheProperties() {
        contextRunner
                .withPropertyValues(
                        "app.cache.redis.enabled=false",
                        "app.cache.ttl.student-dashboard=45s",
                        "app.cache.ttl.student-catalog=3m")
                .run(context -> {
                    CacheProperties cacheProperties = context.getBean(CacheProperties.class);

                    assertThat(cacheProperties.redis().enabled()).isFalse();
                    assertThat(cacheProperties.ttl().studentDashboard().getSeconds()).isEqualTo(45);
                    assertThat(cacheProperties.ttl().studentCatalog().toMinutes()).isEqualTo(3);
                });
    }

    @Test
    void providesFallbackCacheManagerWhenRedisDisabled() {
        cacheContextRunner
                .withPropertyValues("app.cache.redis.enabled=false")
                .run(context -> {
                    CacheManager cacheManager = context.getBean(CacheManager.class);

                    assertThat(cacheManager.getCache(CacheNames.STUDENT_DASHBOARD)).isNotNull();
                    assertThat(cacheManager).isInstanceOf(ConcurrentMapCacheManager.class);
                    assertThat(((ConcurrentMapCacheManager) cacheManager).isAllowNullValues()).isFalse();
                });
    }

    @Test
    void cacheErrorHandlerSwallowsReadErrorsAndRethrowsMutationErrors() {
        cacheContextRunner
                .withPropertyValues("app.cache.redis.enabled=false")
                .run(context -> {
                    CacheErrorHandler errorHandler = context.getBean(CacheErrorHandler.class);
                    Cache cache = new ConcurrentMapCache(CacheNames.STUDENT_DASHBOARD, false);
                    RuntimeException exception = new IllegalStateException("redis unavailable");

                    assertThatCode(() -> errorHandler.handleCacheGetError(exception, cache, "key"))
                            .doesNotThrowAnyException();
                    assertThatCode(() -> errorHandler.handleCachePutError(exception, cache, "key", "value"))
                            .doesNotThrowAnyException();
                    assertThatThrownBy(() -> errorHandler.handleCacheEvictError(exception, cache, "key"))
                            .isSameAs(exception);
                    assertThatThrownBy(() -> errorHandler.handleCacheClearError(exception, cache))
                            .isSameAs(exception);
                });
    }

    @Test
    void redisPartialBindingKeepsDefaultsForMissingValues() {
        contextRunner
                .withPropertyValues("app.cache.redis.key-prefix=custom-prefix")
                .run(context -> {
                    CacheProperties properties = context.getBean(CacheProperties.class);

                    assertThat(properties.redis().enabled()).isTrue();
                    assertThat(properties.redis().keyPrefix()).isEqualTo("custom-prefix");
                    assertThat(properties.redis().ttlJitterPercent()).isEqualTo(10);
                });
    }

    @Test
    void redisValueSerializerSupportsJavaTimeTypes() {
        LeaderboardStudentResponse student = new LeaderboardStudentResponse(
                1L,
                "Student User",
                120,
                3,
                5,
                LocalDate.of(2026, 10, 2));

        ByteBuffer serialized = CacheConfig.redisValueSerializer().write(List.of(student));
        Object deserialized = CacheConfig.redisValueSerializer().read(serialized.duplicate());

        assertThat(serialized.remaining()).isPositive();
        assertThat(deserialized).isEqualTo(List.of(student));
    }

    @Test
    void redisValueSerializerSupportsSharedStudentCourseDetailResponse() {
        StudentQuestionSummaryResponse question = new StudentQuestionSummaryResponse(
                3L,
                "MULTIPLE_CHOICE",
                "Choose the correct greeting",
                "Greetings",
                "EASY",
                5,
                4);
        StudentLessonResponse lesson = new StudentLessonResponse(
                4L,
                "Greetings",
                "Basic greetings",
                "Hello and goodbye",
                "https://example.com/audio.mp3",
                "audio-public-id",
                "LISTENING",
                1,
                10,
                1,
                List.of(question));
        StudentTopicResponse topic = new StudentTopicResponse(
                5L,
                "Introductions",
                "Introduce yourself",
                1,
                List.of(lesson));
        SharedStudentCourseDetailResponse response = new SharedStudentCourseDetailResponse(
                1L,
                "English Basics",
                "Introductory course",
                CourseType.COMPREHENSIVE,
                "A1",
                2L,
                "Teacher User",
                "teacher@example.com",
                "https://example.com/avatar.png",
                Instant.parse("2026-10-02T10:15:30Z"),
                CoursePricingType.FREE,
                0L,
                List.of(topic));

        ByteBuffer serialized = CacheConfig.redisValueSerializer().write(response);
        Object deserialized = CacheConfig.redisValueSerializer().read(serialized.duplicate());

        assertThat(serialized.remaining()).isPositive();
        assertThat(deserialized).isEqualTo(response);
    }

    @Test
    void sharedStudentCourseDetailDefensivelyCopiesTopics() {
        StudentTopicResponse topic = new StudentTopicResponse(5L, "Introductions", "Introduce yourself", 1, List.of());
        List<StudentTopicResponse> topics = new ArrayList<>(List.of(topic));

        SharedStudentCourseDetailResponse response = new SharedStudentCourseDetailResponse(
                1L,
                "English Basics",
                "Introductory course",
                CourseType.COMPREHENSIVE,
                "A1",
                2L,
                "Teacher User",
                "teacher@example.com",
                "https://example.com/avatar.png",
                Instant.parse("2026-10-02T10:15:30Z"),
                CoursePricingType.FREE,
                0L,
                topics);

        topics.clear();
        StudentCourseDetailResponse unlocked = StudentCourseDetailResponse.from(response, CourseAccessStatus.FREE, null, null);

        assertThat(response.topics()).containsExactly(topic);
        assertThat(unlocked.topics()).containsExactly(topic);
        assertThatThrownBy(() -> response.topics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> unlocked.topics().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void redisValueSerializerRejectsUnexpectedPolymorphicTypes() {
        ByteBuffer payload = ByteBuffer.wrap("{\"@class\":\"java.io.File\",\"path\":\"/tmp/payload\"}"
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CacheConfig.redisValueSerializer().read(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("java.io.File");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CacheProperties.class)
    static class TestCachePropertiesConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CacheConfig.class)
    static class TestCacheConfiguration {
    }
}
