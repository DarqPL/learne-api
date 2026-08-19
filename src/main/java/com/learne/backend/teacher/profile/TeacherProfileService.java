package com.learne.backend.teacher.profile;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TeacherProfileService {
    private final TeacherProfileRepository teacherProfileRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate createProfileTransactionTemplate;

    TeacherProfileService(TeacherProfileRepository teacherProfileRepository,
                          EntityManager entityManager,
                          PlatformTransactionManager transactionManager) {
        this.teacherProfileRepository = teacherProfileRepository;
        this.entityManager = entityManager;
        this.createProfileTransactionTemplate = new TransactionTemplate(transactionManager);
        this.createProfileTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    TeacherProfileResponse getOwnProfile(User teacher) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        return TeacherProfileResponse.from(profile);
    }

    @Transactional
    TeacherProfileResponse updateOwnProfile(User teacher, UpdateTeacherProfileRequest request) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        profile.setBio(request.bio());
        profile.setExpertise(request.expertise());
        profile.setYearsOfExperience(request.yearsOfExperience());
        profile.setAvatarUrl(request.avatarUrl());
        profile.setIntroductionVideoUrl(request.introductionVideoUrl());
        return TeacherProfileResponse.from(teacherProfileRepository.save(profile));
    }

    @Transactional(readOnly = true)
    List<TeacherProfileSummaryResponse> listPublicProfiles() {
        return teacherProfileRepository.findPublicProfiles().stream()
                .map(TeacherProfileSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    TeacherProfileResponse getPublicProfile(Long teacherId) {
        TeacherProfile profile = teacherProfileRepository.findPublicProfileByTeacherId(teacherId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Teacher not found."));
        return TeacherProfileResponse.from(profile);
    }

    private TeacherProfile findOrCreateDefaultProfile(User teacher) {
        try {
            return createProfileTransactionTemplate.execute(status -> createDefaultProfile(teacher));
        } catch (DataIntegrityViolationException ex) {
            entityManager.clear();
            return teacherProfileRepository.findByUser(teacher)
                    .orElseThrow(() -> ex);
        }
    }

    private TeacherProfile createDefaultProfile(User teacher) {
        TeacherProfile profile = new TeacherProfile();
        profile.setUser(teacher);
        profile.setBio("");
        profile.setExpertise("");
        profile.setYearsOfExperience(0);
        profile.setAvatarUrl(teacher.getAvatarUrl());
        profile.setStatus(TeacherProfileStatus.ACTIVE);
        return teacherProfileRepository.saveAndFlush(profile);
    }
}
