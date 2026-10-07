package com.learne.backend.student.profile.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.profile.dto.StudentProfileResponse;
import com.learne.backend.student.profile.dto.UpdateStudentProfileRequest;
import com.learne.backend.student.profile.entity.StudentProfile;
import com.learne.backend.student.profile.repository.StudentProfileRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StudentProfileService {
    private final StudentProfileRepository studentProfileRepository;
    private final StudentPreferredSkillJsonService preferredSkillJsonService;
    private final EntityManager entityManager;
    private final TransactionTemplate createProfileTransactionTemplate;

    public StudentProfileService(StudentProfileRepository studentProfileRepository,
                                 StudentPreferredSkillJsonService preferredSkillJsonService,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.studentProfileRepository = studentProfileRepository;
        this.preferredSkillJsonService = preferredSkillJsonService;
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
