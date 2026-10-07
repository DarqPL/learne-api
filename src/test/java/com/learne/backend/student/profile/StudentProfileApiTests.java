package com.learne.backend.student.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void studentCanGetOwnProfileAndDefaultRowIsCreated() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value(student.getId()))
                .andExpect(jsonPath("$.fullName").value("STUDENT User"))
                .andExpect(jsonPath("$.email").value("student@example.com"))
                .andExpect(jsonPath("$.publicProfileEnabled").value(false))
                .andExpect(jsonPath("$.preferredSkills").doesNotExist());

        assertThat(studentProfileRepository.findByUser(student)).isPresent();
    }

    @Test
    void studentCanUpdateOwnProfileWithFullReplacement() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(put("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": " Nguyen Van A ",
                                  "phoneNumber": " 0901234567 ",
                                  "bio": " Dang hoc tieng Anh giao tiep. ",
                                  "currentLevel": "A2",
                                  "preferredSkills": ["LISTENING", "SPEAKING"],
                                  "publicProfileEnabled": true,
                                  "showEmail": false,
                                  "showPhoneNumber": false,
                                  "showDateOfBirth": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.phoneNumber").value("0901234567"))
                .andExpect(jsonPath("$.bio").value("Dang hoc tieng Anh giao tiep."))
                .andExpect(jsonPath("$.currentLevel").value("A2"))
                .andExpect(jsonPath("$.preferredSkills[0]").value("LISTENING"));
    }

    @Test
    void ownProfileRequiresStudentRole() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        mockMvc.perform(get("/api/student/profile"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
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

    private String loginAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
