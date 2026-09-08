package com.learne.backend.ai.grammar.repository;

import com.learne.backend.ai.grammar.entity.GrammarCheck;
import com.learne.backend.domain.auth.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrammarCheckRepository extends JpaRepository<GrammarCheck, Long> {
    Page<GrammarCheck> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    @EntityGraph(attributePaths = {"errors", "course", "lesson", "question", "enrollment"})
    Optional<GrammarCheck> findByIdAndUser(Long id, User user);
}
