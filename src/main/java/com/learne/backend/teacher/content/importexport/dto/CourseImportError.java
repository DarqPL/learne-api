package com.learne.backend.teacher.content.importexport.dto;

public record CourseImportError(Integer rowNumber, String field, String message) {
}
