package com.learne.backend.teacher.profile;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void updateOwnProfileRequiresTeacherRole() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(put("/api/teacher/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProfileJson()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/teacher/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProfileJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateOwnProfileValidatesRequiredFields() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        mockMvc.perform(put("/api/teacher/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bio": "",
                                  "expertise": "",
                                  "yearsOfExperience": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request."));
    }

    @Test
    void teacherCanCreateAndUpdateOwnProfile() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        mockMvc.perform(put("/api/teacher/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProfileJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.bio").value("Giang vien tieng Anh giao tiep"))
                .andExpect(jsonPath("$.expertise").value("Communication, Grammar"))
                .andExpect(jsonPath("$.yearsOfExperience").value(3))
                .andExpect(jsonPath("$.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.introductionVideoUrl").value("https://example.com/intro.mp4"))
                .andExpect(jsonPath("$.completed").value(true));

        mockMvc.perform(put("/api/teacher/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bio": "Cap nhat gioi thieu",
                                  "expertise": "IELTS Foundation",
                                  "yearsOfExperience": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Cap nhat gioi thieu"))
                .andExpect(jsonPath("$.expertise").value("IELTS Foundation"))
                .andExpect(jsonPath("$.yearsOfExperience").value(4))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.introductionVideoUrl").doesNotExist());

        assertThat(teacherProfileRepository.findAll()).hasSize(1);
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

    private String validProfileJson() {
        return """
                {
                  "bio": "Giang vien tieng Anh giao tiep",
                  "expertise": "Communication, Grammar",
                  "yearsOfExperience": 3,
                  "avatarUrl": "https://example.com/avatar.png",
                  "introductionVideoUrl": "https://example.com/intro.mp4"
                }
                """;
    }
}
