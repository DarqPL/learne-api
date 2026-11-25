package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.CourseCoverReview;
import com.learne.backend.teacher.content.dto.CourseCoverMediaReference;
import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface CourseCoverReviewRepository extends JpaRepository<CourseCoverReview, Long> {
    Optional<CourseCoverReview> findTopByCourse_IdOrderBySubmittedAtDescIdDesc(Long courseId);

    Page<CourseCoverReview> findByStatusOrderBySubmittedAtAscIdAsc(
            CourseCoverReviewStatus status, Pageable pageable);

    Optional<CourseCoverReview> findByIdAndCourseId(Long id, Long courseId);

    @Query("""
            select new com.learne.backend.teacher.content.dto.CourseCoverMediaReference(
                review.imageUrl, review.imagePublicId)
            from CourseCoverReview review
            where review.course.id = :courseId
            """)
    java.util.List<CourseCoverMediaReference> findCoverMediaReferencesByCourseId(
            @Param("courseId") Long courseId);

    @Query("select review.course.id from CourseCoverReview review where review.id = :id")
    Optional<Long> findCourseIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select review from CourseCoverReview review where review.id = :id")
    Optional<CourseCoverReview> findLockedById(@Param("id") Long id);
}
