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
    void parsesRevisionScopedIdsFromContentRows() throws Exception {
        CourseImportModel model = parser.parse(workbookWithRevisionIds());

        assertThat(model.courseId()).isEqualTo(101L);
        assertThat(model.topics().getFirst().topicId()).isEqualTo(201L);
        assertThat(model.topics().getFirst().lessons().getFirst().lessonId()).isEqualTo(301L);
        assertThat(model.topics().getFirst().lessons().getFirst().questions().getFirst().questionId())
                .isEqualTo(401L);
        assertThat(model.topics().getFirst().lessons().getFirst().questions().getFirst().options())
                .extracting(option -> option.optionId())
                .containsExactly(501L, 502L);
    }

    @Test
    void parsesNewExportRowWithBlankHiddenIdsAlongsideExistingRows() throws Exception {
        CourseImportModel model = parser.parse(workbookWithNewRowWithoutIds());

        assertThat(model.topics()).anySatisfy(topic -> {
            assertThat(topic.title()).isEqualTo("New topic");
            assertThat(topic.topicId()).isNull();
            assertThat(topic.lessons().getFirst().lessonId()).isNull();
            assertThat(topic.lessons().getFirst().questions().getFirst().questionId()).isNull();
        });
    }

    @Test
    void retainsFirstPopulatedCourseIdWhenNewRowPrecedesExistingRows() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithLeadingNewRowWithoutIds()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("_course_id");
    }

    @Test
    void rejectsBlankCourseIdOnNewRowInExportStyleWorkbook() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithBlankCourseIdNewRow()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("_course_id");
    }

    @Test
    void rejectsConflictingPopulatedCourseIds() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithConflictingCourseIds()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("_course_id");
    }

    @Test
    void parsesExportedLessonAndQuestionMetadataWhenOptionalColumnsArePresent() throws Exception {
        CourseImportModel model = parser.parse(workbookWithExportMetadata());

        var lesson = model.topics().getFirst().lessons().getFirst();
        var question = lesson.questions().getFirst();
        assertThat(lesson.orderIndex()).isEqualTo(3);
        assertThat(lesson.xpReward()).isEqualTo(25);
        assertThat(lesson.placementTest()).isTrue();
        assertThat(question.orderIndex()).isEqualTo(4);
        assertThat(question.xpReward()).isEqualTo(7);
        assertThat(question.difficulty()).isEqualTo("easy");
        assertThat(question.knowledgePoint()).isEqualTo("greetings");
    }

    @Test
    void rejectsTopicIdAddedAfterAnUnidentifiedRowForTheSameTopic() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithInconsistentIds("101", null, "201", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("_topic_id");
    }

    @Test
    void rejectsLessonIdAddedAfterAnUnidentifiedRowForTheSameLesson() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithInconsistentIds("101", "201", "201", null, "301")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("_lesson_id");
    }

    @Test
    void rejectsZeroRevisionId() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithInconsistentIds("0", null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("_course_id");
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
    void rejectsNonChoiceQuestionWithOptionTextOrOptionId() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithShortAnswerOptionPayload()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("option_a");
    }

    @Test
    void rejectsExportStyleContentWithIncompleteRequiredHiddenIdHeaders() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithIncompleteExportIdHeaders()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("_topic_id");
    }

    @Test
    void rejectsConflictingTopicDescriptionAcrossRepeatedTopicRows() throws Exception {
        assertThatThrownBy(() -> parser.parse(workbookWithConflictingTopicDescription()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("topic_description");
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

    private byte[] workbookWithRevisionIds() throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet content = input.getSheet("Content");
            String[] ids = {"_course_id", "_topic_id", "_lesson_id", "_question_id", "_option_a_id",
                    "_option_b_id", "_option_c_id", "_option_d_id"};
            for (int i = 0; i < ids.length; i++) {
                content.getRow(0).createCell(19 + i).setCellValue(ids[i]);
            }
            for (int rowIndex : new int[] {2, 3}) {
                Row row = content.getRow(rowIndex);
                row.createCell(19).setCellValue("101");
                row.createCell(20).setCellValue("201");
                row.createCell(21).setCellValue("301");
                row.createCell(22).setCellValue(rowIndex == 2 ? "401" : "402");
                if (rowIndex == 2) {
                    row.createCell(23).setCellValue("501");
                    row.createCell(24).setCellValue("502");
                }
            }
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithNewRowWithoutIds() throws Exception {
        byte[] workbook = workbookWithRevisionIds();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet content = input.getSheet("Content");
            row(content, 4, "New topic", "New description", "New lesson", "New lesson description",
                    "New lesson content", "READING", "", "SHORT_ANSWER", "New question", "", "Answer", "", "", "", "", "");
            content.getRow(4).createCell(19).setCellValue("101");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithBlankCourseIdNewRow() throws Exception {
        byte[] workbook = workbookWithNewRowWithoutIds();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Content").getRow(4).getCell(19).setBlank();
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithLeadingNewRowWithoutIds() throws Exception {
        byte[] workbook = workbookWithRevisionIds();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            row(input.getSheet("Content"), 1, "Leading new topic", "New description", "Leading lesson",
                    "New lesson description", "New lesson content", "READING", "", "SHORT_ANSWER",
                    "New question", "", "Answer", "", "", "", "", "");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithConflictingCourseIds() throws Exception {
        byte[] workbook = workbookWithRevisionIds();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Content").getRow(3).getCell(19).setCellValue("999");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithExportMetadata() throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet content = input.getSheet("Content");
            String[] headers = {"lesson_order", "lesson_xp_reward", "lesson_placement_test", "question_order",
                    "question_xp_reward", "difficulty", "knowledge_point"};
            for (int i = 0; i < headers.length; i++) {
                content.getRow(0).createCell(18 + i).setCellValue(headers[i]);
            }
            Row row = content.getRow(2);
            row.createCell(18).setCellValue(3);
            row.createCell(19).setCellValue(25);
            row.createCell(20).setCellValue(true);
            row.createCell(21).setCellValue(4);
            row.createCell(22).setCellValue(7);
            row.createCell(23).setCellValue("easy");
            row.createCell(24).setCellValue("greetings");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithInconsistentIds(String courseId, String firstTopicId, String secondTopicId,
            String firstLessonId, String secondLessonId) throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet content = input.getSheet("Content");
            String[] ids = {"_course_id", "_topic_id", "_lesson_id", "_question_id", "_option_a_id",
                    "_option_b_id", "_option_c_id", "_option_d_id"};
            for (int i = 0; i < ids.length; i++) {
                content.getRow(0).createCell(19 + i).setCellValue(ids[i]);
            }
            Row first = content.getRow(2);
            Row second = content.getRow(3);
            if (courseId != null) {
                first.createCell(19).setCellValue(courseId);
                second.createCell(19).setCellValue(courseId);
            }
            if (firstTopicId != null) {
                first.createCell(20).setCellValue(firstTopicId);
            }
            if (secondTopicId != null) {
                second.createCell(20).setCellValue(secondTopicId);
            }
            if (firstLessonId != null) {
                first.createCell(21).setCellValue(firstLessonId);
            }
            if (secondLessonId != null) {
                second.createCell(21).setCellValue(secondLessonId);
            }
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

    private byte[] workbookWithShortAnswerOptionPayload() throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row row = input.getSheet("Content").getRow(3);
            row.createCell(11).setCellValue("Not allowed");
            row.createCell(18).setCellValue("501");
            row.createCell(19).setCellValue("401");
            input.getSheet("Content").getRow(0).createCell(18).setCellValue("_option_a_id");
            input.getSheet("Content").getRow(0).createCell(19).setCellValue("_question_id");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithIncompleteExportIdHeaders() throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet content = input.getSheet("Content");
            content.getRow(0).createCell(18).setCellValue("_course_id");
            content.getRow(0).createCell(20).setCellValue("_lesson_id");
            content.getRow(0).createCell(21).setCellValue("_question_id");
            content.getRow(2).createCell(18).setCellValue("101");
            input.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithConflictingTopicDescription() throws Exception {
        byte[] workbook = validWorkbook();
        try (XSSFWorkbook input = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.getSheet("Content").getRow(3).getCell(1).setCellValue("Different topic description");
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
