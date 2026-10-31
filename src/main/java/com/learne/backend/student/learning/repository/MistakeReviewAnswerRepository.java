package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.MistakeReviewAnswer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MistakeReviewAnswerRepository extends JpaRepository<MistakeReviewAnswer, Long> {
    @Query("""
            select answer from MistakeReviewAnswer answer
            join fetch answer.sessionQuestion membership
            join fetch membership.session session
            left join fetch membership.question question
            where session.user = :user
            order by answer.answerEventOrder desc
            """)
    List<MistakeReviewAnswer> findByUserOrderByAnswerEventOrderDesc(@Param("user") User user);
}
