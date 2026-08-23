package com.learne.backend.storage.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class CloudinaryStorageService implements StorageService {
    private static final Set<String> AVATAR_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_CONTENT_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");

    private final CloudinaryProperties properties;

    CloudinaryStorageService(CloudinaryProperties properties) {
        this.properties = properties;
    }

    @Override
    public StorageUploadResult uploadTeacherAvatar(MultipartFile file) {
        validateFile(file, AVATAR_CONTENT_TYPES, properties.getMaxAvatarBytes(), "Ảnh đại diện không hợp lệ.");
        return upload(file, avatarFolder(), StorageResourceType.IMAGE);
    }

    @Override
    public StorageUploadResult uploadTeacherIntroductionVideo(MultipartFile file) {
        validateFile(file, VIDEO_CONTENT_TYPES, properties.getMaxVideoBytes(), "Video giới thiệu không hợp lệ.");
        return upload(file, videoFolder(), StorageResourceType.VIDEO);
    }

    @Override
    public void delete(String publicId, StorageResourceType resourceType) {
        ensureConfigured();
        try {
            cloudinary().uploader().destroy(publicId, ObjectUtils.asMap("resource_type", resourceType.cloudinaryValue()));
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không thể xóa file trên Cloudinary.");
        }
    }

    private StorageUploadResult upload(MultipartFile file, String folder, StorageResourceType resourceType) {
        ensureConfigured();
        try {
            Map<?, ?> result = cloudinary().uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap("folder", folder, "resource_type", resourceType.cloudinaryValue())
            );
            Object secureUrl = result.get("secure_url");
            Object publicId = result.get("public_id");
            if (secureUrl == null || publicId == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Cloudinary không trả về URL hợp lệ.");
            }
            return new StorageUploadResult(secureUrl.toString(), publicId.toString());
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không thể upload file lên Cloudinary.");
        }
    }

    private void validateFile(MultipartFile file, Set<String> allowedContentTypes, long maxBytes, String message) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File vượt quá dung lượng cho phép.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !allowedContentTypes.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private void ensureConfigured() {
        if (isBlank(properties.getCloudName()) || isBlank(properties.getApiKey()) || isBlank(properties.getApiSecret())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cloudinary chưa được cấu hình.");
        }
    }

    private Cloudinary cloudinary() {
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", properties.getCloudName(),
                "api_key", properties.getApiKey(),
                "api_secret", properties.getApiSecret(),
                "secure", true
        ));
    }

    private String avatarFolder() {
        return normalizedProfileFolder() + "/avatars";
    }

    private String videoFolder() {
        return normalizedProfileFolder() + "/introduction-videos";
    }

    private String normalizedProfileFolder() {
        String folder = properties.getProfileFolder();
        if (folder == null || folder.isBlank()) {
            return "learne/teacher-profiles";
        }
        return folder.replaceAll("^/+|/+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
