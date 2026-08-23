package com.learne.backend.teacher.profile.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.profile.dto.TeacherProfileResponse;
import com.learne.backend.teacher.profile.dto.TeacherProfileSummaryResponse;
import com.learne.backend.teacher.profile.dto.UpdateTeacherProfileRequest;
import com.learne.backend.teacher.profile.entity.TeacherProfile;
import com.learne.backend.teacher.profile.enums.TeacherProfileStatus;
import com.learne.backend.teacher.profile.repository.TeacherProfileRepository;
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
public class TeacherProfileService {
    private final TeacherProfileRepository teacherProfileRepository;
    private final StorageService storageService;
    private final EntityManager entityManager;
    private final TransactionTemplate createProfileTransactionTemplate;

    public TeacherProfileService(TeacherProfileRepository teacherProfileRepository,
                                 StorageService storageService,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.teacherProfileRepository = teacherProfileRepository;
        this.storageService = storageService;
        this.entityManager = entityManager;
        this.createProfileTransactionTemplate = new TransactionTemplate(transactionManager);
        this.createProfileTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public TeacherProfileResponse getOwnProfile(User teacher) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        return TeacherProfileResponse.from(profile);
    }

    @Transactional
    public TeacherProfileResponse updateOwnProfile(User teacher, UpdateTeacherProfileRequest request) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        profile.setBio(request.bio());
        profile.setExpertise(request.expertise());
        profile.setYearsOfExperience(request.yearsOfExperience());
        if (request.avatarUrl() != null) {
            profile.setAvatarUrl(request.avatarUrl());
            profile.setAvatarPublicId(null);
        }
        if (request.introductionVideoUrl() != null) {
            profile.setIntroductionVideoUrl(request.introductionVideoUrl());
            profile.setIntroductionVideoPublicId(null);
        }
        return TeacherProfileResponse.from(teacherProfileRepository.save(profile));
    }

    @Transactional
    public TeacherProfileResponse updateAvatar(User teacher, org.springframework.web.multipart.MultipartFile file) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        String oldPublicId = profile.getAvatarPublicId();
        StorageUploadResult uploadResult = storageService.uploadTeacherAvatar(file);
        profile.setAvatarUrl(uploadResult.url());
        profile.setAvatarPublicId(uploadResult.publicId());
        TeacherProfile savedProfile = teacherProfileRepository.save(profile);
        deleteOldMedia(oldPublicId, StorageResourceType.IMAGE);
        return TeacherProfileResponse.from(savedProfile);
    }

    @Transactional
    public TeacherProfileResponse deleteAvatar(User teacher) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        String oldPublicId = profile.getAvatarPublicId();
        profile.setAvatarUrl(null);
        profile.setAvatarPublicId(null);
        TeacherProfile savedProfile = teacherProfileRepository.save(profile);
        deleteOldMedia(oldPublicId, StorageResourceType.IMAGE);
        return TeacherProfileResponse.from(savedProfile);
    }

    @Transactional
    public TeacherProfileResponse updateIntroductionVideo(User teacher, org.springframework.web.multipart.MultipartFile file) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        String oldPublicId = profile.getIntroductionVideoPublicId();
        StorageUploadResult uploadResult = storageService.uploadTeacherIntroductionVideo(file);
        profile.setIntroductionVideoUrl(uploadResult.url());
        profile.setIntroductionVideoPublicId(uploadResult.publicId());
        TeacherProfile savedProfile = teacherProfileRepository.save(profile);
        deleteOldMedia(oldPublicId, StorageResourceType.VIDEO);
        return TeacherProfileResponse.from(savedProfile);
    }

    @Transactional
    public TeacherProfileResponse deleteIntroductionVideo(User teacher) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> findOrCreateDefaultProfile(teacher));
        String oldPublicId = profile.getIntroductionVideoPublicId();
        profile.setIntroductionVideoUrl(null);
        profile.setIntroductionVideoPublicId(null);
        TeacherProfile savedProfile = teacherProfileRepository.save(profile);
        deleteOldMedia(oldPublicId, StorageResourceType.VIDEO);
        return TeacherProfileResponse.from(savedProfile);
    }

    @Transactional(readOnly = true)
    public List<TeacherProfileSummaryResponse> listPublicProfiles() {
        return teacherProfileRepository.findPublicProfiles().stream()
                .filter(TeacherProfile::isCompleted)
                .map(TeacherProfileSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeacherProfileResponse getPublicProfile(Long teacherId) {
        TeacherProfile profile = teacherProfileRepository.findPublicProfileByTeacherId(teacherId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Teacher not found."));
        if (!profile.isCompleted()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Teacher not found.");
        }
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

    private void deleteOldMedia(String publicId, StorageResourceType resourceType) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        storageService.delete(publicId, resourceType);
    }
}
