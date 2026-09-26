package com.learne.backend.teacher.content.importexport.dto;

import java.util.List;

public record CourseImportResponse(Long courseId, Long revisionId, String status, List<CourseImportError> errors) {
}
