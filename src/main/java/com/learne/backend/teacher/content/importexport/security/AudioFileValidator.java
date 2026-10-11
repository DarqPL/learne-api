package com.learne.backend.teacher.content.importexport.security;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.importexport.archive.CourseImportPackage;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AudioFileValidator {
    private static final long MAX_AUDIO_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/ogg");
    private static final String INVALID_AUDIO = "File audio không hợp lệ";

    public void validate(CourseImportPackage.ImportFile file) {
        if (file == null || file.bytes() == null || file.bytes().length == 0) {
            throw badRequest(INVALID_AUDIO);
        }
        if (file.bytes().length > MAX_AUDIO_BYTES) {
            throw badRequest("File vượt quá dung lượng cho phép");
        }
        if (file.contentType() == null || !ALLOWED_TYPES.contains(file.contentType())) {
            throw badRequest(INVALID_AUDIO);
        }
        if (!hasValidSignature(file.contentType(), file.bytes())) {
            throw badRequest(INVALID_AUDIO);
        }
    }

    private boolean hasValidSignature(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "audio/mpeg" -> isMp3(bytes);
            case "audio/wav", "audio/x-wav" -> isWav(bytes);
            case "audio/mp4" -> isM4a(bytes);
            case "audio/ogg" -> startsWith(bytes, "OggS");
            default -> false;
        };
    }

    private boolean isMp3(byte[] bytes) {
        return startsWith(bytes, "ID3")
                || (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0);
    }

    private boolean isWav(byte[] bytes) {
        return bytes.length >= 12 && startsWith(bytes, "RIFF") && asciiAt(bytes, 8, "WAVE");
    }

    private boolean isM4a(byte[] bytes) {
        return bytes.length >= 8 && asciiAt(bytes, 4, "ftyp");
    }

    private boolean startsWith(byte[] bytes, String signature) {
        return asciiAt(bytes, 0, signature);
    }

    private boolean asciiAt(byte[] bytes, int offset, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
