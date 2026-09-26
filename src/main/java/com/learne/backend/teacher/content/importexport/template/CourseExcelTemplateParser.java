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
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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
    private static final String CONTENT_SHEET = "Content";
    private static final String SAMPLE_ROW_MARKER = "SAMPLE_ROW";
    private static final String[] REQUIRED_HEADERS = {
            "course_name", "course_description", "course_type", "course_level", "course_order_index",
            "topic_order", "topic_title", "topic_description",
            "lesson_order", "lesson_title", "lesson_description", "lesson_content", "skill_type", "xp_reward",
            "placement_test", "audio_file",
            "question_order", "question_type", "question_text", "explanation", "correct_answer",
            "option_a", "option_b", "option_c", "option_d", "correct_option", "difficulty", "knowledge_point",
            "_course_id", "_topic_id", "_lesson_id", "_question_id", "_option_a_id", "_option_b_id",
            "_option_c_id", "_option_d_id", "_row_fingerprint"
    };

    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public CourseImportModel parse(byte[] workbookBytes) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet(CONTENT_SHEET);
            if (sheet == null) {
                throw badRequest("Không tìm thấy sheet Content.");
            }

            Map<String, Integer> headers = readHeaders(sheet.getRow(0));
            validateRequiredHeaders(headers);

            CourseRow firstCourseRow = null;
            Map<String, TopicBuilder> topics = new LinkedHashMap<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) {
                    continue;
                }
                if (isSampleRow(row, headers)) {
                    continue;
                }
                CourseRow courseRow = readCourseRow(row, headers);
                if (firstCourseRow == null) {
                    firstCourseRow = courseRow;
                } else if (courseRow.courseId != null && !courseRow.courseId.equals(firstCourseRow.courseId)) {
                    throw badRequest("ID khóa học trong các dòng không khớp.");
                }

                String topicKey = topicKey(courseRow);
                TopicBuilder topic = topics.computeIfAbsent(topicKey, ignored -> new TopicBuilder(courseRow));
                String lessonKey = lessonKey(courseRow);
                LessonBuilder lesson = topic.lessons.computeIfAbsent(lessonKey, ignored -> new LessonBuilder(courseRow));
                if (hasText(courseRow.questionText) || hasText(courseRow.questionType)) {
                    lesson.questions.add(toQuestion(courseRow));
                }
            }

            if (firstCourseRow == null) {
                return new CourseImportModel(null, null, null, null, null, null, List.of());
            }
            return new CourseImportModel(firstCourseRow.courseId, firstCourseRow.courseName,
                    firstCourseRow.courseDescription, firstCourseRow.courseType, firstCourseRow.courseLevel,
                    firstCourseRow.courseOrderIndex, topics.values().stream().map(TopicBuilder::build).toList());
        } catch (IOException | UnsupportedFileFormatException | POIXMLException | RecordFormatException exception) {
            throw badRequest("File Excel không hợp lệ.");
        }
    }

    private Map<String, Integer> readHeaders(Row headerRow) {
        if (headerRow == null) {
            throw badRequest("Thiếu dòng tiêu đề trong sheet Content.");
        }
        Map<String, Integer> headers = new HashMap<>();
        for (Cell cell : headerRow) {
            String header = text(cell);
            if (hasText(header)) {
                headers.put(header, cell.getColumnIndex());
            }
        }
        return headers;
    }

    private void validateRequiredHeaders(Map<String, Integer> headers) {
        for (String header : REQUIRED_HEADERS) {
            if (!headers.containsKey(header)) {
                throw badRequest("Thiếu cột bắt buộc: " + header + ".");
            }
        }
    }

    private CourseRow readCourseRow(Row row, Map<String, Integer> headers) {
        return new CourseRow(
                longValue(row, headers, "_course_id"),
                stringValue(row, headers, "course_name"),
                stringValue(row, headers, "course_description"),
                stringValue(row, headers, "course_type"),
                stringValue(row, headers, "course_level"),
                integerValue(row, headers, "course_order_index"),
                longValue(row, headers, "_topic_id"),
                intValue(row, headers, "topic_order"),
                stringValue(row, headers, "topic_title"),
                stringValue(row, headers, "topic_description"),
                longValue(row, headers, "_lesson_id"),
                intValue(row, headers, "lesson_order"),
                stringValue(row, headers, "lesson_title"),
                stringValue(row, headers, "lesson_description"),
                stringValue(row, headers, "lesson_content"),
                stringValue(row, headers, "skill_type"),
                integerValue(row, headers, "xp_reward"),
                booleanValue(row, headers, "placement_test"),
                stringValue(row, headers, "audio_file"),
                longValue(row, headers, "_question_id"),
                intValue(row, headers, "question_order"),
                stringValue(row, headers, "question_type"),
                stringValue(row, headers, "question_text"),
                stringValue(row, headers, "explanation"),
                stringValue(row, headers, "correct_answer"),
                stringValue(row, headers, "knowledge_point"),
                stringValue(row, headers, "difficulty"),
                options(row, headers));
    }

    private List<OptionImportModel> options(Row row, Map<String, Integer> headers) {
        String correctOption = stringValue(row, headers, "correct_option");
        List<OptionImportModel> options = new ArrayList<>();
        addOption(options, row, headers, "a", correctOption, 1);
        addOption(options, row, headers, "b", correctOption, 2);
        addOption(options, row, headers, "c", correctOption, 3);
        addOption(options, row, headers, "d", correctOption, 4);
        return options;
    }

    private void addOption(List<OptionImportModel> options, Row row, Map<String, Integer> headers, String letter,
            String correctOption, int orderIndex) {
        String text = stringValue(row, headers, "option_" + letter);
        if (!hasText(text)) {
            return;
        }
        Long id = longValue(row, headers, "_option_" + letter + "_id");
        boolean correct = letter.equalsIgnoreCase(correctOption);
        options.add(new OptionImportModel(id, text, correct, orderIndex));
    }

    private QuestionImportModel toQuestion(CourseRow row) {
        return new QuestionImportModel(row.questionId, row.questionOrder, row.questionType, row.questionText,
                row.explanation, row.correctAnswer, row.knowledgePoint, row.difficulty, row.xpReward, row.options);
    }

    private String topicKey(CourseRow row) {
        if (row.topicId != null) {
            return "id:" + row.topicId;
        }
        return row.topicOrder + ":" + nullToBlank(row.topicTitle);
    }

    private String lessonKey(CourseRow row) {
        if (row.lessonId != null) {
            return "id:" + row.lessonId;
        }
        return row.lessonOrder + ":" + nullToBlank(row.lessonTitle);
    }

    private boolean isBlankRow(Row row) {
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

    private boolean isSampleRow(Row row, Map<String, Integer> headers) {
        return SAMPLE_ROW_MARKER.equalsIgnoreCase(stringValue(row, headers, "_row_fingerprint"));
    }

    private String stringValue(Row row, Map<String, Integer> headers, String header) {
        return text(row.getCell(headers.get(header)));
    }

    private Integer integerValue(Row row, Map<String, Integer> headers, String header) {
        String value = stringValue(row, headers, header);
        if (!hasText(value)) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int intValue(Row row, Map<String, Integer> headers, String header) {
        Integer value = integerValue(row, headers, header);
        return value == null ? 0 : value;
    }

    private Long longValue(Row row, Map<String, Integer> headers, String header) {
        String value = stringValue(row, headers, header);
        if (!hasText(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw invalidHiddenId(header);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalidHiddenId(header);
        }
    }

    private ApiException invalidHiddenId(String header) {
        return badRequest("ID ẩn không hợp lệ ở cột " + header + ".");
    }

    private boolean booleanValue(Row row, Map<String, Integer> headers, String header) {
        Cell cell = row.getCell(headers.get(header));
        if (cell != null && cell.getCellType() == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        return Boolean.parseBoolean(text(cell));
    }

    private String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        String value = formatter.formatCellValue(cell).trim();
        return value.isEmpty() ? null : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private record CourseRow(Long courseId, String courseName, String courseDescription, String courseType,
            String courseLevel, Integer courseOrderIndex, Long topicId, int topicOrder, String topicTitle,
            String topicDescription, Long lessonId, int lessonOrder, String lessonTitle, String lessonDescription,
            String lessonContent, String skillType, Integer xpReward, boolean placementTest, String audioFile,
            Long questionId, int questionOrder, String questionType, String questionText, String explanation,
            String correctAnswer, String knowledgePoint, String difficulty, List<OptionImportModel> options) {
    }

    private static final class TopicBuilder {
        private final CourseRow firstRow;
        private final Map<String, LessonBuilder> lessons = new LinkedHashMap<>();

        private TopicBuilder(CourseRow firstRow) {
            this.firstRow = firstRow;
        }

        private TopicImportModel build() {
            return new TopicImportModel(firstRow.topicId, firstRow.topicOrder, firstRow.topicTitle,
                    firstRow.topicDescription, lessons.values().stream().map(LessonBuilder::build).toList());
        }
    }

    private static final class LessonBuilder {
        private final CourseRow firstRow;
        private final List<QuestionImportModel> questions = new ArrayList<>();

        private LessonBuilder(CourseRow firstRow) {
            this.firstRow = firstRow;
        }

        private LessonImportModel build() {
            return new LessonImportModel(firstRow.lessonId, firstRow.lessonOrder, firstRow.lessonTitle,
                    firstRow.lessonDescription, firstRow.lessonContent, firstRow.skillType, firstRow.xpReward,
                    firstRow.placementTest, firstRow.audioFile, questions);
        }
    }
}
