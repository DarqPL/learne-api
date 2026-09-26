package com.learne.backend.config;

import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@EnableConfigurationProperties(AdminSeedProperties.class)
public class AdminSeeder implements ApplicationRunner {

    private final AdminSeedProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    public AdminSeeder(AdminSeedProperties properties, PasswordEncoder passwordEncoder, UserRepository userRepository) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.getEmail()) || !StringUtils.hasText(properties.getPassword())) {
            return;
        }

        userRepository.findByEmail(properties.getEmail())
                .ifPresentOrElse(this::updateSeededAdminOrigin, this::createAdmin);
    }

    private void createAdmin() {
        User admin = new User();
        admin.setEmail(properties.getEmail().trim().toLowerCase());
        admin.setPasswordHash(passwordEncoder.encode(properties.getPassword()));
        admin.setFullName(StringUtils.hasText(properties.getFullName()) ? properties.getFullName().trim() : "LearnE Admin");
        admin.setRole(UserRole.ADMIN);
        admin.setActive(true);
        admin.setLocked(false);
        admin.setMustChangePassword(false);
        admin.setAccountOrigin(AccountOrigin.ADMIN_CREATED);
        userRepository.save(admin);
    }

    private void updateSeededAdminOrigin(User user) {
        if (user.getRole() == UserRole.ADMIN && user.getAccountOrigin() != AccountOrigin.ADMIN_CREATED) {
            user.setAccountOrigin(AccountOrigin.ADMIN_CREATED);
            userRepository.save(user);
        }
    }
}
