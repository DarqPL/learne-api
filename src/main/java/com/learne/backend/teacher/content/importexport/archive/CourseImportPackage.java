package com.learne.backend.teacher.content.importexport.archive;

import java.util.Map;

public record CourseImportPackage(byte[] workbook, Map<String, ImportFile> audioFiles) {
    public record ImportFile(String path, String contentType, byte[] bytes) {}
}
