package com.learne.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("test")
@SpringBootTest
@TestPropertySource(properties = {
        "app.admin.email=admin@learne.local",
        "app.admin.password=Admin123",
        "app.admin.full-name=LearnE Admin"
})
class AdminSeederTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createsInitialAdminFromConfiguredCredentials() {
        User admin = userRepository.findByEmail("admin@learne.local").orElseThrow();

        assertThat(admin.getFullName()).isEqualTo("LearnE Admin");
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.isLocked()).isFalse();
        assertThat(admin.isMustChangePassword()).isFalse();
        assertThat(admin.getPasswordHash()).isNotEqualTo("Admin123");
        assertThat(passwordEncoder.matches("Admin123", admin.getPasswordHash())).isTrue();
    }
}
