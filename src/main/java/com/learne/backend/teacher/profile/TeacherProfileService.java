package com.learne.backend.teacher.profile;

import com.learne.backend.domain.auth.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TeacherProfileService {
    private final TeacherProfileRepository teacherProfileRepository;

    TeacherProfileService(TeacherProfileRepository teacherProfileRepository) {
        this.teacherProfileRepository = teacherProfileRepository;
    }

    @Transactional
    TeacherProfileResponse getOwnProfile(User teacher) {
        TeacherProfile profile = teacherProfileRepository.findByUser(teacher)
                .orElseGet(() -> createDefaultProfile(teacher));
        return TeacherProfileResponse.from(profile);
    }

    private TeacherProfile createDefaultProfile(User teacher) {
        TeacherProfile profile = new TeacherProfile();
        profile.setUser(teacher);
        profile.setBio("");
        profile.setExpertise("");
        profile.setYearsOfExperience(0);
        profile.setAvatarUrl(teacher.getAvatarUrl());
        profile.setStatus(TeacherProfileStatus.ACTIVE);
        return teacherProfileRepository.save(profile);
    }
}
