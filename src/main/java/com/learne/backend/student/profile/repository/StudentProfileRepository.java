package com.learne.backend.student.profile.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.profile.entity.StudentProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, Long> {
    Optional<StudentProfile> findByUser(User user);

    @Query("""
            select profile from StudentProfile profile
            join fetch profile.user user
            where user.id = :studentId
              and user.role = com.learne.backend.domain.auth.UserRole.STUDENT
              and user.active = true
              and user.locked = false
              and profile.publicProfileEnabled = true
            """)
    Optional<StudentProfile> findVisibleByStudentId(@Param("studentId") Long studentId);
}
