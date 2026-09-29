package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("test")
@SpringBootTest
@TestPropertySource(properties = {
        "app.cache.redis.enabled=false",
        "app.cache.ttl.student-dashboard=45s",
        "app.cache.ttl.student-catalog=3m"
})
class CacheConfigTests {

    @Autowired CacheProperties cacheProperties;
    @Autowired CacheManager cacheManager;

    @Test
    void bindsCachePropertiesAndProvidesFallbackCacheManager() {
        assertThat(cacheProperties.redis().enabled()).isFalse();
        assertThat(cacheProperties.ttl().studentDashboard().getSeconds()).isEqualTo(45);
        assertThat(cacheProperties.ttl().studentCatalog().toMinutes()).isEqualTo(3);
        assertThat(cacheManager.getCache(CacheNames.STUDENT_DASHBOARD)).isNotNull();
    }
}
