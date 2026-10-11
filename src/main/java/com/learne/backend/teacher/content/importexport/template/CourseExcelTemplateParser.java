package com.learne.backend.teacher.content.importexport.template;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.model.LessonImportModel;
import com.learne.backend.teacher.content.importexport.model.OptionImportModel;
import com.learne.backend.teacher.content.importexport.model.QuestionImportModel;
import com.learne.backend.teacher.content.importexport.model.TopicImportModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CourseExcelTemplateParser {
    private static final String[] COURSE_FIELDS = {"course_name", "course_description", "course_type", "course_level"};
    private static final Set<String> COURSE_TYPES = Set.of("COMPREHENSIVE", "GRAMMAR", "LISTENING", "SPEAKING",
            "READING", "WRITING", "VOCABULARY", "PRONUNCIATION");
    private static final Set<String> COURSE_LEVELS = Set.of("A1", "A2", "B1", "B2", "C1", "C2");
    private static final Set<String> SKILL_TYPES = Set.of("GRAMMAR", "LISTENING", "SPEAKING", "READING", "WRITING",
            "VOCABULARY", "PRONUNCIATION");
    private static final Set<String> QUESTION_TYPES = Set.of("MULTIPLE_CHOICE", "FILL_BLANK", "WORD_ORDER",
            "TRANSLATION", "TRUE_FALSE", "SHORT_ANSWER", "PRONUNCIATION");
    private static final Set<String> REQUIRED_CONTENT_HEADERS = Set.of("topic_title", "topic_description",
            "lesson_title", "lesson_description", "lesson_content", "skill_type", "audio_file", "question_type",
            "question_text", "explanation", "correct_answer", "option_a", "option_b", "option_c", "option_d",
            "correct_option", "_row_fingerprint");

    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public CourseImportModel parse(byte[] workbookBytes) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
            validateMetadata(workbook);
            CourseFields course = readCourse(workbook);
            List<ContentRow> contentRows = readContentRows(workbook);
            if (contentRows.isEmpty()) {
                throw badRequest("Sheet Content phải có ít nhất một câu hỏi.");
            }
            return buildModel(course, contentRows);
        } catch (IOException | UnsupportedFileFormatException | POIXMLException | RecordFormatException exception) {
            throw badRequest("File Excel không hợp lệ.");
        }
    }


    private void validateMetadata(Workbook workbook) {
        Sheet metadata = requiredSheet(workbook, "_metadata");
        requireCell(metadata, 0, 0, "_template_version");
        requireCell(metadata, 0, 1, "v1");
        requireCell(metadata, 1, 0, "_template_type");
        requireCell(metadata, 1, 1, "flat_course_content");
    }

    private CourseFields readCourse(Workbook workbook) {
        Sheet sheet = requiredSheet(workbook, "Course");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            String key = text(row == null ? null : row.getCell(0));
            if (!hasText(key)) {
                continue;
            }
            if (values.containsKey(key)) {
                throw badRequest("Sheet Course chứa trường trùng: " + key);
            }
            values.put(key, text(row.getCell(1)));
        }
        for (String field : COURSE_FIELDS) {
            if (!values.containsKey(field)) {
                throw badRequest("Sheet Course thiếu trường bắt buộc: " + field);
            }
        }
        requireText(values.get("course_name"), "Course course_name");
        requireEnum(values.get("course_type"), COURSE_TYPES, "Course course_type");
        requireEnum(values.get("course_level"), COURSE_LEVELS, "Course course_level");
        return new CourseFields(values.get("course_name"), values.get("course_description"),
                values.get("course_type"), values.get("course_level"));
    }

    private List<ContentRow> readContentRows(Workbook workbook) {
        Sheet sheet = requiredSheet(workbook, "Content");
        if (sheet.getLastRowNum() > 5000) {
            throw badRequest("Sheet Content vượt quá số dòng cho phép.");
        }
        Map<String, Integer> headers = headers(sheet, REQUIRED_CONTENT_HEADERS);
        List<ContentRow> rows = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (blank(row) || "SAMPLE_ROW".equalsIgnoreCase(value(row, headers, "_row_fingerprint"))) {
                continue;
            }
            ContentRow content = new ContentRow(i + 1, value(row, headers, "topic_title"),
                    value(row, headers, "topic_description"), value(row, headers, "lesson_title"),
                    value(row, headers, "lesson_description"), value(row, headers, "lesson_content"),
                    value(row, headers, "skill_type"), value(row, headers, "audio_file"),
                    value(row, headers, "question_type"), value(row, headers, "question_text"),
                    value(row, headers, "explanation"), value(row, headers, "correct_answer"),
                    value(row, headers, "correct_option"), options(row, headers));
            requireText(content.topicTitle(), "Content row " + content.rowNumber() + ": topic_title");
            requireText(content.lessonTitle(), "Content row " + content.rowNumber() + ": lesson_title");
            requireText(content.questionText(), "Content row " + content.rowNumber() + ": question_text");
            requireEnum(content.skillType(), SKILL_TYPES, rowLabel(content, "skill_type"));
            validateQuestion(content);
            rows.add(content);
        }
        return rows;
    }

    private void validateQuestion(ContentRow row) {
        requireEnum(row.questionType(), QUESTION_TYPES, rowLabel(row, "question_type"));
        if ("MULTIPLE_CHOICE".equals(row.questionType()) || "TRUE_FALSE".equals(row.questionType())) {
            requireText(row.correctOption(), rowLabel(row, "correct_option"));
            if (!Set.of("A", "B", "C", "D").contains(row.correctOption()) || row.options().size() < 2
                    || row.options().stream().noneMatch(option -> option.correct()
                            && option.text() != null && !option.text().isBlank())) {
                throw rowError(row.rowNumber(), "Câu hỏi trắc nghiệm phải có ít nhất hai lựa chọn và đúng một đáp án.");
            }
            return;
        }
        if (hasText(row.correctOption())) {
            throw rowError(row.rowNumber(), "correct_option chỉ dùng cho câu hỏi trắc nghiệm.");
        }
        requireText(row.correctAnswer(), rowLabel(row, "correct_answer"));
    }

    private CourseImportModel buildModel(CourseFields course, List<ContentRow> rows) {
        Map<String, TopicBuilder> topics = new LinkedHashMap<>();
        for (ContentRow row : rows) {
            TopicBuilder topic = topics.computeIfAbsent(row.topicTitle(),
                    ignored -> new TopicBuilder(row.topicTitle(), row.topicDescription()));
            LessonBuilder lesson = topic.lessons.computeIfAbsent(row.lessonTitle(),
                    ignored -> new LessonBuilder(row.lessonTitle(), row.lessonDescription(), row.lessonContent(),
                            row.skillType(), row.audioFile()));
            if (!same(row.lessonDescription(), lesson.description) || !same(row.lessonContent(), lesson.content)) {
                throw rowError(row.rowNumber(), "lesson_description hoặc lesson_content của cùng một lesson phải giống nhau.");
            }
            if (!lesson.skillType.equals(row.skillType())) {
                throw rowError(row.rowNumber(), "skill_type của cùng một lesson phải giống nhau.");
            }
            if (hasText(row.audioFile()) && hasText(lesson.audioFile) && !lesson.audioFile.equals(row.audioFile())) {
                throw rowError(row.rowNumber(), "audio_file của cùng một lesson phải giống nhau.");
            }
            if (hasText(row.audioFile())) {
                lesson.audioFile = row.audioFile();
            }
            lesson.questions.add(new QuestionImportModel(null, lesson.questions.size() + 1, row.questionType(),
                    row.questionText(), row.explanation(), row.correctAnswer(), null, null, 0, row.options()));
        }
        List<TopicImportModel> topicModels = new ArrayList<>();
        int topicOrder = 1;
        for (TopicBuilder topic : topics.values()) {
            List<LessonImportModel> lessonModels = new ArrayList<>();
            int lessonOrder = 1;
            for (LessonBuilder lesson : topic.lessons.values()) {
                lessonModels.add(new LessonImportModel(null, lessonOrder++, lesson.title, lesson.description,
                        lesson.content, lesson.skillType, 0, false, lesson.audioFile, lesson.questions));
            }
            topicModels.add(new TopicImportModel(null, topicOrder++, topic.title, topic.description, lessonModels));
        }
        return new CourseImportModel(null, course.name(), course.description(), course.type(), course.level(), 0,
                topicModels);
    }

    private Map<String, Integer> headers(Sheet sheet, Set<String> required) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw badRequest("Sheet " + sheet.getSheetName() + " thiếu dòng tiêu đề.");
        }
        Map<String, Integer> result = new HashMap<>();
        for (Cell cell : header) {
            String name = text(cell);
            if (hasText(name) && result.put(name, cell.getColumnIndex()) != null) {
                throw badRequest("Sheet " + sheet.getSheetName() + " chứa cột trùng: " + name);
            }
        }
        for (String name : required) {
            if (!result.containsKey(name)) {
                throw badRequest("Sheet " + sheet.getSheetName() + " thiếu cột bắt buộc: " + name);
            }
        }
        return result;
    }

    private Sheet requiredSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            throw badRequest("Không tìm thấy sheet " + name + ".");
        }
        return sheet;
    }

    private String requireCell(Sheet sheet, int row, int column, String expected) {
        String actual = text(sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(column));
        if (!expected.equals(actual)) {
            throw badRequest("Sheet " + sheet.getSheetName() + " không phải template được hỗ trợ.");
        }
        return actual;
    }

    private String value(Row row, Map<String, Integer> headers, String header) {
        return text(row == null ? null : row.getCell(headers.get(header)));
    }

    private List<OptionImportModel> options(Row row, Map<String, Integer> headers) {
        List<OptionImportModel> options = new ArrayList<>();
        String correct = value(row, headers, "correct_option");
        addOption(options, row, headers, "a", correct, 1);
        addOption(options, row, headers, "b", correct, 2);
        addOption(options, row, headers, "c", correct, 3);
        addOption(options, row, headers, "d", correct, 4);
        return options;
    }

    private void addOption(List<OptionImportModel> options, Row row, Map<String, Integer> headers, String letter,
            String correct, int order) {
        String option = value(row, headers, "option_" + letter);
        if (hasText(option)) {
            options.add(new OptionImportModel(null, option, letter.equals(correct == null ? null : correct.toLowerCase()),
                    order));
        }
    }

    private boolean blank(Row row) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            if (hasText(text(cell))) {
                return false;
            }
        }
        return true;
    }

    private String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> blankToNull(cell.getStringCellValue());
                case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString()).trim();
                case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
                default -> null;
            };
        }
        String value = formatter.formatCellValue(cell).trim();
        return value.isEmpty() ? null : value;
    }

    private String blankToNull(String value) {
        String trimmed = value == null ? null : value.trim();
        return hasText(trimmed) ? trimmed : null;
    }

    private void requireText(String value, String field) {
        if (!hasText(value)) {
            throw badRequest(field + " không được để trống.");
        }
    }

    private void requireEnum(String value, Set<String> allowed, String field) {
        requireText(value, field);
        if (!allowed.contains(value)) {
            throw badRequest(field + " không hợp lệ.");
        }
    }

    private boolean same(String left, String right) {
        return nullToBlank(left).equals(nullToBlank(right));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String rowLabel(ContentRow row, String field) {
        return "Content row " + row.rowNumber() + ": " + field;
    }

    private ApiException rowError(int row, String message) {
        return badRequest("Content row " + row + ": " + message);
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private record CourseFields(String name, String description, String type, String level) {}
    private record ContentRow(int rowNumber, String topicTitle, String topicDescription, String lessonTitle,
            String lessonDescription, String lessonContent, String skillType, String audioFile, String questionType,
            String questionText, String explanation, String correctAnswer, String correctOption,
            List<OptionImportModel> options) {}

    private static final class TopicBuilder {
        private final String title;
        private final String description;
        private final Map<String, LessonBuilder> lessons = new LinkedHashMap<>();

        private TopicBuilder(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    private static final class LessonBuilder {
        private final String title;
        private final String description;
        private final String content;
        private final String skillType;
        private String audioFile;
        private final List<QuestionImportModel> questions = new ArrayList<>();

        private LessonBuilder(String title, String description, String content, String skillType, String audioFile) {
            this.title = title;
            this.description = description;
            this.content = content;
            this.skillType = skillType;
            this.audioFile = audioFile;
        }
    }

}
