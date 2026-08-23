package com.learne.backend.admin.user.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.profile.entity.TeacherProfile;
import com.learne.backend.teacher.profile.enums.TeacherProfileStatus;

public record AdminTeacherResponse(
        Long userId,
        String fullName,
        String email,
        boolean locked,
        boolean mustChangePassword,
        TeacherProfileStatus profileStatus,
        boolean completed) {

    public static AdminTeacherResponse from(User user, TeacherProfile profile) {
        return new AdminTeacherResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.isLocked(),
                user.isMustChangePassword(),
                profile != null ? profile.getStatus() : null,
                profile != null && profile.isCompleted());
    }
}
