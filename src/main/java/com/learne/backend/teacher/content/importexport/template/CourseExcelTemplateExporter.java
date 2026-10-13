package com.learne.backend.teacher.content.importexport.template;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCalcPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDefinedName;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCalcMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CourseExcelTemplateExporter {
    private static final String[] HEADERS = {
            "topic_title", "topic_description", "lesson_title", "lesson_description", "lesson_content",
            "skill_type", "audio_file", "question_type", "question_text", "explanation", "correct_answer",
            "option_a", "option_b", "option_c", "option_d", "correct_option", "unused_16", "unused_17",
            "unused_18", "unused_19", "unused_20", "unused_21", "unused_22", "unused_23", "unused_24",
            "unused_25", "unused_26", "unused_27",
            "_course_id", "_topic_id", "_lesson_id", "_question_id", "_option_a_id", "_option_b_id",
            "_option_c_id", "_option_d_id", "_row_fingerprint"
    };

    public byte[] blankTemplate() {
        try {
            Path path = Path.of("course-template.xlsm");
            if (Files.exists(path)) {
                return removeCalculationChain(Files.readAllBytes(path));
            }
            try (InputStream input = getClass().getResourceAsStream("/course-template.xlsm")) {
                if (input != null) {
                    return removeCalculationChain(input.readAllBytes());
                }
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể tải file template.");
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không tìm thấy file template.");
    }

    private byte[] removeCalculationChain(byte[] workbookBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbookBytes));
                ZipOutputStream cleaned = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/calcChain.xml".equals(name)) {
                    zip.closeEntry();
                    continue;
                }

                byte[] bytes = zip.readAllBytes();
                if ("[Content_Types].xml".equals(name) || "xl/_rels/workbook.xml.rels".equals(name)) {
                    bytes = removeCalculationChainReferences(bytes);
                }

                ZipEntry copy = new ZipEntry(name);
                cleaned.putNextEntry(copy);
                cleaned.write(bytes);
                cleaned.closeEntry();
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private byte[] removeCalculationChainReferences(byte[] bytes) {
        String xml = new String(bytes, StandardCharsets.UTF_8)
                .replaceAll("(?s)<Override\\s+[^>]*PartName=\"/xl/calcChain.xml\"[^>]*/>", "")
                .replaceAll("(?s)<Relationship\\s+[^>]*(calcChain\\.xml|/calcChain)[^>]*/>", "");
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /** Exports a revision by filling the canonical macro-enabled workbook template. */
    public byte[] exportCourse(CourseRevision revision) {
        return exportCourse(revision, Map.of());
    }

    public byte[] exportCourse(CourseRevision revision, Map<Long, String> lessonAudioPaths) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(blankTemplate()));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet content = workbook.getSheet("Content");
            CellStyle[] dataStyles = dataStyles(content.getRow(1));
            String fingerprintFormula = formula(content.getRow(1), 36);
            Sheet topicsSheet = workbook.getSheet("Topics");
            Sheet lessonsSheet = workbook.getSheet("Lessons");
            ensureLessonAudioHeader(lessonsSheet);
            Sheet lessonLists = workbook.getSheet("_LessonLists");
            CellStyle[] topicStyles = dataStyles(topicsSheet.getRow(2));
            CellStyle[] lessonStyles = dataStyles(lessonsSheet.getRow(3));
            CellStyle[] lessonListStyles = dataStyles(lessonLists.getRow(1));
            clearContentRows(content);
            clearDataRows(topicsSheet);
            clearDataRows(lessonsSheet);
            clearDataRows(lessonLists);
            writeCourseSheet(workbook.getSheet("Course"), revision);

            int rowIndex = 1;
            int topicRowIndex = 1;
            int lessonRowIndex = 1;
            List<TopicRevision> topics = revision.getTopics().stream()
                    .sorted(Comparator.comparingInt(TopicRevision::getOrderIndex).thenComparing(TopicRevision::getId))
                    .toList();
            for (TopicRevision topic : topics) {
                Row topicRow = topicsSheet.createRow(topicRowIndex++);
                writeTopicRow(topicRow, topic);
                applyStyles(topicRow, topicStyles);
                List<LessonRevision> lessons = topic.getLessons().stream()
                        .sorted(Comparator.comparingInt(LessonRevision::getOrderIndex).thenComparing(LessonRevision::getId))
                        .toList();
                for (LessonRevision lesson : lessons) {
                    Row lessonRow = lessonsSheet.createRow(lessonRowIndex++);
                    writeLessonRow(lessonRow, topic, lesson, lessonAudioPaths);
                    applyStyles(lessonRow, lessonStyles);
                    List<QuestionRevision> questions = lesson.getQuestions().stream()
                            .sorted(Comparator.comparingInt(QuestionRevision::getOrderIndex).thenComparing(QuestionRevision::getId))
                            .toList();
                    for (QuestionRevision question : questions) {
                        Row row = content.createRow(rowIndex++);
                        writeRow(row, revision, topic, lesson, question, lessonAudioPaths);
                        writeFingerprint(row, fingerprintFormula);
                        applyStyles(row, dataStyles);
                    }
                }
            }
            updateContentTables(content, rowIndex - 1);
            updateTables(topicsSheet, topicRowIndex - 1, 3);
            updateTables(lessonsSheet, lessonRowIndex - 1, 7);
            refreshLessonLists(workbook, lessonLists, lessonListStyles, topics);
            configureRecalculation(workbook);

            workbook.write(output);
            return removeCalculationChain(output.toByteArray());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể xuất khóa học.");
        }
    }

    private void clearContentRows(Sheet content) {
        for (int rowIndex = content.getLastRowNum(); rowIndex > 0; rowIndex--) {
            Row row = content.getRow(rowIndex);
            if (row != null) {
                content.removeRow(row);
            }
        }
    }

    private void ensureLessonAudioHeader(Sheet lessons) {
        Row header = null;
        for (int rowIndex = 0; rowIndex <= lessons.getLastRowNum(); rowIndex++) {
            Row candidate = lessons.getRow(rowIndex);
            if (candidate == null) {
                continue;
            }
            for (Cell cell : candidate) {
                if ("lesson_title".equals(cell.toString()) || "lesson_content".equals(cell.toString())) {
                    header = candidate;
                    break;
                }
            }
            if (header != null) {
                break;
            }
        }
        if (header == null) {
            header = lessons.getRow(2);
            if (header == null) {
                header = lessons.createRow(2);
            }
        }
        set(header, 6, "audio_file");
    }

    private void clearDataRows(Sheet sheet) {
        for (int rowIndex = sheet.getLastRowNum(); rowIndex > 0; rowIndex--) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
    }

    private void refreshLessonLists(XSSFWorkbook workbook, Sheet sheet, CellStyle[] styles,
            List<TopicRevision> topics) {
        for (org.apache.poi.ss.usermodel.Name name : List.copyOf(workbook.getAllNames())) {
            if (name.getNameName().startsWith("LessonChoices_")) {
                workbook.removeName(name);
            }
        }
        for (int topicIndex = 0; topicIndex < topics.size(); topicIndex++) {
            TopicRevision topic = topics.get(topicIndex);
            int rowIndex = topicIndex + 1;
            Row row = sheet.createRow(rowIndex);
            List<LessonRevision> lessons = topic.getLessons().stream()
                    .sorted(Comparator.comparingInt(LessonRevision::getOrderIndex).thenComparing(LessonRevision::getId))
                    .toList();
            for (int lessonIndex = 0; lessonIndex < lessons.size(); lessonIndex++) {
                set(row, lessonIndex, lessons.get(lessonIndex).getTitle());
            }
            applyStyles(row, styles);
            if (!lessons.isEmpty()) {
                int excelRow = rowIndex + 1;
                String start = CellReference.convertNumToColString(0);
                String end = CellReference.convertNumToColString(lessons.size() - 1);
                String formula = "_LessonLists!$" + start + "$" + excelRow
                        + (lessons.size() == 1 ? "" : ":$" + end + "$" + excelRow);
                setDefinedName(workbook, "LessonChoices_" + excelRow, formula);
            }
        }
    }

    private void setDefinedName(XSSFWorkbook workbook, String name, String formula) {
        org.apache.poi.xssf.usermodel.XSSFName definedName = workbook.getName(name);
        if (definedName == null) {
            definedName = workbook.createName();
            definedName.setNameName(name);
        }
        definedName.setRefersToFormula(formula);
        for (CTDefinedName candidate : workbook.getCTWorkbook().getDefinedNames().getDefinedNameArray()) {
            if (name.equals(candidate.getName())) {
                candidate.setHidden(true);
            }
        }
    }

    private void configureRecalculation(XSSFWorkbook workbook) {
        CTCalcPr calculation = workbook.getCTWorkbook().isSetCalcPr()
                ? workbook.getCTWorkbook().getCalcPr() : workbook.getCTWorkbook().addNewCalcPr();
        calculation.setCalcMode(STCalcMode.AUTO);
        calculation.setFullCalcOnLoad(true);
        calculation.setForceFullCalc(true);
        workbook.setForceFormulaRecalculation(true);
    }

    private void writeCourseSheet(Sheet sheet, CourseRevision revision) {
        Map<String, Integer> rows = new HashMap<>();
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null && row.getCell(0) != null) {
                rows.put(row.getCell(0).getStringCellValue(), rowIndex);
            }
        }
        set(sheet.getRow(rows.get("course_name")), 1, revision.getName());
        set(sheet.getRow(rows.get("course_description")), 1, revision.getDescription());
        set(sheet.getRow(rows.get("course_type")), 1, revision.getType());
        set(sheet.getRow(rows.get("course_level")), 1, revision.getLevel());
    }

    private void writeTopicRow(Row row, TopicRevision topic) {
        set(row, 0, key("T", topic.getId(), topic.getOrderIndex()));
        set(row, 1, topic.getName());
        set(row, 2, topic.getDescription());
    }

    private void writeLessonRow(Row row, TopicRevision topic, LessonRevision lesson,
            Map<Long, String> lessonAudioPaths) {
        set(row, 0, key("L", lesson.getId(), lesson.getOrderIndex()));
        set(row, 1, key("T", topic.getId(), topic.getOrderIndex()));
        set(row, 2, lesson.getTitle());
        set(row, 3, lesson.getDescription());
        set(row, 4, lesson.getContent());
        set(row, 5, topic.getName());
        String audioPath = lessonAudioPaths.containsKey(lesson.getId())
                ? lessonAudioPaths.get(lesson.getId()) : lesson.getAudioUrl();
        set(row, 6, audioPath);
    }

    private String key(String prefix, Long id, int order) {
        return prefix + (id == null ? order : id);
    }

    private CellStyle[] dataStyles(Row row) {
        CellStyle[] styles = new CellStyle[HEADERS.length];
        for (int column = 0; column < styles.length; column++) {
            Cell cell = row == null ? null : row.getCell(column);
            styles[column] = cell == null ? null : cell.getCellStyle();
        }
        return styles;
    }

    private void applyStyles(Row row, CellStyle[] styles) {
        for (int column = 0; column < styles.length; column++) {
            if (styles[column] != null) {
                row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellStyle(styles[column]);
            }
        }
    }

    private String formula(Row row, int column) {
        Cell cell = row == null ? null : row.getCell(column);
        return cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                ? cell.getCellFormula() : null;
    }

    private void writeFingerprint(Row row, String formula) {
        Cell cell = row.getCell(36, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if (formula != null) {
            cell.setCellFormula(formula);
        } else {
            cell.setBlank();
        }
    }

    private void updateContentTables(Sheet content, int lastRow) {
        if (!(content instanceof XSSFSheet xssfContent)) {
            return;
        }
        updateTables(xssfContent, lastRow, HEADERS.length);
    }

    private void updateTables(Sheet sheet, int lastRow, int columns) {
        if (!(sheet instanceof XSSFSheet xssfSheet)) {
            return;
        }
        updateTables(xssfSheet, lastRow, columns);
    }

    private void updateTables(XSSFSheet sheet, int lastRow, int columns) {
        AreaReference area = new AreaReference(new CellReference(0, 0),
                new CellReference(Math.max(0, lastRow), columns - 1), SpreadsheetVersion.EXCEL2007);
        for (XSSFTable table : sheet.getTables()) {
            table.setArea(area);
        }
    }

    private void writeRow(Row row, CourseRevision revision, TopicRevision topic, LessonRevision lesson,
            QuestionRevision question, Map<Long, String> lessonAudioPaths) {
        set(row, 0, topic.getName());
        set(row, 1, topic.getDescription());
        set(row, 2, lesson.getTitle());
        set(row, 3, lesson.getDescription());
        set(row, 4, lesson.getContent());
        set(row, 5, lesson.getSkillType());
        String audioPath = lessonAudioPaths.containsKey(lesson.getId())
                ? lessonAudioPaths.get(lesson.getId()) : lesson.getAudioUrl();
        set(row, 6, audioPath);
        set(row, 7, question.getType());
        set(row, 8, question.getQuestionText());
        set(row, 9, question.getExplanation());
         set(row, 10, question.getCorrectAnswer());
         writeOptions(row, question.getOptions());
        set(row, 28, revision.getCourse() == null ? null : revision.getCourse().getId());
        set(row, 29, topic.getId());
        set(row, 30, lesson.getId());
        set(row, 31, question.getId());
    }

    private void writeOptions(Row row, List<QuestionOptionRevision> options) {
        if (options.size() > 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Câu hỏi vượt quá số lượng tối đa 4 lựa chọn.");
        }
        List<QuestionOptionRevision> sortedOptions = options.stream()
                .sorted(Comparator.comparingInt(QuestionOptionRevision::getOrderIndex).thenComparing(QuestionOptionRevision::getId))
                .toList();
        String correctOption = null;
        for (int i = 0; i < sortedOptions.size(); i++) {
            QuestionOptionRevision option = sortedOptions.get(i);
            set(row, 11 + i, option.getOptionText());
            set(row, 32 + i, option.getId());
            if (option.isCorrect()) {
                correctOption = String.valueOf((char) ('A' + i));
            }
        }
        set(row, 15, correctOption);
    }

    private void set(Row row, int column, Object value) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof Enum<?> enumeration) {
            cell.setCellValue(enumeration.name());
        } else {
            cell.setCellValue(value.toString());
        }
    }
}
