package com.learne.backend.student.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.dto.StorageDownloadResult;
import com.learne.backend.storage.service.StorageService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

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

    @Autowired
    private CapturingStudentStorageService storageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
        storageService.clear();
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
    void profileUpdateRejectsFutureBirthDate() throws Exception {
        User student = saveUser("future-dob@example.com", UserRole.STUDENT);
        String token = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(put("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson("\"dateOfBirth\": \"2099-01-01\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileUpdateRejectsDailyLearningMinutesOutsideRange() throws Exception {
        User student = saveUser("minutes@example.com", UserRole.STUDENT);
        String token = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(put("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson("\"dailyLearningMinutes\": 1441")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileUpdateRejectsDuplicatePreferredSkills() throws Exception {
        User student = saveUser("duplicate-skills@example.com", UserRole.STUDENT);
        String token = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(put("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson("\"preferredSkills\": [\"LISTENING\", \"LISTENING\"]")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileUpdateRejectsMissingPrivacyFlags() throws Exception {
        User student = saveUser("missing-flags@example.com", UserRole.STUDENT);
        String token = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(put("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Student",
                                  "preferredSkills": [],
                                  "publicProfileEnabled": true,
                                  "showEmail": true,
                                  "showPhoneNumber": true
                                }
                                """))
                .andExpect(status().isBadRequest());
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

    @Test
    void publicProfileAppliesVisibilityAndSensitiveFieldFlags() throws Exception {
        User viewer = saveUser("viewer@example.com", UserRole.STUDENT);
        User target = saveUser("target@example.com", UserRole.STUDENT);
        String viewerToken = loginAndGetAccessToken(viewer.getEmail());

        mockMvc.perform(get("/api/students/%d/profile".formatted(target.getId())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/students/%d/profile".formatted(target.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Student profile not found."));

        mockMvc.perform(put("/api/student/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(target.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Target Student",
                                  "phoneNumber": "0901234567",
                                  "dateOfBirth": "2003-05-10",
                                  "bio": "Dang hoc giao tiep.",
                                  "learningGoal": "Dat B1 trong 6 thang",
                                  "currentLevel": "A2",
                                  "preferredSkills": ["LISTENING", "SPEAKING"],
                                  "publicProfileEnabled": true,
                                  "showEmail": false,
                                  "showPhoneNumber": false,
                                  "showDateOfBirth": false
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/students/%d/profile".formatted(target.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Target Student"))
                .andExpect(jsonPath("$.bio").value("Dang hoc giao tiep."))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phoneNumber").doesNotExist());
    }

    @Test
    void publicProfileReturnsSensitiveFieldsWhenEnabled() throws Exception {
        User viewer = saveUser("visible-viewer@example.com", UserRole.STUDENT);
        User target = saveUser("visible-target@example.com", UserRole.STUDENT);
        StudentProfile profile = visibleProfile(target);
        profile.setShowEmail(true);
        profile.setShowPhoneNumber(true);
        profile.setShowDateOfBirth(true);
        studentProfileRepository.save(profile);

        mockMvc.perform(get("/api/students/%d/profile".formatted(target.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(viewer.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(target.getEmail()))
                .andExpect(jsonPath("$.phoneNumber").value("0901234567"))
                .andExpect(jsonPath("$.dateOfBirth").value("2003-05-10"));
    }

    @Test
    void publicProfileHidesInactiveLockedAndNonStudentTargets() throws Exception {
        User viewer = saveUser("state-viewer@example.com", UserRole.STUDENT);
        String viewerToken = loginAndGetAccessToken(viewer.getEmail());
        User inactive = saveUser("inactive@example.com", UserRole.STUDENT);
        inactive.setActive(false);
        userRepository.save(inactive);
        User locked = saveUser("locked@example.com", UserRole.STUDENT);
        locked.setLocked(true);
        userRepository.save(locked);
        User teacher = saveUser("public-teacher@example.com", UserRole.TEACHER);

        visibleProfile(inactive);
        visibleProfile(locked);
        visibleProfile(teacher);

        mockMvc.perform(get("/api/students/%d/profile".formatted(inactive.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/students/%d/profile".formatted(locked.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/students/%d/profile".formatted(teacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentCanUploadReplaceAndDeleteAvatar() throws Exception {
        User student = saveUser("avatar@example.com", UserRole.STUDENT);
        String token = loginAndGetAccessToken(student.getEmail());
        MockMultipartFile avatar = new MockMultipartFile(
                "file", "avatar.png", MediaType.IMAGE_PNG_VALUE, "avatar-bytes".getBytes());

        mockMvc.perform(multipart("/api/student/profile/avatar").file(avatar)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("https://res.cloudinary.com/demo/image/upload/student-avatar-1.png"));

        mockMvc.perform(multipart("/api/student/profile/avatar").file(avatar)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("https://res.cloudinary.com/demo/image/upload/student-avatar-2.png"));

        assertThat(storageService.deletedPublicIds())
                .contains("learne/student-profiles/avatars/student-avatar-1");

        mockMvc.perform(delete("/api/student/profile/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        mockMvc.perform(delete("/api/student/profile/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());
    }

    @Test
    void avatarCleanupFailureDoesNotFailSuccessfulMutation() throws Exception {
        User student = saveUser("avatar-cleanup@example.com", UserRole.STUDENT);
        String token = loginAndGetAccessToken(student.getEmail());
        MockMultipartFile avatar = validPngAvatar();

        mockMvc.perform(multipart("/api/student/profile/avatar").file(avatar)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        storageService.failDeletes = true;

        mockMvc.perform(multipart("/api/student/profile/avatar").file(avatar)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("https://res.cloudinary.com/demo/image/upload/student-avatar-2.png"));
    }

    private String validUpdateJson(String override) {
        return """
                {
                  "fullName": "Student",
                  "preferredSkills": [],
                  "publicProfileEnabled": false,
                  "showEmail": false,
                  "showPhoneNumber": false,
                  "showDateOfBirth": false,
                  %s
                }
                """.formatted(override);
    }

    private MockMultipartFile validPngAvatar() {
        return new MockMultipartFile(
                "file", "avatar.png", MediaType.IMAGE_PNG_VALUE,
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }

    private StudentProfile visibleProfile(User target) {
        StudentProfile profile = new StudentProfile();
        profile.setUser(target);
        profile.setPhoneNumber("0901234567");
        profile.setDateOfBirth(LocalDate.of(2003, 5, 10));
        profile.setBio("Public bio");
        profile.setPublicProfileEnabled(true);
        profile.setPreferredSkillsJson("[]");
        return studentProfileRepository.save(profile);
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

    @TestConfiguration
    static class StudentStorageTestConfiguration {
        @Bean
        @Primary
        CapturingStudentStorageService storageService() {
            return new CapturingStudentStorageService();
        }
    }

    static class CapturingStudentStorageService implements StorageService {
        private final java.util.List<String> deletedPublicIds = new java.util.ArrayList<>();
        private int studentAvatarUploads;
        private boolean failDeletes;

        @Override
        public StorageUploadResult uploadTeacherAvatar(MultipartFile file) {
            return new StorageUploadResult("https://example.com/teacher-avatar.png", "teacher-avatar");
        }

        @Override
        public StorageUploadResult uploadStudentAvatar(MultipartFile file) {
            studentAvatarUploads++;
            return new StorageUploadResult(
                    "https://res.cloudinary.com/demo/image/upload/student-avatar-%d.png".formatted(studentAvatarUploads),
                    "learne/student-profiles/avatars/student-avatar-%d".formatted(studentAvatarUploads));
        }

        @Override
        public StorageUploadResult uploadTeacherIntroductionVideo(MultipartFile file) {
            return new StorageUploadResult("https://example.com/intro.mp4", "intro");
        }

        @Override
        public StorageUploadResult uploadLessonAudio(MultipartFile file) {
            return new StorageUploadResult("https://example.com/audio.mp3", "audio");
        }

        @Override
        public StorageDownloadResult downloadLessonAudio(String publicId, String fallbackUrl) {
            return new StorageDownloadResult("audio/mpeg", new byte[0]);
        }

        @Override
        public void delete(String publicId, StorageResourceType resourceType) {
            if (failDeletes) {
                throw new RuntimeException("storage cleanup failed");
            }
            deletedPublicIds.add(publicId);
        }

        java.util.List<String> deletedPublicIds() {
            return deletedPublicIds;
        }

        void clear() {
            deletedPublicIds.clear();
            studentAvatarUploads = 0;
            failDeletes = false;
        }
    }
}
