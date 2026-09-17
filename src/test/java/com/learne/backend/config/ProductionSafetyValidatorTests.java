package com.learne.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyValidatorTests {

    private final ProductionSafetyValidator validator = new ProductionSafetyValidator();

    @Test
    void rejectsDefaultJwtSecretInProd() {
        MockEnvironment environment = safeProductionEnvironment()
                .withProperty("app.auth.jwt-secret", "learne-development-jwt-secret-change-me-please-32-bytes-minimum");

        assertThatThrownBy(() -> validator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.auth.jwt-secret");
    }

    @Test
    void rejectsWeakJwtSecretInProd() {
        MockEnvironment environment = safeProductionEnvironment()
                .withProperty("app.auth.jwt-secret", "short-production-secret");

        assertThatThrownBy(() -> validator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.auth.jwt-secret");
    }

    @Test
    void rejectsDefaultAdminPasswordInProd() {
        MockEnvironment environment = safeProductionEnvironment()
                .withProperty("app.admin.password", "Admin123");

        assertThatThrownBy(() -> validator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.password");
    }

    @Test
    void rejectsBlankAdminPasswordInProd() {
        MockEnvironment environment = safeProductionEnvironment()
                .withProperty("app.admin.password", " ");

        assertThatThrownBy(() -> validator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.password");
    }

    @Test
    void rejectsInsecureRefreshCookieInProd() {
        MockEnvironment environment = safeProductionEnvironment()
                .withProperty("app.auth.refresh-cookie-secure", "false");

        assertThatThrownBy(() -> validator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.auth.refresh-cookie-secure");
    }

    @Test
    void allowsSafeProductionValues() {
        assertThatCode(() -> validator.validate(safeProductionEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresLocalProfiles() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.auth.jwt-secret", "short")
                .withProperty("app.admin.password", "Admin123")
                .withProperty("app.auth.refresh-cookie-secure", "false");
        environment.setActiveProfiles("local");

        assertThatCode(() -> validator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresTestProfiles() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.auth.jwt-secret", "short")
                .withProperty("app.admin.password", "Admin123")
                .withProperty("app.auth.refresh-cookie-secure", "false");
        environment.setActiveProfiles("test");

        assertThatCode(() -> validator.validate(environment))
                .doesNotThrowAnyException();
    }

    private MockEnvironment safeProductionEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.auth.jwt-secret", "safe-production-jwt-secret-at-least-32-bytes")
                .withProperty("app.admin.password", "SufficientlyStrongAdminPassword123!")
                .withProperty("app.auth.refresh-cookie-secure", "true");
        environment.setActiveProfiles("prod");
        return environment;
    }
}
