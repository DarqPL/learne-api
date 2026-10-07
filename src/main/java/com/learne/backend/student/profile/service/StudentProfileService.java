package com.learne.backend.student.profile.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.profile.dto.StudentProfileResponse;
import com.learne.backend.student.profile.dto.PublicStudentProfileResponse;
import com.learne.backend.student.profile.dto.UpdateStudentProfileRequest;
import com.learne.backend.student.profile.entity.StudentProfile;
import com.learne.backend.student.profile.repository.StudentProfileRepository;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StudentProfileService {
    private final StudentProfileRepository studentProfileRepository;
    private final StudentPreferredSkillJsonService preferredSkillJsonService;
    private final EntityManager entityManager;
    private final StorageService storageService;
    private final TransactionTemplate createProfileTransactionTemplate;

    public StudentProfileService(StudentProfileRepository studentProfileRepository,
                                 StudentPreferredSkillJsonService preferredSkillJsonService,
                                 StorageService storageService,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.studentProfileRepository = studentProfileRepository;
        this.preferredSkillJsonService = preferredSkillJsonService;
        this.storageService = storageService;
        this.entityManager = entityManager;
        this.createProfileTransactionTemplate = new TransactionTemplate(transactionManager);
        this.createProfileTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public StudentProfileResponse getOwnProfile(User student) {
        StudentProfile profile = studentProfileRepository.findByUser(student)
                .orElseGet(() -> findOrCreateDefaultProfile(student));
        return toOwnResponse(profile);
    }

    @Transactional
    public StudentProfileResponse updateOwnProfile(User student, UpdateStudentProfileRequest request) {
        StudentProfile profile = studentProfileRepository.findByUser(student)
                .orElseGet(() -> findOrCreateDefaultProfile(student));
        student.setFullName(request.fullName());
        profile.getUser().setFullName(request.fullName());
        entityManager.merge(profile.getUser());
        profile.setPhoneNumber(request.phoneNumber());
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setBio(request.bio());
        profile.setLearningGoal(request.learningGoal());
        profile.setCurrentLevel(request.currentLevel());
        profile.setDailyLearningMinutes(request.dailyLearningMinutes());
        profile.setPreferredSkillsJson(preferredSkillJsonService.toJson(request.preferredSkills()));
        profile.setPublicProfileEnabled(request.publicProfileEnabled());
        profile.setShowEmail(request.showEmail());
        profile.setShowPhoneNumber(request.showPhoneNumber());
        profile.setShowDateOfBirth(request.showDateOfBirth());
        StudentProfile savedProfile = studentProfileRepository.save(profile);
        entityManager.flush();
        return toOwnResponse(studentProfileRepository.findById(savedProfile.getId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public PublicStudentProfileResponse getPublicProfile(Long studentId) {
        StudentProfile profile = studentProfileRepository.findVisibleByStudentId(studentId)
                .orElseThrow(() -> new com.learne.backend.shared.api.ApiException(
                        HttpStatus.NOT_FOUND, "Student profile not found."));
        return PublicStudentProfileResponse.from(
                profile, preferredSkillJsonService.fromJson(profile.getPreferredSkillsJson()));
    }

    @Transactional
    public StudentProfileResponse updateAvatar(User student, MultipartFile file) {
        StudentProfile profile = studentProfileRepository.findByUser(student)
                .orElseGet(() -> findOrCreateDefaultProfile(student));
        String oldPublicId = profile.getAvatarPublicId();
        StorageUploadResult uploadResult = storageService.uploadStudentAvatar(file);
        boolean rollbackCleanupRegistered = registerUploadedMediaRollbackCleanup(uploadResult.publicId());
        try {
            profile.setAvatarUrl(uploadResult.url());
            profile.setAvatarPublicId(uploadResult.publicId());
            student.setAvatarUrl(uploadResult.url());
            profile.getUser().setAvatarUrl(uploadResult.url());
            StudentProfile savedProfile = studentProfileRepository.save(profile);
            deleteOldMediaAfterCommit(oldPublicId);
            return toOwnResponse(savedProfile);
        } catch (RuntimeException ex) {
            if (!rollbackCleanupRegistered) {
                deleteOldMediaBestEffort(uploadResult.publicId());
            }
            throw ex;
        }
    }

    @Transactional
    public StudentProfileResponse deleteAvatar(User student) {
        StudentProfile profile = studentProfileRepository.findByUser(student)
                .orElseGet(() -> findOrCreateDefaultProfile(student));
        String oldPublicId = profile.getAvatarPublicId();
        profile.setAvatarUrl(null);
        profile.setAvatarPublicId(null);
        student.setAvatarUrl(null);
        profile.getUser().setAvatarUrl(null);
        StudentProfile savedProfile = studentProfileRepository.save(profile);
        deleteOldMediaAfterCommit(oldPublicId);
        return toOwnResponse(savedProfile);
    }

    private void deleteOldMediaAfterCommit(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteOldMediaBestEffort(publicId);
                }
            });
            return;
        }
        deleteOldMediaBestEffort(publicId);
    }

    private boolean registerUploadedMediaRollbackCleanup(String publicId) {
        if (publicId == null || publicId.isBlank()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteOldMediaBestEffort(publicId);
                }
            }
        });
        return true;
    }

    private void deleteOldMediaBestEffort(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            storageService.delete(publicId, StorageResourceType.IMAGE);
        } catch (RuntimeException ignored) {
            // The profile state is already persisted; cleanup is best effort.
        }
    }

    private StudentProfileResponse toOwnResponse(StudentProfile profile) {
        return StudentProfileResponse.from(profile, preferredSkillJsonService.fromJson(profile.getPreferredSkillsJson()));
    }

    private StudentProfile findOrCreateDefaultProfile(User student) {
        try {
            return createProfileTransactionTemplate.execute(status -> createDefaultProfile(student));
        } catch (DataIntegrityViolationException ex) {
            entityManager.clear();
            return studentProfileRepository.findByUser(student).orElseThrow(() -> ex);
        }
    }

    private StudentProfile createDefaultProfile(User student) {
        StudentProfile profile = new StudentProfile();
        profile.setUser(student);
        profile.setAvatarUrl(student.getAvatarUrl());
        return studentProfileRepository.saveAndFlush(profile);
    }
}
