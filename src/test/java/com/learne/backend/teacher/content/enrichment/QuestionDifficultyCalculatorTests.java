package com.learne.backend.teacher.content.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionDifficultyCalculatorTests {
    private final QuestionDifficultyCalculator calculator = new QuestionDifficultyCalculator();
    private final QuestionXpPolicy xpPolicy = new QuestionXpPolicy();

    @Test
    void calculatesEasyForSimpleMultipleChoice() {
        String difficulty = calculator.calculate("A2", SkillType.READING, "short", QuestionType.MULTIPLE_CHOICE,
                "Choose the greeting.", "", List.of("Hello", "Bye"));

        assertThat(difficulty).isEqualTo("easy");
        assertThat(xpPolicy.xpFor(difficulty)).isEqualTo(10);
    }

    @Test
    void calculatesMediumForListeningMultipleChoiceWithFourOptions() {
        String difficulty = calculator.calculate("A2", SkillType.LISTENING, "short", QuestionType.MULTIPLE_CHOICE,
                "What date does the speaker mention?", "", List.of("A", "B", "C", "D"));

        assertThat(difficulty).isEqualTo("medium");
        assertThat(xpPolicy.xpFor(difficulty)).isEqualTo(20);
    }

    @Test
    void calculatesHardForAdvancedProductionQuestion() {
        String longLesson = "x".repeat(601);
        String longQuestion = "q".repeat(121);
        String longAnswer = "a".repeat(61);

        String difficulty = calculator.calculate("C1", SkillType.SPEAKING, longLesson, QuestionType.TRANSLATION,
                longQuestion, longAnswer, List.of());

        assertThat(difficulty).isEqualTo("hard");
        assertThat(xpPolicy.xpFor(difficulty)).isEqualTo(30);
    }

    @Test
    void ignoresNullStringsUnknownLevelsAndBlankOptions() {
        String difficulty = calculator.calculate(null, SkillType.READING, null, QuestionType.TRUE_FALSE,
                null, null, List.of("", "Yes", "   "));

        assertThat(difficulty).isEqualTo("easy");
    }
}
