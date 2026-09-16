package com.learne.backend.ai.grammar.repository;

import com.learne.backend.ai.grammar.entity.GrammarCheck;
import com.learne.backend.domain.auth.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GrammarCheckRepository extends JpaRepository<GrammarCheck, Long> {
    Page<GrammarCheck> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    @Query("""
            select grammarCheck from GrammarCheck grammarCheck
            where grammarCheck.user = :user
            order by grammarCheck.createdAt desc, grammarCheck.id desc
            """)
    List<GrammarCheck> findRecentByUser(@Param("user") User user, Pageable pageable);

    @EntityGraph(attributePaths = {"errors", "course", "lesson", "question", "enrollment"})
    Optional<GrammarCheck> findByIdAndUser(Long id, User user);
}
