package com.learne.backend.teacher.content.importexport.service;

import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import java.util.List;
import java.util.Map;

public record PreparedCourseImport(CourseImportModel model, Long expectedSourceRevisionId,
        Map<String, StorageUploadResult> uploadedByAudioPath, List<StorageUploadResult> uploadedAssets) {
    public PreparedCourseImport {
        uploadedByAudioPath = Map.copyOf(uploadedByAudioPath);
        uploadedAssets = List.copyOf(uploadedAssets);
    }
}
