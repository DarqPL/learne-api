package com.learne.backend.teacher.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void getOwnProfileRequiresTeacherRole() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);

        mockMvc.perform(get("/api/teacher/profile"))
                .andExpect(status().isUnauthorized());

        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/teacher/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherGetOwnProfileCreatesEmptyProfileWhenMissing() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        mockMvc.perform(get("/api/teacher/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.fullName").value("TEACHER User"))
                .andExpect(jsonPath("$.email").value("teacher@example.com"))
                .andExpect(jsonPath("$.bio").value(""))
                .andExpect(jsonPath("$.expertise").value(""))
                .andExpect(jsonPath("$.yearsOfExperience").value(0))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.introductionVideoUrl").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.completed").value(false));

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
