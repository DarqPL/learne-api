package com.learne.backend.storage.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class CloudinaryStorageService implements StorageService {
    private static final int MAX_SIGNATURE_BYTES = 12;
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
        if (!hasAllowedSignature(file, contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private boolean hasAllowedSignature(MultipartFile file, String contentType) {
        byte[] signature;
        try (InputStream inputStream = file.getInputStream()) {
            signature = inputStream.readNBytes(MAX_SIGNATURE_BYTES);
        } catch (IOException ex) {
            return false;
        }

        return switch (contentType) {
            case "image/png" -> startsWith(signature, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(signature, 0xFF, 0xD8, 0xFF);
            case "image/webp" -> startsWith(signature, 0x52, 0x49, 0x46, 0x46) && matchesAt(signature, 8, 0x57, 0x45, 0x42, 0x50);
            case "video/mp4", "video/quicktime" -> matchesAt(signature, 4, 0x66, 0x74, 0x79, 0x70);
            case "video/webm" -> startsWith(signature, 0x1A, 0x45, 0xDF, 0xA3);
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, int... expected) {
        return matchesAt(bytes, 0, expected);
    }

    private boolean matchesAt(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[offset + index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
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
