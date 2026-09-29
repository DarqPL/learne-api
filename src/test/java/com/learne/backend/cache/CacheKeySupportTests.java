package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CacheKeySupportTests {

    @Test
    void studentCatalogKeyContainsPrincipalFiltersAndCatalogVersion() {
        String key = CacheKeySupport.studentCatalog(42L, "A1", 9L, 7L);

        assertThat(key).isEqualTo("student:42:level:A1:teacher:9:catalogVersion:7");
    }

    @Test
    void studentCatalogKeyNormalizesBlankFilters() {
        String key = CacheKeySupport.studentCatalog(42L, " ", null, 7L);

        assertThat(key).isEqualTo("student:42:level:all:teacher:all:catalogVersion:7");
    }

    @Test
    void logKeyHashDoesNotExposeFullKey() {
        String key = "student:42:level:A1:teacher:9:catalogVersion:7";

        String hash = CacheKeySupport.logKeyHash(key);

        assertThat(hash).hasSize(16);
        assertThat(hash).doesNotContain("student");
        assertThat(hash).doesNotContain("42");
    }

    @Test
    void requiredSecurityScopedIdsFailFastWhenNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeySupport.studentCatalog(null, "A1", 9L, 7L))
                .withMessage("studentId is required");
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeySupport.studentCourseDetail(null, 12L, 7L))
                .withMessage("studentId is required");
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeySupport.studentCourseDetail(42L, null, 7L))
                .withMessage("courseId is required");
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeySupport.studentDashboard(null))
                .withMessage("studentId is required");
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeySupport.teacherDashboard(null))
                .withMessage("teacherId is required");
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeySupport.courseAccess(null))
                .withMessage("studentId is required");
    }

    @Test
    void optionalCatalogFiltersNormalizeSafely() {
        assertThat(CacheKeySupport.studentCatalog(42L, null, null, null))
                .isEqualTo("student:42:level:all:teacher:all:catalogVersion:0");
        assertThat(CacheKeySupport.studentCourseDetail(42L, 12L, null))
                .isEqualTo("student:42:course:12:catalogVersion:0");
    }

    @Test
    void leaderboardRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> CacheKeySupport.leaderboard(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> CacheKeySupport.leaderboard(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
    }
}
