package com.learne.backend.teacher.content.importexport.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class CourseExcelTemplateExporterTests {
    @Test
    void blankTemplateReturnsXlsmWithoutStaleCalculationChain() throws Exception {
        CourseExcelTemplateExporter exporter = new CourseExcelTemplateExporter();

        byte[] actual = exporter.blankTemplate();

        assertThat(actual).containsSequence("vbaProject.bin".getBytes(StandardCharsets.UTF_8));
        assertThat(hasZipEntry(actual, "xl/calcChain.xml")).isFalse();
        assertThat(zipEntryText(actual, "[Content_Types].xml")).doesNotContain("calcChain");
        assertThat(zipEntryText(actual, "xl/_rels/workbook.xml.rels")).doesNotContain("calcChain");
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
}
