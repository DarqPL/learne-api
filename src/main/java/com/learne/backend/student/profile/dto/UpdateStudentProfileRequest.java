package com.learne.backend.student.profile.dto;

import com.learne.backend.student.profile.enums.StudentCurrentLevel;
import com.learne.backend.student.profile.enums.StudentPreferredSkill;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

public record UpdateStudentProfileRequest(
        @NotBlank @Size(max = 255) String fullName,
        @Size(max = 30) @Pattern(regexp = "^[0-9+()\\- ]*$") String phoneNumber,
        @PastOrPresent LocalDate dateOfBirth,
        @Size(max = 2000) String bio,
        @Size(max = 1000) String learningGoal,
        StudentCurrentLevel currentLevel,
        @Min(0) @Max(1440) Integer dailyLearningMinutes,
        List<StudentPreferredSkill> preferredSkills,
        @NotNull Boolean publicProfileEnabled,
        @NotNull Boolean showEmail,
        @NotNull Boolean showPhoneNumber,
        @NotNull Boolean showDateOfBirth
) {
    public UpdateStudentProfileRequest {
        fullName = trim(fullName);
        phoneNumber = blankToNull(phoneNumber);
        bio = blankToNull(bio);
        learningGoal = blankToNull(learningGoal);
    }

    @AssertTrue
    public boolean hasUniquePreferredSkills() {
        return preferredSkills == null || new HashSet<>(preferredSkills).size() == preferredSkills.size();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
