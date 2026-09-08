package com.learne.backend.ai.grammar.repository;

import com.learne.backend.ai.grammar.entity.GrammarCheck;
import com.learne.backend.ai.grammar.entity.GrammarCheckError;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrammarCheckErrorRepository extends JpaRepository<GrammarCheckError, Long> {
    List<GrammarCheckError> findByGrammarCheckOrderBySequenceOrderAsc(GrammarCheck grammarCheck);
    long countByGrammarCheck(GrammarCheck grammarCheck);
}
