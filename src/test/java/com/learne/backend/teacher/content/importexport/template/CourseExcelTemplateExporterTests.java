package com.learne.backend.teacher.content.importexport.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CourseExcelTemplateExporterTests {
    @Test
    void canonicalTemplateCopiesContainAllRequiredHiddenContentHeaders() throws Exception {
        List<String> requiredHeaders = List.of("_course_id", "_topic_id", "_lesson_id", "_question_id",
                "_option_a_id", "_option_b_id", "_option_c_id", "_option_d_id", "_row_fingerprint");

        for (Path path : List.of(Path.of("course-template.xlsm"), Path.of("src/main/resources/course-template.xlsm"))) {
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(path)))) {
                var content = workbook.getSheet("Content");
                for (int index = 0; index < requiredHeaders.size(); index++) {
                    int column = 28 + index;
                    assertThat(content.getRow(0).getCell(column).getStringCellValue()).isEqualTo(requiredHeaders.get(index));
                    assertThat(content.isColumnHidden(column)).as("%s column %d", path, column).isTrue();
                }
            }
        }
    }

    @Test
    void exportContentHeadersMatchCanonicalTemplateAndOmitOptionalMetadataHeaders() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();

        byte[] actual = exporter.exportCourse(revisionWithOneQuestion());

        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(actual));
                XSSFWorkbook template = new XSSFWorkbook(new ByteArrayInputStream(exporter.blankTemplate()))) {
            var exportedHeader = exported.getSheet("Content").getRow(0);
            var templateHeader = template.getSheet("Content").getRow(0);
            List<String> expectedHeaders = java.util.stream.IntStream.range(0, templateHeader.getLastCellNum())
                    .mapToObj(column -> templateHeader.getCell(column) == null ? ""
                            : templateHeader.getCell(column).getStringCellValue())
                    .toList();
            List<String> actualHeaders = java.util.stream.IntStream.range(0, exportedHeader.getLastCellNum())
                    .mapToObj(column -> exportedHeader.getCell(column) == null ? ""
                            : exportedHeader.getCell(column).getStringCellValue())
                    .toList();

            assertThat(actualHeaders).containsExactlyElementsOf(expectedHeaders);
            assertThat(actualHeaders).doesNotContain("lesson_order", "lesson_xp_reward", "lesson_placement_test",
                    "question_order", "question_xp_reward", "difficulty", "knowledge_point");
        }
    }

    @Test
    void blankTemplateReturnsXlsmWithoutStaleCalculationChain() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();

        byte[] actual = exporter.blankTemplate();

        assertThat(actual).containsSequence("vbaProject.bin".getBytes(StandardCharsets.UTF_8));
        assertThat(hasZipEntry(actual, "xl/calcChain.xml")).isFalse();
        assertThat(zipEntryText(actual, "[Content_Types].xml")).doesNotContain("calcChain");
        assertThat(zipEntryText(actual, "xl/_rels/workbook.xml.rels")).doesNotContain("calcChain");
    }

    @Test
    void exportCourseUsesCanonicalXlsmTemplateAndWritesContentWithHiddenIds() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();
        CourseRevision revision = revisionWithOneQuestion();
        byte[] canonicalTemplate = exporter.blankTemplate();

        byte[] actual = exporter.exportCourse(revision);

        assertThat(hasZipEntry(actual, "xl/vbaProject.bin")).isTrue();
        assertThat(zipEntryBytes(actual, "xl/vbaProject.bin")).isEqualTo(zipEntryBytes(canonicalTemplate, "xl/vbaProject.bin"));
        assertThat(hasZipEntry(actual, "xl/calcChain.xml")).isFalse();
        assertThat(zipEntryText(actual, "[Content_Types].xml")).doesNotContain("calcChain");
        assertThat(zipEntryText(actual, "xl/_rels/workbook.xml.rels")).doesNotContain("calcChain");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(actual));
                XSSFWorkbook template = new XSSFWorkbook(new ByteArrayInputStream(exporter.blankTemplate()))) {
            assertThat(sheetNames(workbook)).containsExactlyElementsOf(sheetNames(template));
            assertThat(workbook.getAllNames().stream().map(name -> name.getNameName()).collect(Collectors.toSet()))
                    .contains("Course_Level_Choice", "Course_Type_Choice", "Question_Type_Choice", "TopicKeyList",
                            "TopicTitleList", "LessonChoices_2").doesNotContain("LessonChoices_3");
            assertThat(workbook.getSheet("HuongDan").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo(template.getSheet("HuongDan").getRow(0).getCell(0).getStringCellValue());

            var content = workbook.getSheet("Content");
            assertThat(content.getRow(0).getCell(16)).isNull();
            assertThat(content.getRow(0).getCell(22)).isNull();
            assertThat(content.getRow(0).getCell(28).getStringCellValue()).isEqualTo("_course_id");
            assertThat(content.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Exported topic");
            assertThat(content.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Exported lesson");
            assertThat(content.getRow(1).getCell(8).getStringCellValue()).isEqualTo("Exported question");
            assertThat(content.getRow(1).getCell(9).getStringCellValue()).isEqualTo("Explanation");
            assertThat(content.getRow(1).getCell(11).getStringCellValue()).isEqualTo("Option A");
            assertThat(content.getRow(1).getCell(16)).isNull();
            assertThat(content.getRow(1).getCell(22)).isNull();
            assertThat(content.getRow(1).getCell(6).getStringCellValue()).isEqualTo("https://audio.example/lesson.mp3");
            assertThat(content.getRow(1).getCell(28).getNumericCellValue()).isEqualTo(10);
            assertThat(content.getRow(1).getCell(36).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(content.isColumnHidden(28)).isTrue();
            assertThat(content.isColumnHidden(36)).isTrue();
            assertThat(workbook.getSheet("Course").getRow(0).getCell(1).getStringCellValue()).isEqualTo("Exported course");
            assertThat(workbook.getSheet("Topics").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Exported topic");
            assertThat(workbook.getSheet("Lessons").getRow(1).getCell(2).getStringCellValue()).isEqualTo("Exported lesson");
            assertThat(content.getRow(1).getCell(0).getCellStyle().getIndex())
                    .isEqualTo(template.getSheet("Content").getRow(1).getCell(0).getCellStyle().getIndex());
            assertThat(content.getRow(0).getCell(0).getCellStyle().getIndex())
                    .isEqualTo(template.getSheet("Content").getRow(0).getCell(0).getCellStyle().getIndex());
        }

        var imported = new CourseExcelTemplateParser().parse(actual);
        assertThat(imported.topics().getFirst().lessons().getFirst().xpReward()).isZero();
        assertThat(imported.topics().getFirst().lessons().getFirst().placementTest()).isFalse();
        assertThat(imported.topics().getFirst().lessons().getFirst().questions().getFirst().knowledgePoint())
                .isNull();
        assertThat(imported.topics().getFirst().topicId()).isEqualTo(20L);
        assertThat(imported.topics().getFirst().lessons().getFirst().lessonId()).isEqualTo(30L);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(actual))) {
            assertThat(workbook.getSheet("Topics").getRow(1).getCell(0).getStringCellValue()).isEqualTo("T20");
            assertThat(workbook.getSheet("Lessons").getRow(1).getCell(0).getStringCellValue()).isEqualTo("L30");
            assertThat(workbook.getSheet("Lessons").getRow(1).getCell(1).getStringCellValue()).isEqualTo("T20");
        }
    }

    @Test
    void exportCoursePreservesTopicsAndLessonsHeadersAndTableRanges() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();
        byte[] actual = exporter.exportCourse(revisionWithOneQuestion());

        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(actual));
                XSSFWorkbook template = new XSSFWorkbook(new ByteArrayInputStream(exporter.blankTemplate()))) {
            assertThat(exported.getSheet("Topics").getRow(0).getCell(0).getStringCellValue()).isEqualTo("topic_key");
            assertThat(exported.getSheet("Topics").getRow(0).getCell(1).getStringCellValue()).isEqualTo("topic_title");
            assertThat(exported.getSheet("Lessons").getRow(0).getCell(0).getStringCellValue()).isEqualTo("lesson_key");
            assertThat(exported.getSheet("Lessons").getRow(0).getCell(2).getStringCellValue()).isEqualTo("lesson_title");
            assertThat(exported.getSheet("Topics").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo(template.getSheet("Topics").getRow(0).getCell(0).getStringCellValue());
            assertThat(exported.getSheet("Lessons").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo(template.getSheet("Lessons").getRow(0).getCell(0).getStringCellValue());

            var topicsTable = ((org.apache.poi.xssf.usermodel.XSSFSheet) exported.getSheet("Topics"))
                    .getTables().getFirst();
            var lessonsTable = ((org.apache.poi.xssf.usermodel.XSSFSheet) exported.getSheet("Lessons"))
                    .getTables().getFirst();
            assertThat(topicsTable.getArea().getFirstCell().getRow()).isZero();
            assertThat(topicsTable.getArea().getLastCell().getRow()).isEqualTo(1);
            assertThat(lessonsTable.getArea().getFirstCell().getRow()).isZero();
            assertThat(lessonsTable.getArea().getLastCell().getRow()).isEqualTo(1);
            assertThat(topicsTable.getName()).isEqualTo("tblTopics");
            assertThat(lessonsTable.getName()).isEqualTo("tblLessons");
            assertThat(exported.getName("TopicTitleList").getRefersToFormula())
                    .isEqualTo(template.getName("TopicTitleList").getRefersToFormula());
            assertThat(exported.getName("TopicKeyList").getRefersToFormula())
                    .isEqualTo(template.getName("TopicKeyList").getRefersToFormula());
        }
    }

    @Test
    void exportCourseForcesFullAutomaticRecalculationAndRefreshesLessonChoices() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();

        byte[] actual = exporter.exportCourse(revisionWithOneQuestion());

        assertThat(zipEntryText(actual, "xl/workbook.xml")).contains("calcMode=\"auto\"")
                .contains("fullCalcOnLoad=\"true\"").contains("forceFullCalc=\"true\"");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(actual))) {
            assertThat(workbook.getForceFormulaRecalculation()).isTrue();
            var helper = workbook.getSheet("_LessonLists");
            assertThat(helper.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Exported lesson");
            assertThat(helper.getRow(1).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(workbook.getName("LessonChoices_2").getRefersToFormula())
                    .isEqualTo("_LessonLists!$A$2");
        }
    }

    @Test
    void exportCourseWritesMappedAudioPathIntoWorkbook() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();

        byte[] actual = exporter.exportCourse(revisionWithOneQuestion(),
                Map.of(30L, "audio/lesson-30-exported-lesson.mp3"));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(actual))) {
            assertThat(workbook.getSheet("Content").getRow(1).getCell(6).getStringCellValue())
                    .isEqualTo("audio/lesson-30-exported-lesson.mp3");
            assertThat(cellValueForHeader(workbook.getSheet("Lessons"), "audio_file", 1))
                    .isEqualTo("audio/lesson-30-exported-lesson.mp3");
        }
    }

    private String cellValueForHeader(org.apache.poi.ss.usermodel.Sheet sheet, String header, int dataRow) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            var row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            for (var cell : row) {
                if (header.equals(cell.getStringCellValue())) {
                    var value = sheet.getRow(dataRow).getCell(cell.getColumnIndex());
                    return value == null ? "" : value.toString();
                }
            }
        }
        throw new AssertionError("Missing header: " + header);
    }

    private CourseRevision revisionWithOneQuestion() {
        Course course = new Course();
        CourseRevision revision = new CourseRevision();
        revision.setCourse(course);
        setId(course, 10L);
        revision.setName("Exported course");
        revision.setDescription("Description");
        revision.setType(CourseType.COMPREHENSIVE);
        revision.setLevel("A1");
        revision.setOrderIndex(1);

        TopicRevision topic = new TopicRevision();
        topic.setName("Exported topic");
        topic.setDescription("Topic description");
        topic.setOrderIndex(1);
        setId(topic, 20L);
        LessonRevision lesson = new LessonRevision();
        lesson.setTitle("Exported lesson");
        lesson.setDescription("Lesson description");
        lesson.setContent("Lesson content");
        lesson.setSkillType(SkillType.READING);
        lesson.setOrderIndex(1);
        setId(lesson, 30L);
        QuestionRevision question = new QuestionRevision();
        question.setType(QuestionType.MULTIPLE_CHOICE);
        question.setQuestionText("Exported question");
        question.setExplanation("Explanation");
        question.setCorrectAnswer("Answer");
        question.setDifficulty("easy");
        question.setKnowledgePoint("greetings");
        question.setXpReward(7);
        question.setOrderIndex(1);
        setId(question, 40L);
        QuestionOptionRevision option = new QuestionOptionRevision();
        option.setOptionText("Option A");
        option.setOrderIndex(1);
        option.setCorrect(true);
        setId(option, 50L);
        question.getOptions().add(option);
        QuestionOptionRevision secondOption = new QuestionOptionRevision();
        secondOption.setOptionText("Option B");
        secondOption.setOrderIndex(2);
        setId(secondOption, 51L);
        question.getOptions().add(secondOption);
        lesson.setXpReward(25);
        lesson.setPlacementTest(true);
        lesson.setAudioUrl("https://audio.example/lesson.mp3");
        lesson.setOrderIndex(3);
        lesson.getQuestions().add(question);
        topic.getLessons().add(lesson);
        revision.getTopics().add(topic);
        return revision;
    }

    private void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private List<String> sheetNames(XSSFWorkbook workbook) {
        return workbook.iterator().hasNext()
                ? java.util.stream.IntStream.range(0, workbook.getNumberOfSheets())
                        .mapToObj(workbook::getSheetName).toList()
                : List.of();
    }

    private boolean hasZipEntry(byte[] bytes, String path) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String zipEntryText(byte[] bytes, String path) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(path)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private byte[] zipEntryBytes(byte[] bytes, String path) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(path)) {
                    return zip.readAllBytes();
                }
            }
        }
        return new byte[0];
    }
}
