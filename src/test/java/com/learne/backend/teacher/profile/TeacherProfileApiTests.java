package com.learne.backend.teacher.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.teacher.profile.entity.TeacherProfile;
import com.learne.backend.teacher.profile.enums.TeacherProfileStatus;
import com.learne.backend.teacher.profile.repository.TeacherProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MultipartFile;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class TeacherProfileApiTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeacherProfileRepository teacherProfileRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CapturingCloudinaryStorageService cloudinaryStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
        cloudinaryStorageService.clear();
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
                .andExpect(jsonPath("$.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.introductionVideoUrl").value("https://example.com/intro.mp4"));

        assertThat(teacherProfileRepository.findAll()).hasSize(1);
    }

    @Test
    void teacherCanUploadAndDeleteAvatar() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        MockMultipartFile avatar = new MockMultipartFile(
                "file",
                "avatar.png",
                MediaType.IMAGE_PNG_VALUE,
                "avatar-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/teacher/profile/avatar")
                        .file(avatar)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("https://res.cloudinary.com/demo/image/upload/avatar.png"));

        TeacherProfile uploadedProfile = teacherProfileRepository.findByUser(teacher).orElseThrow();
        assertThat(uploadedProfile.getAvatarUrl()).isEqualTo("https://res.cloudinary.com/demo/image/upload/avatar.png");
        assertThat(uploadedProfile.getAvatarPublicId()).isEqualTo("learne/teacher-profiles/avatars/avatar");

        mockMvc.perform(delete("/api/teacher/profile/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        TeacherProfile deletedProfile = teacherProfileRepository.findByUser(teacher).orElseThrow();
        assertThat(deletedProfile.getAvatarUrl()).isNull();
        assertThat(deletedProfile.getAvatarPublicId()).isNull();
        assertThat(cloudinaryStorageService.deletedPublicIds()).contains("learne/teacher-profiles/avatars/avatar");
    }

    @Test
    void teacherCanUploadAndDeleteIntroductionVideo() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        MockMultipartFile video = new MockMultipartFile(
                "file",
                "intro.mp4",
                "video/mp4",
                "video-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/teacher/profile/introduction-video")
                        .file(video)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.introductionVideoUrl").value("https://res.cloudinary.com/demo/video/upload/intro.mp4"));

        TeacherProfile uploadedProfile = teacherProfileRepository.findByUser(teacher).orElseThrow();
        assertThat(uploadedProfile.getIntroductionVideoUrl()).isEqualTo("https://res.cloudinary.com/demo/video/upload/intro.mp4");
        assertThat(uploadedProfile.getIntroductionVideoPublicId()).isEqualTo("learne/teacher-profiles/introduction-videos/intro");

        mockMvc.perform(delete("/api/teacher/profile/introduction-video")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.introductionVideoUrl").doesNotExist());

        TeacherProfile deletedProfile = teacherProfileRepository.findByUser(teacher).orElseThrow();
        assertThat(deletedProfile.getIntroductionVideoUrl()).isNull();
        assertThat(deletedProfile.getIntroductionVideoPublicId()).isNull();
        assertThat(cloudinaryStorageService.deletedPublicIds()).contains("learne/teacher-profiles/introduction-videos/intro");
    }

    @Test
    void profileMediaUploadRequiresTeacherRole() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());
        MockMultipartFile avatar = new MockMultipartFile(
                "file",
                "avatar.png",
                MediaType.IMAGE_PNG_VALUE,
                "avatar-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/teacher/profile/avatar").file(avatar))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/api/teacher/profile/avatar")
                        .file(avatar)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentTeacherListRequiresStudentRole() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        mockMvc.perform(get("/api/teachers"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/teachers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentTeacherDetailRequiresStudentRole() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        mockMvc.perform(get("/api/teachers/%d".formatted(teacher.getId())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/teachers/%d".formatted(teacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentTeacherListReturnsOnlyPublicCompletedProfiles() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User visibleTeacher = saveUser("visible-teacher@example.com", UserRole.TEACHER);
        saveCompletedProfile(visibleTeacher, TeacherProfileStatus.ACTIVE);
        User incompleteTeacher = saveUser("incomplete-teacher@example.com", UserRole.TEACHER);
        saveIncompleteProfile(incompleteTeacher);
        User lockedTeacher = saveUser("locked-teacher@example.com", UserRole.TEACHER);
        lockedTeacher.setLocked(true);
        userRepository.save(lockedTeacher);
        saveCompletedProfile(lockedTeacher, TeacherProfileStatus.ACTIVE);
        User suspendedTeacher = saveUser("suspended-teacher@example.com", UserRole.TEACHER);
        saveCompletedProfile(suspendedTeacher, TeacherProfileStatus.SUSPENDED);
        User inactiveTeacher = saveUser("inactive-teacher@example.com", UserRole.TEACHER);
        inactiveTeacher.setActive(false);
        userRepository.save(inactiveTeacher);
        saveCompletedProfile(inactiveTeacher, TeacherProfileStatus.ACTIVE);
        User nonTeacher = saveUser("non-teacher@example.com", UserRole.STUDENT);
        saveCompletedProfile(nonTeacher, TeacherProfileStatus.ACTIVE);
        User whitespaceBioTeacher = saveUser("whitespace-bio-teacher@example.com", UserRole.TEACHER);
        saveProfile(whitespaceBioTeacher, "   ", "Communication, Grammar", TeacherProfileStatus.ACTIVE);
        User whitespaceExpertiseTeacher = saveUser("whitespace-expertise-teacher@example.com", UserRole.TEACHER);
        saveProfile(whitespaceExpertiseTeacher, "Giang vien tieng Anh giao tiep", "   ", TeacherProfileStatus.ACTIVE);
        User tabNewlineBioTeacher = saveUser("tab-newline-bio-teacher@example.com", UserRole.TEACHER);
        saveProfile(tabNewlineBioTeacher, "\t\n", "Communication, Grammar", TeacherProfileStatus.ACTIVE);
        User tabNewlineExpertiseTeacher = saveUser("tab-newline-expertise-teacher@example.com", UserRole.TEACHER);
        saveProfile(tabNewlineExpertiseTeacher, "Giang vien tieng Anh giao tiep", "\t\n", TeacherProfileStatus.ACTIVE);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/teachers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].teacherId").value(visibleTeacher.getId()))
                .andExpect(jsonPath("$[0].fullName").value("TEACHER User"))
                .andExpect(jsonPath("$[0].email").value("visible-teacher@example.com"))
                .andExpect(jsonPath("$[0].avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$[0].expertise").value("Communication, Grammar"))
                .andExpect(jsonPath("$[0].yearsOfExperience").value(3))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void studentCanViewPublicTeacherDetail() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        saveCompletedProfile(teacher, TeacherProfileStatus.ACTIVE);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/teachers/%d".formatted(teacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.fullName").value("TEACHER User"))
                .andExpect(jsonPath("$.email").value("teacher@example.com"))
                .andExpect(jsonPath("$.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.bio").value("Giang vien tieng Anh giao tiep"))
                .andExpect(jsonPath("$.expertise").value("Communication, Grammar"))
                .andExpect(jsonPath("$.yearsOfExperience").value(3))
                .andExpect(jsonPath("$.introductionVideoUrl").value("https://example.com/intro.mp4"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.mustChangePassword").doesNotExist())
                .andExpect(jsonPath("$.locked").doesNotExist());
    }

    @Test
    void studentTeacherDetailReturnsNotFoundWhenProfileIsNotPublic() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User incompleteTeacher = saveUser("incomplete-teacher@example.com", UserRole.TEACHER);
        saveIncompleteProfile(incompleteTeacher);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/teachers/%d".formatted(incompleteTeacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Teacher not found."));
    }

    @Test
    void studentTeacherDetailReturnsNotFoundWhenProfileHasOnlyWhitespaceRequiredFields() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User whitespaceBioTeacher = saveUser("whitespace-bio-teacher@example.com", UserRole.TEACHER);
        saveProfile(whitespaceBioTeacher, "   ", "Communication, Grammar", TeacherProfileStatus.ACTIVE);
        User whitespaceExpertiseTeacher = saveUser("whitespace-expertise-teacher@example.com", UserRole.TEACHER);
        saveProfile(whitespaceExpertiseTeacher, "Giang vien tieng Anh giao tiep", "   ", TeacherProfileStatus.ACTIVE);
        User tabNewlineBioTeacher = saveUser("tab-newline-bio-teacher@example.com", UserRole.TEACHER);
        saveProfile(tabNewlineBioTeacher, "\t\n", "Communication, Grammar", TeacherProfileStatus.ACTIVE);
        User tabNewlineExpertiseTeacher = saveUser("tab-newline-expertise-teacher@example.com", UserRole.TEACHER);
        saveProfile(tabNewlineExpertiseTeacher, "Giang vien tieng Anh giao tiep", "\t\n", TeacherProfileStatus.ACTIVE);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/teachers/%d".formatted(whitespaceBioTeacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Teacher not found."));

        mockMvc.perform(get("/api/teachers/%d".formatted(whitespaceExpertiseTeacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Teacher not found."));

        mockMvc.perform(get("/api/teachers/%d".formatted(tabNewlineBioTeacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Teacher not found."));

        mockMvc.perform(get("/api/teachers/%d".formatted(tabNewlineExpertiseTeacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Teacher not found."));
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

    private TeacherProfile saveCompletedProfile(User teacher, TeacherProfileStatus status) {
        TeacherProfile profile = new TeacherProfile();
        profile.setUser(teacher);
        profile.setBio("Giang vien tieng Anh giao tiep");
        profile.setExpertise("Communication, Grammar");
        profile.setYearsOfExperience(3);
        profile.setAvatarUrl("https://example.com/avatar.png");
        profile.setIntroductionVideoUrl("https://example.com/intro.mp4");
        profile.setStatus(status);
        return teacherProfileRepository.save(profile);
    }

    private TeacherProfile saveIncompleteProfile(User teacher) {
        return saveProfile(teacher, "", "", TeacherProfileStatus.ACTIVE);
    }

    private TeacherProfile saveProfile(User teacher, String bio, String expertise, TeacherProfileStatus status) {
        TeacherProfile profile = new TeacherProfile();
        profile.setUser(teacher);
        profile.setBio(bio);
        profile.setExpertise(expertise);
        profile.setYearsOfExperience(0);
        profile.setStatus(status);
        return teacherProfileRepository.save(profile);
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

    @TestConfiguration
    static class CloudinaryStorageTestConfiguration {
        @Bean
        @Primary
        CapturingCloudinaryStorageService storageService() {
            return new CapturingCloudinaryStorageService();
        }
    }

    static class CapturingCloudinaryStorageService implements StorageService {
        private final java.util.List<String> deletedPublicIds = new java.util.ArrayList<>();

        @Override
        public StorageUploadResult uploadTeacherAvatar(MultipartFile file) {
            return new StorageUploadResult(
                    "https://res.cloudinary.com/demo/image/upload/avatar.png",
                    "learne/teacher-profiles/avatars/avatar"
            );
        }

        @Override
        public StorageUploadResult uploadStudentAvatar(MultipartFile file) {
            return new StorageUploadResult(
                    "https://res.cloudinary.com/demo/image/upload/student-avatar.png",
                    "learne/student-profiles/avatars/student-avatar"
            );
        }

        @Override
        public StorageUploadResult uploadTeacherIntroductionVideo(MultipartFile file) {
            return new StorageUploadResult(
                    "https://res.cloudinary.com/demo/video/upload/intro.mp4",
                    "learne/teacher-profiles/introduction-videos/intro"
            );
        }

        @Override
        public StorageUploadResult uploadLessonAudio(MultipartFile file) {
            return new StorageUploadResult(
                    "https://res.cloudinary.com/demo/video/upload/audio.mp3",
                    "learne/course-audio/audio"
            );
        }

        @Override
        public void delete(String publicId, StorageResourceType resourceType) {
            deletedPublicIds.add(publicId);
        }

        java.util.List<String> deletedPublicIds() {
            return deletedPublicIds;
        }

        void clear() {
            deletedPublicIds.clear();
        }
    }
}
