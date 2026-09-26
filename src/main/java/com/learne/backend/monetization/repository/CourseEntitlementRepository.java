package com.learne.backend.monetization.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.entity.CourseEntitlement;
import com.learne.backend.teacher.content.entity.Course;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseEntitlementRepository extends JpaRepository<CourseEntitlement, Long> {
    @Query("""
            select count(entitlement) > 0
            from CourseEntitlement entitlement
            where entitlement.user = :user
              and entitlement.course = :course
              and entitlement.revokedAt is null
              and entitlement.startsAt <= :now
              and (entitlement.endsAt is null or entitlement.endsAt > :now)
            """)
    boolean existsActiveCourseAccess(@Param("user") User user, @Param("course") Course course, @Param("now") Instant now);
}
