package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QuestionDifficultyCalculator {
    public String calculate(String courseLevel, SkillType skillType, String lessonContent, QuestionType questionType,
            String questionText, String correctAnswer, List<String> optionTexts) {
        int score = questionTypeWeight(questionType);
        if (length(questionText) > 120) {
            score++;
        }
        if (length(correctAnswer) > 60) {
            score++;
        }
        if (length(lessonContent) > 600) {
            score++;
        }
        if (nonBlankOptionCount(optionTexts) >= 4) {
            score++;
        }
        score += levelWeight(courseLevel);
        if (skillType == SkillType.LISTENING || skillType == SkillType.SPEAKING) {
            score++;
        }
        if (score <= 2) {
            return "easy";
        }
        if (score <= 5) {
            return "medium";
        }
        return "hard";
    }

    private int questionTypeWeight(QuestionType questionType) {
        return switch (questionType) {
            case MULTIPLE_CHOICE, TRUE_FALSE -> 1;
            case FILL_BLANK, SHORT_ANSWER -> 2;
            case WORD_ORDER, TRANSLATION, PRONUNCIATION -> 3;
        };
    }

    private int length(String value) {
        return value == null ? 0 : value.trim().length();
    }

    private int nonBlankOptionCount(List<String> optionTexts) {
        if (optionTexts == null) {
            return 0;
        }
        return (int) optionTexts.stream()
                .filter(value -> value != null && !value.trim().isBlank())
                .count();
    }

    private int levelWeight(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level.trim().toUpperCase()) {
            case "B1", "B2" -> 1;
            case "C1", "C2" -> 2;
            default -> 0;
        };
    }
}
