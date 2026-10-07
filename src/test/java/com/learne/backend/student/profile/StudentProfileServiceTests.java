package com.learne.backend.student.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.student.profile.dto.StudentProfileResponse;
import com.learne.backend.student.profile.entity.StudentProfile;
import com.learne.backend.student.profile.repository.StudentProfileRepository;
import com.learne.backend.student.profile.service.StudentPreferredSkillJsonService;
import com.learne.backend.student.profile.service.StudentProfileService;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StudentProfileServiceTests {
    @Mock
    private StudentProfileRepository studentProfileRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void getOwnProfileReturnsExistingProfileWhenDefaultCreationRaces() {
        User student = studentUser();
        StudentProfile existingProfile = profileFor(student);
        existingProfile.setBio("Already created");
        when(studentProfileRepository.findByUser(student))
                .thenReturn(Optional.empty(), Optional.of(existingProfile));
        when(studentProfileRepository.saveAndFlush(any(StudentProfile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate student profile"));
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());

        StudentProfileResponse response = service().getOwnProfile(student);

        assertThat(response.bio()).isEqualTo("Already created");
    }

    @Test
    void malformedPreferredSkillsJsonThrowsApiException() {
        User student = studentUser();
        StudentProfile profile = profileFor(student);
        profile.setPreferredSkillsJson("not-json");
        when(studentProfileRepository.findByUser(student)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service().getOwnProfile(student))
                .isInstanceOf(ApiException.class)
                .hasMessage("Student profile data is invalid.");
    }

    private StudentProfileService service() {
        return new StudentProfileService(
                studentProfileRepository,
                new StudentPreferredSkillJsonService(new ObjectMapper()),
                new NoopStorageService(),
                entityManager,
                transactionManager
        );
    }

    private User studentUser() {
        User student = new User();
        student.setEmail("student@example.com");
        student.setFullName("Student User");
        student.setRole(UserRole.STUDENT);
        student.setActive(true);
        return student;
    }

    private StudentProfile profileFor(User student) {
        StudentProfile profile = new StudentProfile();
        profile.setUser(student);
        return profile;
    }

    private static class NoopStorageService implements StorageService {
        @Override
        public StorageUploadResult uploadTeacherAvatar(MultipartFile file) {
            return new StorageUploadResult("https://example.com/teacher-avatar.png", "teacher-avatar-public-id");
        }

        @Override
        public StorageUploadResult uploadStudentAvatar(MultipartFile file) {
            return new StorageUploadResult("https://example.com/student-avatar.png", "student-avatar-public-id");
        }

        @Override
        public StorageUploadResult uploadTeacherIntroductionVideo(MultipartFile file) {
            return new StorageUploadResult("https://example.com/intro.mp4", "video-public-id");
        }

        @Override
        public StorageUploadResult uploadLessonAudio(MultipartFile file) {
            return new StorageUploadResult("https://example.com/audio.mp3", "audio-public-id");
        }

        @Override
        public void delete(String publicId, StorageResourceType resourceType) {
        }
    }
}
