package com.learne.backend.storage.service;

import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageDownloadResult;
import com.learne.backend.storage.dto.StorageUploadResult;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {
    StorageUploadResult uploadTeacherAvatar(MultipartFile file);

    StorageUploadResult uploadStudentAvatar(MultipartFile file);

    StorageUploadResult uploadTeacherIntroductionVideo(MultipartFile file);

    StorageUploadResult uploadLessonAudio(MultipartFile file);
    default StorageUploadResult uploadCourseCover(MultipartFile file) {
        throw new UnsupportedOperationException("Course cover upload is not supported by this storage implementation");
    }

    StorageDownloadResult downloadLessonAudio(String publicId, String fallbackUrl);

    void delete(String publicId, StorageResourceType resourceType);
}
