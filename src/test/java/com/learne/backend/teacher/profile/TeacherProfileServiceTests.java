package com.learne.backend.teacher.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class TeacherProfileServiceTests {

    @Mock
    private TeacherProfileRepository teacherProfileRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void getOwnProfileReturnsExistingProfileWhenDefaultCreationRaces() {
        User teacher = teacherUser();
        TeacherProfile existingProfile = profileFor(teacher);
        existingProfile.setBio("Already created");
        existingProfile.setExpertise("Grammar");
        when(teacherProfileRepository.findByUser(teacher))
                .thenReturn(Optional.empty(), Optional.of(existingProfile));
        when(teacherProfileRepository.saveAndFlush(any(TeacherProfile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate user profile"));
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());

        TeacherProfileResponse response = new TeacherProfileService(teacherProfileRepository, entityManager, transactionManager)
                .getOwnProfile(teacher);

        assertThat(response.bio()).isEqualTo("Already created");
        assertThat(response.expertise()).isEqualTo("Grammar");
    }

    @Test
    void updateOwnProfileUpdatesExistingProfileWhenDefaultCreationRaces() {
        User teacher = teacherUser();
        TeacherProfile existingProfile = profileFor(teacher);
        when(teacherProfileRepository.findByUser(teacher))
                .thenReturn(Optional.empty(), Optional.of(existingProfile));
        when(teacherProfileRepository.saveAndFlush(any(TeacherProfile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate user profile"));
        when(teacherProfileRepository.save(any(TeacherProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, TeacherProfile.class));
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());

        TeacherProfileResponse response = new TeacherProfileService(teacherProfileRepository, entityManager, transactionManager)
                .updateOwnProfile(teacher, new UpdateTeacherProfileRequest(
                        "Updated bio",
                        "IELTS",
                        4,
                        null,
                        null));

        assertThat(response.bio()).isEqualTo("Updated bio");
        assertThat(response.expertise()).isEqualTo("IELTS");
        assertThat(response.yearsOfExperience()).isEqualTo(4);
    }

    private User teacherUser() {
        User teacher = new User();
        teacher.setEmail("teacher@example.com");
        teacher.setFullName("Teacher User");
        teacher.setRole(UserRole.TEACHER);
        teacher.setActive(true);
        return teacher;
    }

    private TeacherProfile profileFor(User teacher) {
        TeacherProfile profile = new TeacherProfile();
        profile.setUser(teacher);
        profile.setBio("");
        profile.setExpertise("");
        profile.setYearsOfExperience(0);
        profile.setStatus(TeacherProfileStatus.ACTIVE);
        return profile;
    }
}
