package com.learne.backend.admin.user.service;

import com.learne.backend.admin.user.dto.AdminStudentAiConversationResponse;
import com.learne.backend.admin.user.dto.AdminStudentDetailResponse;
import com.learne.backend.admin.user.dto.AdminStudentEnrollmentResponse;
import com.learne.backend.admin.user.dto.AdminStudentGrammarCheckResponse;
import com.learne.backend.admin.user.dto.AdminStudentQuizAttemptResponse;
import com.learne.backend.admin.user.dto.AdminStudentSummaryResponse;
import com.learne.backend.admin.user.dto.AdminTeacherResponse;
import com.learne.backend.admin.user.dto.CreateTeacherRequest;
import com.learne.backend.ai.grammar.repository.GrammarCheckRepository;
import com.learne.backend.ai.tutor.repository.AiConversationRepository;
import com.learne.backend.auth.session.service.RefreshTokenService;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.dto.StudentStatsResponse;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import com.learne.backend.teacher.profile.repository.TeacherProfileRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final TeacherInvitationMailService teacherInvitationMailService;
    private final UserStatsRepository userStatsRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final GrammarCheckRepository grammarCheckRepository;
    private final AiConversationRepository aiConversationRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public AdminUserService(
            UserRepository userRepository,
            TeacherProfileRepository teacherProfileRepository,
            RefreshTokenService refreshTokenService,
            PasswordEncoder passwordEncoder,
            TemporaryPasswordGenerator temporaryPasswordGenerator,
            TeacherInvitationMailService teacherInvitationMailService,
            UserStatsRepository userStatsRepository,
            CourseEnrollmentRepository courseEnrollmentRepository,
            QuizAttemptRepository quizAttemptRepository,
            GrammarCheckRepository grammarCheckRepository,
            AiConversationRepository aiConversationRepository,
            CacheInvalidationService cacheInvalidationService) {
        this.userRepository = userRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.teacherInvitationMailService = teacherInvitationMailService;
        this.userStatsRepository = userStatsRepository;
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.grammarCheckRepository = grammarCheckRepository;
        this.aiConversationRepository = aiConversationRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Transactional(readOnly = true)
    public List<AdminTeacherResponse> listTeachers() {
        return userRepository.findByRoleOrderByCreatedAtAsc(UserRole.TEACHER)
                .stream()
                .map(user -> AdminTeacherResponse.from(user, teacherProfileRepository.findByUser(user).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminStudentSummaryResponse> listStudents() {
        return userRepository.findStudentSummaries(UserRole.STUDENT, LessonProgressStatus.COMPLETED);
    }

    @Transactional(readOnly = true)
    public AdminStudentDetailResponse getStudent(Long userId) {
        User student = userRepository.findByIdAndRole(userId, UserRole.STUDENT)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Student not found."));
        StudentStatsResponse stats = userStatsRepository.findByUser(student)
                .map(StudentStatsResponse::from)
                .orElseGet(this::emptyStats);
        PageRequest recentLimit = PageRequest.of(0, 5);

        return new AdminStudentDetailResponse(
                student.getId(),
                student.getFullName(),
                student.getEmail(),
                student.isLocked(),
                student.isMustChangePassword(),
                stats,
                courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student).stream()
                        .limit(5)
                        .map(enrollment -> new AdminStudentEnrollmentResponse(
                                enrollment.getId(),
                                enrollment.getCourse().getId(),
                                enrollment.getCourse().getName(),
                                enrollment.getStatus(),
                                enrollment.getProgressPercent(),
                                enrollment.getEnrolledAt()))
                        .toList(),
                quizAttemptRepository.findRecentByUser(student, recentLimit).stream()
                        .map(attempt -> new AdminStudentQuizAttemptResponse(
                                attempt.getId(),
                                attempt.getLesson().getTitle(),
                                attempt.getScorePercent(),
                                attempt.isPassed(),
                                attempt.getSubmittedAt()))
                        .toList(),
                grammarCheckRepository.findRecentByUser(student, recentLimit).stream()
                        .map(grammarCheck -> new AdminStudentGrammarCheckResponse(
                                grammarCheck.getId(),
                                grammarCheck.getInputText(),
                                null,
                                grammarCheck.getCreatedAt()))
                        .toList(),
                aiConversationRepository.findByUserOrderByLastMessageAtDescIdDesc(student, recentLimit).stream()
                        .map(conversation -> new AdminStudentAiConversationResponse(
                                conversation.getId(),
                                conversation.getTitle(),
                                conversation.getPracticeType(),
                                conversation.getUpdatedAt()))
                        .toList());
    }

    @Transactional
    public void lockUser(Long userId, User currentAdmin) {
        if (currentAdmin.getId().equals(userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Admin cannot lock their own account.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
        user.setLocked(true);
        user.setLockedAt(OffsetDateTime.now());
        userRepository.save(user);
        refreshTokenService.revokeAllActiveTokens(user);
        evictLockCaches(user);
    }

    @Transactional
    public void unlockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
        user.setLocked(false);
        user.setLockedAt(null);
        userRepository.save(user);
        evictLockCaches(user);
    }

    private void evictLockCaches(User user) {
        cacheInvalidationService.evictAdminDashboard();
        if (user.getRole() == UserRole.STUDENT) {
            cacheInvalidationService.evictAccessForStudent(user.getId());
            cacheInvalidationService.evictStudentLearning(user.getId());
        }
    }

    @Transactional
    public User createTeacher(CreateTeacherRequest request) {
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
        teacher.setAccountOrigin(AccountOrigin.ADMIN_CREATED);

        User savedTeacher = userRepository.save(teacher);
        teacherInvitationMailService.sendTeacherInvitation(savedTeacher.getEmail(), savedTeacher.getFullName(), temporaryPassword);
        cacheInvalidationService.evictAdminDashboard();
        return savedTeacher;
    }

    private String normalizeEmail(String email) {
        return email.toLowerCase().trim();
    }

    private StudentStatsResponse emptyStats() {
        return new StudentStatsResponse(0, 0, 0, null);
    }
}
