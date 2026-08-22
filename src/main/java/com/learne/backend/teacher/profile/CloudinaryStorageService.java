package com.learne.backend.teacher.profile;

import org.springframework.web.multipart.MultipartFile;

interface CloudinaryStorageService {
    CloudinaryUploadResult uploadTeacherAvatar(MultipartFile file);

    CloudinaryUploadResult uploadTeacherIntroductionVideo(MultipartFile file);

    void delete(String publicId, CloudinaryResourceType resourceType);
}
