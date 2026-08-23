package com.learne.backend.storage.service;

import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {
    StorageUploadResult uploadTeacherAvatar(MultipartFile file);

    StorageUploadResult uploadTeacherIntroductionVideo(MultipartFile file);

    void delete(String publicId, StorageResourceType resourceType);
}
