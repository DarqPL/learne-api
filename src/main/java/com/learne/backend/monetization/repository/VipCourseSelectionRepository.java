package com.learne.backend.monetization.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.entity.VipCourseSelection;
import com.learne.backend.teacher.content.entity.Course;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VipCourseSelectionRepository extends JpaRepository<VipCourseSelection, Long> {
    @Query("""
            select count(selection) > 0
            from VipCourseSelection selection
            where selection.user = :user
              and selection.course = :course
              and selection.subscription = :subscription
              and selection.replacedAt is null
            """)
    boolean existsActiveSelection(@Param("user") User user,
                                  @Param("course") Course course,
                                  @Param("subscription") Subscription subscription);

    long countBySubscriptionAndLevelAndReplacedAtIsNull(Subscription subscription, String level);

    List<VipCourseSelection> findBySubscriptionAndReplacedAtIsNullOrderBySelectedAtAsc(Subscription subscription);
}
