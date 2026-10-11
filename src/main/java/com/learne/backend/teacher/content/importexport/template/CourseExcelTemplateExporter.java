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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CourseExcelTemplateExporter {
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

    /**
     * Exports the existing course revision in the legacy flat XLSX format.
     * Teacher imports use {@link #blankTemplate()} and the XLSM package contract instead.
     */
    public byte[] exportCourse(CourseRevision revision) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            createGuideSheet(workbook);
            Sheet content = createContentSheet(workbook);
            createMetadataSheet(workbook);

            int rowIndex = 1;
            List<TopicRevision> topics = revision.getTopics().stream()
                    .sorted(Comparator.comparingInt(TopicRevision::getOrderIndex).thenComparing(TopicRevision::getId))
                    .toList();
            for (TopicRevision topic : topics) {
                List<LessonRevision> lessons = topic.getLessons().stream()
                        .sorted(Comparator.comparingInt(LessonRevision::getOrderIndex).thenComparing(LessonRevision::getId))
                        .toList();
                for (LessonRevision lesson : lessons) {
                    List<QuestionRevision> questions = lesson.getQuestions().stream()
                            .sorted(Comparator.comparingInt(QuestionRevision::getOrderIndex).thenComparing(QuestionRevision::getId))
                            .toList();
                    for (QuestionRevision question : questions) {
                        Row row = content.createRow(rowIndex++);
                        writeRow(row, revision, topic, lesson, question);
                    }
                }
            }

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể xuất khóa học.");
        }
    }

    private Sheet createContentSheet(XSSFWorkbook workbook) {
        Sheet content = workbook.createSheet("Content");
        content.createFreezePane(0, 1);
        Row header = content.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS[i]);
            if (HEADERS[i].startsWith("_")) {
                content.setColumnHidden(i, true);
            }
        }
        setContentColumnWidths(content);
        addContentHeaderComments(workbook, content, header);
        return content;
    }

    private void createGuideSheet(XSSFWorkbook workbook) {
        Sheet guide = workbook.createSheet("HuongDan");
        guide.setColumnWidth(0, 9500);
        guide.setColumnWidth(1, 14000);

        writeGuideRow(guide, 0, "Huong dan xuat khoa hoc bang Excel", "Du lieu xuat nam trong sheet Content.");
        writeGuideRow(guide, 2, "Dinh dang", "Day la file XLSX legacy de xem hoac luu tru, khong dung cho quy trinh nhap moi.");
        writeGuideRow(guide, 3, "Nhap moi", "Tai template course-template.xlsm, sua du lieu, roi dong goi file .xlsm va audio vao goi nen .zip/.rar/.7z.");
        writeGuideRow(guide, 4, "Dong mau", "Dong mau chi la tai lieu tham khao; quy trinh nhap moi bo qua dong co dau SAMPLE_ROW.");
        writeGuideRow(guide, 6, "course_type", "COMPREHENSIVE hoac SKILL_FOCUSED.");
        writeGuideRow(guide, 7, "skill_type", "READING, LISTENING, SPEAKING, WRITING, VOCABULARY, GRAMMAR.");
        writeGuideRow(guide, 8, "question_type", "MULTIPLE_CHOICE dung option_a..option_d va correct_option. SHORT_ANSWER dung correct_answer.");
        writeGuideRow(guide, 9, "placement_test", "TRUE hoac FALSE.");
        writeGuideRow(guide, 10, "Thu tu", "Dung topic_order, lesson_order, question_order de sap xep noi dung.");
    }

    private void writeGuideRow(Sheet sheet, int rowIndex, String label, String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value);
    }

    private void setContentColumnWidths(Sheet content) {
        for (int i = 0; i < HEADERS.length; i++) {
            content.setColumnWidth(i, HEADERS[i].startsWith("_") ? 3000 : 5200);
        }
        content.setColumnWidth(1, 9000);
        content.setColumnWidth(11, 12000);
        content.setColumnWidth(18, 9000);
        content.setColumnWidth(19, 9000);
    }

    private void addContentHeaderComments(XSSFWorkbook workbook, Sheet content, Row header) {
        CreationHelper helper = workbook.getCreationHelper();
        Drawing<?> drawing = content.createDrawingPatriarch();
        addComment(helper, drawing, header.getCell(0), "Ten khoa hoc. Tat ca dong nen dung cung thong tin khoa hoc.");
        addComment(helper, drawing, header.getCell(2), "Vi du: COMPREHENSIVE hoac SKILL_FOCUSED.");
        addComment(helper, drawing, header.getCell(12), "Vi du: READING, LISTENING, SPEAKING, WRITING, VOCABULARY, GRAMMAR.");
        addComment(helper, drawing, header.getCell(15), "Duong dan file audio trong goi nen, vi du audio/greetings-lesson.mp3.");
        addComment(helper, drawing, header.getCell(17), "MULTIPLE_CHOICE dung option_a..d; SHORT_ANSWER dung correct_answer.");
        addComment(helper, drawing, header.getCell(25), "Dap an dung cho cau hoi trac nghiem: A, B, C hoac D.");
    }

    private void addComment(CreationHelper helper, Drawing<?> drawing, Cell cell, String text) {
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 3);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 4);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(text));
        cell.setCellComment(comment);
    }

    private void createMetadataSheet(XSSFWorkbook workbook) {
        Sheet metadata = workbook.createSheet("_metadata");
        metadata.createRow(0).createCell(0).setCellValue("_template_version");
        metadata.getRow(0).createCell(1).setCellValue("v1");
        metadata.createRow(1).createCell(0).setCellValue("_template_type");
        metadata.getRow(1).createCell(1).setCellValue("flat_course_content");
        workbook.setSheetHidden(workbook.getSheetIndex(metadata), true);
    }

    private void writeRow(Row row, CourseRevision revision, TopicRevision topic, LessonRevision lesson,
            QuestionRevision question) {
        row.createCell(0).setCellValue(revision.getName());
        row.createCell(1).setCellValue(nullToBlank(revision.getDescription()));
        row.createCell(2).setCellValue(revision.getType().name());
        row.createCell(3).setCellValue(revision.getLevel());
        row.createCell(4).setCellValue(revision.getOrderIndex());
        row.createCell(5).setCellValue(topic.getOrderIndex());
        row.createCell(6).setCellValue(topic.getName());
        row.createCell(7).setCellValue(nullToBlank(topic.getDescription()));
        row.createCell(8).setCellValue(lesson.getOrderIndex());
        row.createCell(9).setCellValue(lesson.getTitle());
        row.createCell(10).setCellValue(nullToBlank(lesson.getDescription()));
        row.createCell(11).setCellValue(nullToBlank(lesson.getContent()));
        row.createCell(12).setCellValue(lesson.getSkillType().name());
        row.createCell(13).setCellValue(lesson.getXpReward());
        row.createCell(14).setCellValue(lesson.isPlacementTest());
        row.createCell(15).setBlank();
        row.createCell(16).setCellValue(question.getOrderIndex());
        row.createCell(17).setCellValue(question.getType().name());
        row.createCell(18).setCellValue(question.getQuestionText());
        row.createCell(19).setCellValue(nullToBlank(question.getExplanation()));
        row.createCell(20).setCellValue(nullToBlank(question.getCorrectAnswer()));
        writeOptions(row, question.getOptions());
        row.createCell(26).setCellValue(nullToBlank(question.getDifficulty()));
        row.createCell(27).setCellValue(nullToBlank(question.getKnowledgePoint()));
        row.createCell(28).setCellValue(revision.getCourse().getId());
        row.createCell(29).setCellValue(topic.getId());
        row.createCell(30).setCellValue(lesson.getId());
        row.createCell(31).setCellValue(question.getId());
        row.createCell(36).setBlank();
    }

    private void writeOptions(Row row, List<QuestionOptionRevision> options) {
        List<QuestionOptionRevision> sortedOptions = options.stream()
                .sorted(Comparator.comparingInt(QuestionOptionRevision::getOrderIndex).thenComparing(QuestionOptionRevision::getId))
                .limit(4)
                .toList();
        String correctOption = null;
        for (int i = 0; i < sortedOptions.size(); i++) {
            QuestionOptionRevision option = sortedOptions.get(i);
            row.createCell(21 + i).setCellValue(option.getOptionText());
            row.createCell(32 + i).setCellValue(option.getId());
            if (option.isCorrect()) {
                correctOption = String.valueOf((char) ('A' + i));
            }
        }
        row.createCell(25).setCellValue(nullToBlank(correctOption));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
