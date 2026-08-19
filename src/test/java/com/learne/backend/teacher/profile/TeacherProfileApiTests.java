package com.learne.backend.teacher.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class TeacherProfileApiTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeacherProfileRepository teacherProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        teacherProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void repositoryFindsProfileByTeacherUser() {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        TeacherProfile profile = new TeacherProfile();
        profile.setUser(teacher);
        profile.setBio("Experienced English teacher");
        profile.setExpertise("Grammar");
        profile.setYearsOfExperience(5);
        teacherProfileRepository.save(profile);

        assertThat(teacherProfileRepository.findByUser(teacher)).isPresent();
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }
}
