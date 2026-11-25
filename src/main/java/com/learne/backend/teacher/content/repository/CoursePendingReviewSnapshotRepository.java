package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.CoursePendingReviewSnapshot;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoursePendingReviewSnapshotRepository
        extends JpaRepository<CoursePendingReviewSnapshot, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select snapshot from CoursePendingReviewSnapshot snapshot where snapshot.courseId = :courseId")
    Optional<CoursePendingReviewSnapshot> findLockedByCourseId(@Param("courseId") Long courseId);
}
