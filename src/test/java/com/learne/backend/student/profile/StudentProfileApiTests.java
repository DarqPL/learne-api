package com.learne.backend.student.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.profile.entity.StudentProfile;
import com.learne.backend.student.profile.enums.StudentCurrentLevel;
import com.learne.backend.student.profile.enums.StudentPreferredSkill;
import com.learne.backend.student.profile.repository.StudentProfileRepository;
import com.learne.backend.student.profile.service.StudentPreferredSkillJsonService;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class StudentProfileApiTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StudentProfileRepository studentProfileRepository;

    @Autowired
    private StudentPreferredSkillJsonService preferredSkillJsonService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void repositoryFindsProfileByStudentUser() {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        StudentProfile profile = new StudentProfile();
        profile.setUser(student);
        profile.setPhoneNumber("0901234567");
        profile.setDateOfBirth(LocalDate.of(2003, 5, 10));
        profile.setBio("Dang hoc tieng Anh giao tiep.");
        profile.setLearningGoal("Dat B1 trong 6 thang");
        profile.setCurrentLevel(StudentCurrentLevel.A2);
        profile.setDailyLearningMinutes(45);
        profile.setPreferredSkillsJson("[\"LISTENING\",\"SPEAKING\"]");
        studentProfileRepository.save(profile);

        assertThat(studentProfileRepository.findByUser(student)).isPresent();
        assertThat(studentProfileRepository.findVisibleByStudentId(student.getId())).isEmpty();

        profile.setPublicProfileEnabled(true);
        studentProfileRepository.save(profile);

        assertThat(studentProfileRepository.findVisibleByStudentId(student.getId())).isPresent();
    }

    @Test
    void preferredSkillsJsonRoundTripsAndRejectsMalformedJson() {
        String json = preferredSkillJsonService.toJson(List.of(
                StudentPreferredSkill.LISTENING,
                StudentPreferredSkill.SPEAKING
        ));

        assertThat(json).isEqualTo("[\"LISTENING\",\"SPEAKING\"]");
        assertThat(preferredSkillJsonService.fromJson(json))
                .containsExactly(StudentPreferredSkill.LISTENING, StudentPreferredSkill.SPEAKING);
        assertThat(preferredSkillJsonService.fromJson(null)).isNull();
        assertThat(preferredSkillJsonService.fromJson("[]")).isEmpty();
        assertThatThrownBy(() -> preferredSkillJsonService.fromJson("not-json"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Student profile data is invalid.");
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
