package com.learne.backend.teacher.content.importexport.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CourseExcelTemplateExporterTests {
    @Test
    void blankTemplateIncludesGuideSheetAndSampleRow() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exporter.blankTemplate()))) {
            Sheet guide = workbook.getSheet("HuongDan");
            Sheet content = workbook.getSheet("Content");

            assertThat(guide).isNotNull();
            assertThat(guide.getRow(0).getCell(0).getStringCellValue()).contains("Huong dan nhap khoa hoc");
            assertThat(guide.getRow(3).getCell(0).getStringCellValue()).contains("ZIP");
            assertThat(guide.getRow(6).getCell(1).getStringCellValue()).contains("COMPREHENSIVE");

            Row sample = content.getRow(1);
            assertThat(sample.getCell(0).getStringCellValue()).isEqualTo("English A1 Sample");
            assertThat(sample.getCell(15).getStringCellValue()).isEqualTo("audio/greetings-lesson.mp3");
            assertThat(sample.getCell(36).getStringCellValue()).isEqualTo("SAMPLE_ROW");
        }
    }

    @Test
    void parserIgnoresTemplateSampleRow() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();
        CourseExcelTemplateParser parser = new CourseExcelTemplateParser();

        CourseImportModel model = parser.parse(exporter.blankTemplate());

        assertThat(model.name()).isNull();
        assertThat(model.topics()).isEmpty();
    }
}
