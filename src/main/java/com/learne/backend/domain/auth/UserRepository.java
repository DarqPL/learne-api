package com.learne.backend.domain.auth;

import com.learne.backend.admin.user.dto.AdminStudentSummaryResponse;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndRole(Long id, UserRole role);

    long countByRole(UserRole role);

    long countByRoleAndLocked(UserRole role, boolean locked);

    List<User> findByRoleOrderByCreatedAtAsc(UserRole role);

    @Query("select user from User user where user.role = :role order by user.createdAt asc, user.id asc")
    List<User> findUsersByRole(@Param("role") UserRole role);

    @Query("""
            select new com.learne.backend.admin.user.dto.AdminStudentSummaryResponse(
                user.id,
                user.fullName,
                user.email,
                user.locked,
                user.mustChangePassword,
                coalesce(stats.totalXp, 0),
                coalesce(stats.currentStreakDays, 0),
                coalesce(stats.longestStreakDays, 0),
                stats.lastActivityDate,
                (select count(enrollment) from CourseEnrollment enrollment where enrollment.user = user),
                (select count(progress) from LessonProgress progress where progress.user = user and progress.status = :completedStatus)
            )
            from User user
            left join UserStats stats on stats.user = user
            where user.role = :role
            order by user.createdAt asc, user.id asc
            """)
    List<AdminStudentSummaryResponse> findStudentSummaries(
            @Param("role") UserRole role,
            @Param("completedStatus") LessonProgressStatus completedStatus);
}
