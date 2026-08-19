package com.learne.backend.admin.user;

import com.learne.backend.auth.session.RefreshTokenService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.profile.TeacherProfile;
import com.learne.backend.teacher.profile.TeacherProfileRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminUserService {

    private final UserRepository userRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final TeacherInvitationMailService teacherInvitationMailService;

    AdminUserService(
            UserRepository userRepository,
            TeacherProfileRepository teacherProfileRepository,
            RefreshTokenService refreshTokenService,
            PasswordEncoder passwordEncoder,
            TemporaryPasswordGenerator temporaryPasswordGenerator,
            TeacherInvitationMailService teacherInvitationMailService) {
        this.userRepository = userRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.teacherInvitationMailService = teacherInvitationMailService;
    }

    @Transactional(readOnly = true)
    List<AdminTeacherResponse> listTeachers() {
        return userRepository.findByRoleOrderByCreatedAtAsc(UserRole.TEACHER)
                .stream()
                .map(user -> AdminTeacherResponse.from(user, teacherProfileRepository.findByUser(user).orElse(null)))
                .toList();
    }

    @Transactional
    void lockUser(Long userId, User currentAdmin) {
        if (currentAdmin.getId().equals(userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Admin cannot lock their own account.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
        user.setLocked(true);
        user.setLockedAt(OffsetDateTime.now());
        userRepository.save(user);
        refreshTokenService.revokeAllActiveTokens(user);
    }

    @Transactional
    void unlockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
        user.setLocked(false);
        user.setLockedAt(null);
        userRepository.save(user);
    }

    @Transactional
    User createTeacher(CreateTeacherRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered.");
        }

        String temporaryPassword = temporaryPasswordGenerator.generate();
        User teacher = new User();
        teacher.setEmail(email);
        teacher.setFullName(request.fullName().trim());
        teacher.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        teacher.setRole(UserRole.TEACHER);
        teacher.setActive(true);
        teacher.setLocked(false);
        teacher.setMustChangePassword(true);

        User savedTeacher = userRepository.save(teacher);
        teacherInvitationMailService.sendTeacherInvitation(savedTeacher.getEmail(), savedTeacher.getFullName(), temporaryPassword);
        return savedTeacher;
    }

    private String normalizeEmail(String email) {
        return email.toLowerCase().trim();
    }
}
