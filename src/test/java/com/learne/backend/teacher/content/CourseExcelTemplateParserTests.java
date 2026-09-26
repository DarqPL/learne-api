package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.model.LessonImportModel;
import com.learne.backend.teacher.content.importexport.model.QuestionImportModel;
import com.learne.backend.teacher.content.importexport.model.TopicImportModel;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CourseExcelTemplateParserTests {
    private static final String[] HEADERS = {
            "course_name", "course_description", "course_type", "course_level", "course_order_index",
            "topic_order", "topic_title", "topic_description",
            "lesson_order", "lesson_title", "lesson_description", "lesson_content", "skill_type", "xp_reward",
            "placement_test", "audio_file",
            "question_order", "question_type", "question_text", "explanation", "correct_answer",
            "option_a", "option_b", "option_c", "option_d", "correct_option", "difficulty", "knowledge_point",
            "_course_id", "_topic_id", "_lesson_id", "_question_id", "_option_a_id", "_option_b_id",
            "_option_c_id", "_option_d_id", "_row_fingerprint"
    };

    @Test
    void parseGroupsRowsWithSameCourseTopicAndLessonIntoQuestionList() throws Exception {
        byte[] workbook = workbookBytes();

        CourseImportModel model = new CourseExcelTemplateParser().parse(workbook);

        assertThat(model.courseId()).isEqualTo(101L);
        assertThat(model.name()).isEqualTo("English Basics");
        assertThat(model.description()).isEqualTo("Basic course");
        assertThat(model.type()).isEqualTo("VOCABULARY");
        assertThat(model.level()).isEqualTo("BEGINNER");
        assertThat(model.orderIndex()).isEqualTo(1);
        assertThat(model.topics()).hasSize(1);

        TopicImportModel topic = model.topics().get(0);
        assertThat(topic.topicId()).isEqualTo(201L);
        assertThat(topic.orderIndex()).isEqualTo(1);
        assertThat(topic.title()).isEqualTo("Greetings");
        assertThat(topic.description()).isEqualTo("Greeting words");
        assertThat(topic.lessons()).hasSize(1);

        LessonImportModel lesson = topic.lessons().get(0);
        assertThat(lesson.lessonId()).isEqualTo(301L);
        assertThat(lesson.orderIndex()).isEqualTo(1);
        assertThat(lesson.title()).isEqualTo("Hello");
        assertThat(lesson.description()).isEqualTo("First lesson");
        assertThat(lesson.content()).isEqualTo("Say hello");
        assertThat(lesson.skillType()).isEqualTo("LISTENING");
        assertThat(lesson.xpReward()).isEqualTo(10);
        assertThat(lesson.placementTest()).isFalse();
        assertThat(lesson.audioFile()).isEqualTo("audio/hello.mp3");
        assertThat(lesson.questions()).hasSize(2);

        var questions = lesson.questions();
        QuestionImportModel firstQuestion = questions.get(0);
        assertThat(firstQuestion.questionId()).isEqualTo(401L);
        assertThat(firstQuestion.orderIndex()).isEqualTo(1);
        assertThat(firstQuestion.type()).isEqualTo("multiple_choice");
        assertThat(firstQuestion.questionText()).isEqualTo("What is hello?");
        assertThat(firstQuestion.explanation()).isEqualTo("Choose the greeting translation");
        assertThat(firstQuestion.correctAnswer()).isEqualTo("A");
        assertThat(firstQuestion.knowledgePoint()).isEqualTo("greetings");
        assertThat(firstQuestion.difficulty()).isEqualTo("EASY");
        assertThat(firstQuestion.xpReward()).isEqualTo(10);
        assertThat(firstQuestion.options()).hasSize(2);
        assertThat(firstQuestion.options().get(0).optionId()).isEqualTo(501L);
        assertThat(firstQuestion.options().get(0).text()).isEqualTo("A");
        assertThat(firstQuestion.options().get(0).correct()).isTrue();
        assertThat(firstQuestion.options().get(0).orderIndex()).isEqualTo(1);
        assertThat(firstQuestion.options().get(1).optionId()).isEqualTo(502L);
        assertThat(firstQuestion.options().get(1).text()).isEqualTo("B");
        assertThat(firstQuestion.options().get(1).correct()).isFalse();
        assertThat(firstQuestion.options().get(1).orderIndex()).isEqualTo(2);

        QuestionImportModel secondQuestion = questions.get(1);
        assertThat(secondQuestion.questionId()).isEqualTo(402L);
        assertThat(secondQuestion.orderIndex()).isEqualTo(2);
        assertThat(secondQuestion.type()).isEqualTo("short_answer");
        assertThat(secondQuestion.questionText()).isEqualTo("Translate bye");
        assertThat(secondQuestion.explanation()).isEqualTo("Type the English word");
        assertThat(secondQuestion.correctAnswer()).isEqualTo("bye");
        assertThat(secondQuestion.knowledgePoint()).isEqualTo("farewells");
        assertThat(secondQuestion.difficulty()).isEqualTo("MEDIUM");
        assertThat(secondQuestion.xpReward()).isEqualTo(10);
        assertThat(secondQuestion.options()).hasSize(2);
        assertThat(secondQuestion.options().get(0).optionId()).isEqualTo(503L);
        assertThat(secondQuestion.options().get(0).text()).isEqualTo("bye");
        assertThat(secondQuestion.options().get(0).correct()).isTrue();
        assertThat(secondQuestion.options().get(0).orderIndex()).isEqualTo(1);
        assertThat(secondQuestion.options().get(1).optionId()).isEqualTo(504L);
        assertThat(secondQuestion.options().get(1).text()).isEqualTo("hello");
        assertThat(secondQuestion.options().get(1).correct()).isFalse();
        assertThat(secondQuestion.options().get(1).orderIndex()).isEqualTo(2);
    }

    @Test
    void parseRejectsInvalidWorkbookBytesAsBadRequest() {
        byte[] invalidWorkbook = "not an xlsx workbook".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new CourseExcelTemplateParser().parse(invalidWorkbook))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception).hasMessageContaining("File Excel không hợp lệ");
                });
    }

    @Test
    void parseRejectsNonIntegerHiddenIdAsBadRequest() throws Exception {
        byte[] workbook = workbookBytesWithHiddenId("_lesson_id", "1.9");

        assertThatThrownBy(() -> new CourseExcelTemplateParser().parse(workbook))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception).hasMessageContaining("ID ẩn không hợp lệ");
                });
    }

    @Test
    void parseRejectsTextHiddenIdAsBadRequest() throws Exception {
        byte[] workbook = workbookBytesWithHiddenId("_question_id", "abc");

        assertThatThrownBy(() -> new CourseExcelTemplateParser().parse(workbook))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception).hasMessageContaining("ID ẩn không hợp lệ");
                });
    }

    private byte[] workbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Content");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }

            createDataRow(sheet, 1, 401, 501, 502, 1, "multiple_choice", "What is hello?",
                    "Choose the greeting translation", "A", "A", "B", "EASY", "greetings");
            createDataRow(sheet, 2, 402, 503, 504, 2, "short_answer", "Translate bye",
                    "Type the English word", "bye", "bye", "hello", "MEDIUM", "farewells");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookBytesWithHiddenId(String header, String value) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes()));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getSheet("Content").getRow(1).createCell(indexOf(header)).setCellValue(value);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void createDataRow(Sheet sheet, int rowIndex, long questionId, long optionAId, long optionBId,
            int questionOrder, String questionType, String questionText, String explanation, String correctAnswer,
            String optionA, String optionB, String difficulty, String knowledgePoint) {
        Row row = sheet.createRow(rowIndex);
        set(row, "course_name", "English Basics");
        set(row, "course_description", "Basic course");
        set(row, "course_type", "VOCABULARY");
        set(row, "course_level", "BEGINNER");
        set(row, "course_order_index", "1");
        set(row, "topic_order", "1");
        set(row, "topic_title", "Greetings");
        set(row, "topic_description", "Greeting words");
        set(row, "lesson_order", "1");
        set(row, "lesson_title", "Hello");
        set(row, "lesson_description", "First lesson");
        set(row, "lesson_content", "Say hello");
        set(row, "skill_type", "LISTENING");
        set(row, "xp_reward", "10");
        set(row, "placement_test", "false");
        set(row, "audio_file", "audio/hello.mp3");
        set(row, "question_order", Integer.toString(questionOrder));
        set(row, "question_type", questionType);
        set(row, "question_text", questionText);
        set(row, "explanation", explanation);
        set(row, "correct_answer", correctAnswer);
        set(row, "option_a", optionA);
        set(row, "option_b", optionB);
        set(row, "correct_option", "A");
        set(row, "difficulty", difficulty);
        set(row, "knowledge_point", knowledgePoint);
        set(row, "_course_id", "101");
        set(row, "_topic_id", "201");
        set(row, "_lesson_id", "301");
        set(row, "_question_id", Long.toString(questionId));
        set(row, "_option_a_id", Long.toString(optionAId));
        set(row, "_option_b_id", Long.toString(optionBId));
    }

    private void set(Row row, String header, String value) {
        row.createCell(indexOf(header)).setCellValue(value);
    }

    private int indexOf(String header) {
        for (int i = 0; i < HEADERS.length; i++) {
            if (HEADERS[i].equals(header)) {
                return i;
            }
        }
        throw new IllegalArgumentException(header);
    }
}
