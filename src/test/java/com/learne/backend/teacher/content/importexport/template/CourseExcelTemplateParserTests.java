package com.learne.backend.teacher.content.importexport.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CourseExcelTemplateParserTests {
    private final CourseExcelTemplateParser parser = new CourseExcelTemplateParser();

    @Test
    void parsesXlsmTemplateSheetsAndAssignsOrderFromContentRows() throws Exception {
        CourseImportModel model = parser.parse(validWorkbook());

        assertThat(model.name()).isEqualTo("English A1");
        assertThat(model.type()).isEqualTo("GRAMMAR");
        assertThat(model.orderIndex()).isZero();
        assertThat(model.topics()).hasSize(1);
        assertThat(model.topics().getFirst().orderIndex()).isEqualTo(1);
        assertThat(model.topics().getFirst().title()).isEqualTo("Greetings");
        assertThat(model.topics().getFirst().lessons()).hasSize(1);
        assertThat(model.topics().getFirst().lessons().getFirst().orderIndex()).isEqualTo(1);
        assertThat(model.topics().getFirst().lessons().getFirst().placementTest()).isFalse();
        assertThat(model.topics().getFirst().lessons().getFirst().xpReward()).isZero();
        assertThat(model.topics().getFirst().lessons().getFirst().audioFile()).isEqualTo("audio/hello.mp3");
        assertThat(model.topics().getFirst().lessons().getFirst().questions()).hasSize(2);
        assertThat(model.topics().getFirst().lessons().getFirst().questions().getFirst().orderIndex()).isEqualTo(1);
        assertThat(model.topics().getFirst().lessons().getFirst().questions().getFirst().xpReward()).isZero();
    }

    @Test
    void parsesContentTopicWithoutTopicSheetReference() throws Exception {
        CourseImportModel model = parser.parse(workbookWithContentTopic("Unknown topic"));

        assertThat(model.topics()).anySatisfy(topic -> assertThat(topic.title()).isEqualTo("Unknown topic"));
    }

    @Test
    void rejectsMismatchedLessonContentForSameLesson() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithSecondQuestionLessonContent("Different content")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("lesson_content");
    }

    @Test
    void rejectsMismatchedLessonSkillTypeForSameLesson() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithSecondQuestionSkillType("LISTENING")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("skill_type");
    }

    @Test
    void ignoresUnreferencedLessonHelperRows() throws Exception {
        CourseImportModel model = parser.parse(workbookWithUnreferencedLesson());

        assertThat(model.topics()).hasSize(1);
        assertThat(model.topics().getFirst().lessons()).hasSize(1);
        assertThat(model.topics().getFirst().lessons().getFirst().title()).isEqualTo("Say hello");
    }

    @Test
    void parsesContentWhenHelperTopicAndLessonKeysAreInconsistent() throws Exception {
        CourseImportModel model = parser.parse(workbookWithBrokenHelperSheetKeys());

        assertThat(model.topics()).hasSize(1);
        assertThat(model.topics().getFirst().title()).isEqualTo("Greetings");
        assertThat(model.topics().getFirst().lessons()).hasSize(1);
        assertThat(model.topics().getFirst().lessons().getFirst().title()).isEqualTo("Say hello");
    }

    @Test
    void rejectsNonChoiceQuestionWithCorrectOption() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithShortAnswerCorrectOption("A")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("correct_option");
    }

    @Test
    void parsesCanonicalXlsmTemplateIgnoringSampleRows() throws Exception {
        CourseImportModel model = parser.parse(Files.readAllBytes(Path.of("course-template.xlsm")));

        assertThat(model.name()).isEqualTo("Template");
        assertThat(model.type()).isEqualTo("GRAMMAR");
        assertThat(model.level()).isEqualTo("A1");
        assertThat(model.topics()).isNotEmpty();
    }

    @Test
    void rejectsLegacyFlatWorkbookWithoutMetadata() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            row(workbook.createSheet("Content"), 0, "course_name", "course_description");
            workbook.write(output);

            assertThatThrownBy(() -> parser.parse(output.toByteArray()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("_metadata");
        }
    }

    private byte[] validWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            row(workbook.createSheet("_metadata"), 0, "_template_version", "v1");
            row(workbook.getSheet("_metadata"), 1, "_template_type", "flat_course_content");

            Sheet course = workbook.createSheet("Course");
            row(course, 0, "course_name", "English A1");
            row(course, 1, "course_description", "Basic course");
            row(course, 2, "course_type", "GRAMMAR");
            row(course, 3, "course_level", "A1");

            Sheet topics = workbook.createSheet("Topics");
            row(topics, 0, "topic_key", "topic_title", "topic_description");
            row(topics, 1, "T001", "Greetings", "Greeting topic");

            Sheet lessons = workbook.createSheet("Lessons");
            row(lessons, 0, "lesson_key", "topic_key", "lesson_title", "lesson_description",
                    "lesson_content", "topic_title");
            row(lessons, 1, "L001", "T001", "Say hello", "Greeting lesson", "Hello means xin chao.", "Greetings");

            Sheet content = workbook.createSheet("Content");
            row(content, 0, "topic_title", "topic_description", "lesson_title", "lesson_description",
                    "lesson_content", "skill_type", "audio_file", "question_type", "question_text", "explanation",
                    "correct_answer", "option_a", "option_b", "option_c", "option_d", "correct_option",
                    "_row_fingerprint");
            row(content, 1, "Greetings", "Greeting topic", "Say hello", "Greeting lesson",
                    "Hello means xin chao.", "GRAMMAR", "", "MULTIPLE_CHOICE", "Sample?", "", "",
                    "A", "B", "", "", "A", "SAMPLE_ROW");
            row(content, 2, "Greetings", "Greeting topic", "Say hello", "Greeting lesson",
                    "Hello means xin chao.", "GRAMMAR", "audio/hello.mp3", "MULTIPLE_CHOICE",
                    "What does Hello mean?", "Hello is a greeting.", "", "Xin chao", "Tam biet", "", "", "A", "");
            row(content, 3, "Greetings", "Greeting topic", "Say hello", "Greeting lesson",
                    "Hello means xin chao.", "GRAMMAR", "", "SHORT_ANSWER", "Write hello", "", "Hello",
                    "", "", "", "", "", "");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithContentTopic(String topic) throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Content").getRow(2).getCell(0).setCellValue(topic);
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithSecondQuestionLessonContent(String contentValue) throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Content").getRow(3).createCell(4).setCellValue(contentValue);
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithSecondQuestionSkillType(String skillType) throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Content").getRow(3).getCell(5).setCellValue(skillType);
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithUnreferencedLesson() throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            row(input.getSheet("Lessons"), 2, "L002", "T001", "Unused lesson", "Unused description",
                    "Unused content", "Greetings");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithBrokenHelperSheetKeys() throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Lessons").getRow(1).getCell(1).setCellValue("BROKEN_TOPIC_KEY");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithShortAnswerCorrectOption(String correctOption) throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Content").getRow(3).createCell(15).setCellValue(correctOption);
            input.write(output);
            return output.toByteArray();
        }
    }

    private void row(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
