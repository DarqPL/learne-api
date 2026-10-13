package com.learne.backend.teacher.content.importexport.template;

import com.learne.backend.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public record ExportedAudioFile(String path, String contentType, byte[] bytes) {
    public ExportedAudioFile {
        if (bytes == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File audio không được thiếu bytes.");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
