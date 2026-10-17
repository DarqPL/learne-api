package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LessonAudioFileService {
    private final ClamAvClient scanner;

    public LessonAudioFileService(ClamAvClient scanner) {
        this.scanner = scanner;
    }

    public LessonAudioFile validateAndScan(MultipartFile input) {
        if (input == null || input.isEmpty()) {
            throw invalidAudio();
        }
        if (input.getSize() > AudioFileValidator.MAX_AUDIO_BYTES) {
            throw oversizedAudio();
        }

        byte[] bytes;
        try (InputStream stream = input.getInputStream()) {
            bytes = stream.readNBytes((int) AudioFileValidator.MAX_AUDIO_BYTES + 1);
        } catch (IOException e) {
            throw invalidAudio();
        }
        if (bytes.length > AudioFileValidator.MAX_AUDIO_BYTES) {
            throw oversizedAudio();
        }
        String contentType = input.getContentType();
        if (bytes.length == 0 || !AudioFileValidator.isValidAudio(contentType, bytes)) {
            throw invalidAudio();
        }

        scanner.assertClean(LessonAudioFile.SAFE_FILENAME, bytes);
        return new LessonAudioFile(contentType, bytes);
    }

    private ApiException invalidAudio() {
        return new ApiException(HttpStatus.BAD_REQUEST, "File audio không hợp lệ");
    }

    private ApiException oversizedAudio() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "File vượt quá dung lượng cho phép");
    }
}
