package com.learne.backend.storage.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageDownloadResult;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class CloudinaryStorageService implements StorageService {
    private static final int MAX_SIGNATURE_BYTES = 12;
    private static final int DOWNLOAD_TIMEOUT_MILLIS = 8_000;
    private static final int MAX_COURSE_COVER_BYTES = 5 * 1024 * 1024;
    private static final int MAX_COURSE_COVER_DIMENSION = 4096;
    private static final long MAX_COURSE_COVER_PIXELS = 16_000_000L;
    private static final Set<String> AVATAR_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_CONTENT_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");

    private final CloudinaryProperties properties;
    private final ConnectionFactory connectionFactory;

    @Autowired
    CloudinaryStorageService(CloudinaryProperties properties) {
        this(properties, URL::openConnection);
    }

    CloudinaryStorageService(CloudinaryProperties properties, ConnectionFactory connectionFactory) {
        this.properties = properties;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public StorageUploadResult uploadTeacherAvatar(MultipartFile file) {
        validateFile(file, AVATAR_CONTENT_TYPES, properties.getMaxAvatarBytes(), "Ảnh đại diện không hợp lệ.");
        return upload(file, avatarFolder(), StorageResourceType.IMAGE);
    }

    @Override
    public StorageUploadResult uploadStudentAvatar(MultipartFile file) {
        validateFile(file, AVATAR_CONTENT_TYPES, properties.getMaxAvatarBytes(), "Ảnh đại diện không hợp lệ.");
        return upload(file, "learne/student-profiles/avatars", StorageResourceType.IMAGE);
    }

    @Override
    public StorageUploadResult uploadTeacherIntroductionVideo(MultipartFile file) {
        validateFile(file, VIDEO_CONTENT_TYPES, properties.getMaxVideoBytes(), "Video giới thiệu không hợp lệ.");
        return upload(file, videoFolder(), StorageResourceType.VIDEO);
    }

    @Override
    public StorageUploadResult uploadLessonAudio(MultipartFile file) {
        validateFile(file, AudioFileValidator.ALLOWED_TYPES, AudioFileValidator.MAX_AUDIO_BYTES, "File audio không hợp lệ.");
        return upload(file, courseAudioFolder(), StorageResourceType.VIDEO);
    }

    @Override
    public StorageUploadResult uploadCourseCover(MultipartFile file) {
        validateCourseCover(file);
        return uploadUnique(file, "learne/course-covers", StorageResourceType.IMAGE);
    }

    @Override
    public StorageDownloadResult downloadLessonAudio(String publicId, String fallbackUrl) {
        ensureConfigured();
        String url = fallbackUrl;
        if (isBlank(url) && !isBlank(publicId)) {
            url = cloudinary().url()
                    .resourceType(StorageResourceType.VIDEO.cloudinaryValue())
                    .secure(true)
                    .generate(publicId);
        }
        if (isBlank(url)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không tìm thấy URL audio bài học.");
        }
        if (!isApprovedCloudinaryUrl(url)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không thể tải audio bài học từ Cloudinary.");
        }

        try {
            URLConnection connection = connectionFactory.open(URI.create(url).toURL());
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MILLIS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MILLIS);
            if (connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setInstanceFollowRedirects(false);
                int responseCode = httpConnection.getResponseCode();
                if (responseCode >= 300 && responseCode < 400) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY,
                            "Không thể tải audio bài học từ Cloudinary.");
                }
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readBounded(input);
                if (bytes.length > AudioFileValidator.MAX_AUDIO_BYTES) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "Audio bài học vượt quá dung lượng cho phép.");
                }
                String contentType = URLConnection.guessContentTypeFromName(url);
                if (contentType == null) {
                    contentType = AudioFileValidator.detectContentType(bytes);
                }
                if (!AudioFileValidator.isValidAudio(contentType, bytes)) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "Không thể tải audio bài học từ Cloudinary.");
                }
                return new StorageDownloadResult(contentType, bytes);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (IllegalArgumentException | IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không thể tải audio bài học từ Cloudinary.");
        }
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

    private StorageUploadResult uploadUnique(MultipartFile file, String folder, StorageResourceType resourceType) {
        ensureConfigured();
        try {
            Map<?, ?> result = cloudinary().uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", resourceType.cloudinaryValue(),
                            "unique_filename", true,
                            "overwrite", false,
                            "invalidate", true
                    )
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

    private void validateCourseCover(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh bìa khóa học không hợp lệ.");
        }
        if (file.getSize() > MAX_COURSE_COVER_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File vượt quá dung lượng cho phép.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !AVATAR_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh bìa khóa học không hợp lệ.");
        }
        if (!hasAllowedSignature(file, contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh bìa khóa học không hợp lệ.");
        }

        try {
            byte[] bytes = file.getBytes();
            decodeAndValidateImage(contentType, bytes);
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh bìa khóa học không hợp lệ.");
        }
    }

    private void decodeAndValidateImage(String contentType, byte[] bytes) throws IOException {
        if ("image/webp".equals(contentType) && !hasValidWebpRiff(bytes)) {
            throw new IOException("Invalid WebP RIFF structure");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Image input stream unavailable");
            }
            ImageReader reader = selectImageReader(contentType, input);
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateImageDimensions(width, height);
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("Decoded image is incomplete");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private ImageReader selectImageReader(String contentType, ImageInputStream input) throws IOException {
        String readerClass = switch (contentType) {
            case "image/jpeg" -> "com.sun.imageio.plugins.jpeg.JPEGImageReader";
            case "image/png" -> "com.sun.imageio.plugins.png.PNGImageReader";
            case "image/webp" -> "com.twelvemonkeys.imageio.plugins.webp.WebPImageReader";
            default -> throw new IOException("Unsupported image MIME");
        };
        var readers = ImageIO.getImageReaders(input);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader.getClass().getName().equals(readerClass)
                    && reader.getFormatName().equalsIgnoreCase(contentType.substring("image/".length()))) {
                return reader;
            }
            reader.dispose();
        }
        throw new IOException("No matching image reader");
    }

    private void validateImageDimensions(int width, int height) {
        if (width <= 0 || height <= 0
                || width > MAX_COURSE_COVER_DIMENSION
                || height > MAX_COURSE_COVER_DIMENSION
                || (long) width * height > MAX_COURSE_COVER_PIXELS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh bìa khóa học vượt quá kích thước cho phép.");
        }
    }

    private boolean hasValidWebpRiff(byte[] bytes) {
        if (bytes.length < 20
                || !startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                || !matchesAt(bytes, 8, 0x57, 0x45, 0x42, 0x50)) {
            return false;
        }
        int riffSize = (bytes[4] & 0xFF)
                | ((bytes[5] & 0xFF) << 8)
                | ((bytes[6] & 0xFF) << 16)
                | ((bytes[7] & 0xFF) << 24);
        return riffSize >= 12 && riffSize + 8 == bytes.length;
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
            case "audio/mpeg" -> startsWith(signature, 0x49, 0x44, 0x33)
                    || (signature.length >= 2 && (signature[0] & 0xFF) == 0xFF && (signature[1] & 0xE0) == 0xE0);
            case "audio/wav", "audio/x-wav" -> startsWith(signature, 0x52, 0x49, 0x46, 0x46)
                    && matchesAt(signature, 8, 0x57, 0x41, 0x56, 0x45);
            case "audio/mp4" -> matchesAt(signature, 4, 0x66, 0x74, 0x79, 0x70);
            case "audio/ogg" -> startsWith(signature, 0x4F, 0x67, 0x67, 0x53);
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, int... expected) {
        return matchesAt(bytes, 0, expected);
    }

    private byte[] readBounded(InputStream input) throws IOException {
        long deadline = System.nanoTime() + DOWNLOAD_TIMEOUT_MILLIS * 1_000_000L;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() <= AudioFileValidator.MAX_AUDIO_BYTES) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("Audio download deadline exceeded.");
            }
            int read = input.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            output.write(buffer, 0, read);
            if (output.size() > AudioFileValidator.MAX_AUDIO_BYTES) {
                return output.toByteArray();
            }
        }
        return output.toByteArray();
    }

    private boolean isApprovedCloudinaryUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            String cloudName = properties.getCloudName();
            if (isBlank(cloudName)) {
                return false;
            }
            String configuredHost = cloudName + ".res.cloudinary.com";
            String configuredPathPrefix = "/" + cloudName + "/";
            String path = uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && ((configuredHost.equalsIgnoreCase(host))
                    || ("res.cloudinary.com".equalsIgnoreCase(host)
                    && path != null
                    && (path.equalsIgnoreCase("/" + cloudName)
                    || path.regionMatches(true, 0, configuredPathPrefix, 0, configuredPathPrefix.length()))));
        } catch (IllegalArgumentException ex) {
            return false;
        }
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

    private String courseAudioFolder() {
        return "learne/course-audio";
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

    @FunctionalInterface
    interface ConnectionFactory {
        URLConnection open(URL url) throws IOException;
    }
}
