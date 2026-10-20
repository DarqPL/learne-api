package com.learne.backend.teacher.content.importexport.service;

import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import java.util.List;
import java.util.Map;

public record PreparedCourseImport(CourseImportModel model, Long expectedSourceRevisionId,
        Map<String, StorageUploadResult> uploadedByAudioPath, List<StorageUploadResult> uploadedAssets,
        java.util.UUID operationId) {
    public PreparedCourseImport(CourseImportModel model, Long expectedSourceRevisionId,
            Map<String, StorageUploadResult> uploadedByAudioPath, List<StorageUploadResult> uploadedAssets) {
        this(model, expectedSourceRevisionId, uploadedByAudioPath, uploadedAssets, java.util.UUID.randomUUID());
    }
    public PreparedCourseImport {
        uploadedByAudioPath = Map.copyOf(uploadedByAudioPath);
        uploadedAssets = List.copyOf(uploadedAssets);
    }
}
