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
    private static final String[] EXPORT_ID_HEADERS = {"_course_id", "_topic_id", "_lesson_id", "_question_id",
            "_option_a_id", "_option_b_id", "_option_c_id", "_option_d_id", "_row_fingerprint"};
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
            validateIds(contentRows);
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
        validateExportIdHeaders(headers);
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
                    value(row, headers, "correct_option"), id(row, headers, "_course_id"),
                    id(row, headers, "_topic_id"), id(row, headers, "_lesson_id"),
                    id(row, headers, "_question_id"), optionalInt(row, headers, "lesson_order", i + 1),
                    optionalInt(row, headers, "lesson_xp_reward", i + 1),
                    optionalBoolean(row, headers, "lesson_placement_test", i + 1),
                    optionalInt(row, headers, "question_order", i + 1),
                    optionalInt(row, headers, "question_xp_reward", i + 1),
                    optionalValue(row, headers, "difficulty"), optionalValue(row, headers, "knowledge_point"),
                    options(row, headers));
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
        boolean choiceQuestion = "MULTIPLE_CHOICE".equals(row.questionType()) || "TRUE_FALSE".equals(row.questionType());
        if (!choiceQuestion && !row.options().isEmpty()) {
            throw rowError(row.rowNumber(), "option_a..option_d chỉ dùng cho câu hỏi trắc nghiệm.");
        }
        if (choiceQuestion) {
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
                    ignored -> new TopicBuilder(row.topicId(), row.topicTitle(), row.topicDescription()));
            if (!same(row.topicDescription(), topic.description)) {
                throw rowError(row.rowNumber(), "topic_description của cùng một topic phải giống nhau.");
            }
            LessonBuilder lesson = topic.lessons.computeIfAbsent(row.lessonTitle(),
                    ignored -> new LessonBuilder(row.lessonId(), row.lessonOrder() == null ? topic.lessons.size() + 1 : row.lessonOrder(),
                            row.lessonTitle(), row.lessonDescription(), row.lessonContent(), row.skillType(),
                            row.lessonXpReward() == null ? 0 : row.lessonXpReward(),
                            row.lessonPlacementTest() != null && row.lessonPlacementTest(), row.audioFile()));
            if (!same(row.lessonDescription(), lesson.description) || !same(row.lessonContent(), lesson.content)) {
                throw rowError(row.rowNumber(), "lesson_description hoặc lesson_content của cùng một lesson phải giống nhau.");
            }
            if (!lesson.skillType.equals(row.skillType())) {
                throw rowError(row.rowNumber(), "skill_type của cùng một lesson phải giống nhau.");
            }
            if (row.lessonXpReward() != null && lesson.xpReward != row.lessonXpReward()) {
                throw rowError(row.rowNumber(), "lesson_xp_reward của cùng một lesson phải giống nhau.");
            }
            if (row.lessonPlacementTest() != null && lesson.placementTest != row.lessonPlacementTest()) {
                throw rowError(row.rowNumber(), "lesson_placement_test của cùng một lesson phải giống nhau.");
            }
            if (hasText(row.audioFile()) && hasText(lesson.audioFile) && !lesson.audioFile.equals(row.audioFile())) {
                throw rowError(row.rowNumber(), "audio_file của cùng một lesson phải giống nhau.");
            }
            if (hasText(row.audioFile())) {
                lesson.audioFile = row.audioFile();
            }
            lesson.questions.add(new QuestionImportModel(row.questionId(),
                    row.questionOrder() == null ? lesson.questions.size() + 1 : row.questionOrder(), row.questionType(),
                    row.questionText(), row.explanation(), row.correctAnswer(), row.knowledgePoint(), row.difficulty(),
                    row.questionXpReward() == null ? 0 : row.questionXpReward(), row.options()));
        }
        List<TopicImportModel> topicModels = new ArrayList<>();
        int topicOrder = 1;
        for (TopicBuilder topic : topics.values()) {
            List<LessonImportModel> lessonModels = new ArrayList<>();
            int lessonOrder = 1;
            for (LessonBuilder lesson : topic.lessons.values()) {
                lessonModels.add(new LessonImportModel(lesson.id, lesson.orderIndex, lesson.title, lesson.description,
                        lesson.content, lesson.skillType, lesson.xpReward, lesson.placementTest, lesson.audioFile, lesson.questions));
            }
            topicModels.add(new TopicImportModel(topic.id, topicOrder++, topic.title, topic.description, lessonModels));
        }
        Long courseId = rows.stream()
                .map(ContentRow::courseId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new CourseImportModel(courseId, course.name(), course.description(), course.type(), course.level(), 0,
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

    private void validateExportIdHeaders(Map<String, Integer> headers) {
        if (!headers.containsKey("_course_id")) {
            return;
        }
        for (String header : EXPORT_ID_HEADERS) {
            if (header.startsWith("_option_") && headers.keySet().stream().noneMatch(candidate -> candidate.startsWith("_option_")
                    && candidate.endsWith("_id"))) {
                continue;
            }
            if (!headers.containsKey(header)) {
                throw badRequest("Sheet Content thiếu cột bắt buộc: " + header);
            }
        }
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

    private String optionalValue(Row row, Map<String, Integer> headers, String header) {
        Integer column = headers.get(header);
        return column == null ? null : text(row == null ? null : row.getCell(column));
    }

    private Integer optionalInt(Row row, Map<String, Integer> headers, String header, int rowNumber) {
        String value = optionalValue(row, headers, header);
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw rowError(rowNumber, header + " phải là số nguyên.");
        }
    }

    private Boolean optionalBoolean(Row row, Map<String, Integer> headers, String header, int rowNumber) {
        String value = optionalValue(row, headers, header);
        if (!hasText(value)) {
            return null;
        }
        if ("TRUE".equalsIgnoreCase(value)) {
            return true;
        }
        if ("FALSE".equalsIgnoreCase(value)) {
            return false;
        }
        throw rowError(rowNumber, header + " phải là TRUE hoặc FALSE.");
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
        Long optionId = id(row, headers, "_option_" + letter + "_id");
        if (hasText(option)) {
            options.add(new OptionImportModel(optionId, option, letter.equals(correct == null ? null : correct.toLowerCase()),
                    order));
        } else if (optionId != null) {
            throw rowError(row.getRowNum() + 1, "_option_" + letter + "_id không có đáp án tương ứng.");
        }
    }

    private Long id(Row row, Map<String, Integer> headers, String header) {
        Integer column = headers.get(header);
        return column == null ? null : parseId(value(row, headers, header), header, row == null ? 0 : row.getRowNum() + 1);
    }

    private Long parseId(String value, String field, int rowNumber) {
        if (!hasText(value)) {
            return null;
        }
        if (!value.matches("[0-9]+")) {
            throw rowError(rowNumber, field + " phải là số nguyên dương.");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw rowError(rowNumber, field + " phải là số nguyên dương.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw rowError(rowNumber, field + " phải là số nguyên dương.");
        }
    }

    private void validateIds(List<ContentRow> rows) {
        Long courseId = rows.stream()
                .map(ContentRow::courseId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        Map<String, Long> topicIdsByTitle = new HashMap<>();
        Map<Long, String> topicTitlesById = new HashMap<>();
        Map<String, Long> lessonIdsByTitle = new HashMap<>();
        Map<Long, String> lessonKeysById = new HashMap<>();
        Set<Long> questionIds = new java.util.HashSet<>();
        Set<Long> optionIds = new java.util.HashSet<>();
        for (ContentRow row : rows) {
            if (courseId != null && row.courseId() == null) {
                throw rowError(row.rowNumber(), "_course_id phải được giữ nguyên trên mọi dòng khi nhập lại khóa học.");
            }
            if (row.courseId() != null) {
                assertConsistent(courseId, row.courseId(), row, "_course_id");
            }
            if (row.topicId() != null && row.courseId() == null) {
                throw rowError(row.rowNumber(), "_topic_id yêu cầu _course_id.");
            }
            if (row.lessonId() != null && row.topicId() == null) {
                throw rowError(row.rowNumber(), "_lesson_id yêu cầu _topic_id.");
            }
            if (row.questionId() != null && row.lessonId() == null) {
                throw rowError(row.rowNumber(), "_question_id yêu cầu _lesson_id.");
            }
            String topicKey = row.topicTitle();
            if (topicIdsByTitle.containsKey(topicKey)) {
                assertConsistent(topicIdsByTitle.get(topicKey), row.topicId(), row, "_topic_id");
            } else {
                topicIdsByTitle.put(topicKey, row.topicId());
            }
            if (row.topicId() != null) {
                if (topicTitlesById.containsKey(row.topicId())) {
                    assertConsistent(topicTitlesById.get(row.topicId()), topicKey, row, "_topic_id");
                }
                topicTitlesById.put(row.topicId(), topicKey);
            }
            String lessonKey = topicKey + "\u0000" + row.lessonTitle();
            if (lessonIdsByTitle.containsKey(lessonKey)) {
                assertConsistent(lessonIdsByTitle.get(lessonKey), row.lessonId(), row, "_lesson_id");
            } else {
                lessonIdsByTitle.put(lessonKey, row.lessonId());
            }
            if (row.lessonId() != null) {
                if (lessonKeysById.containsKey(row.lessonId())) {
                    assertConsistent(lessonKeysById.get(row.lessonId()), lessonKey, row, "_lesson_id");
                }
                lessonKeysById.put(row.lessonId(), lessonKey);
            }
            if (row.questionId() != null && !questionIds.add(row.questionId())) {
                throw rowError(row.rowNumber(), "_question_id bị trùng.");
            }
            for (OptionImportModel option : row.options()) {
                if (option.optionId() != null && row.questionId() == null) {
                    throw rowError(row.rowNumber(), "ID đáp án yêu cầu _question_id.");
                }
                if (option.optionId() != null && !optionIds.add(option.optionId())) {
                    throw rowError(row.rowNumber(), "ID đáp án bị trùng.");
                }
            }
        }
    }

    private void assertConsistent(Object expected, Object actual, ContentRow row, String field) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw rowError(row.rowNumber(), field + " không nhất quán.");
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
            Long courseId, Long topicId, Long lessonId, Long questionId, Integer lessonOrder, Integer lessonXpReward,
            Boolean lessonPlacementTest, Integer questionOrder, Integer questionXpReward, String difficulty,
            String knowledgePoint, List<OptionImportModel> options) {}

    private static final class TopicBuilder {
        private final Long id;
        private final String title;
        private final String description;
        private final Map<String, LessonBuilder> lessons = new LinkedHashMap<>();

        private TopicBuilder(Long id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }
    }

    private static final class LessonBuilder {
        private final Long id;
        private final int orderIndex;
        private final String title;
        private final String description;
        private final String content;
        private final String skillType;
        private final int xpReward;
        private final boolean placementTest;
        private String audioFile;
        private final List<QuestionImportModel> questions = new ArrayList<>();

        private LessonBuilder(Long id, int orderIndex, String title, String description, String content, String skillType,
                int xpReward, boolean placementTest, String audioFile) {
            this.id = id;
            this.orderIndex = orderIndex;
            this.title = title;
            this.description = description;
            this.content = content;
            this.skillType = skillType;
            this.xpReward = xpReward;
            this.placementTest = placementTest;
            this.audioFile = audioFile;
        }
    }

}
