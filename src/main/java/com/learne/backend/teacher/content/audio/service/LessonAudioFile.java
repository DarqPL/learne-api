package com.learne.backend.teacher.content.audio.service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.springframework.web.multipart.MultipartFile;

public final class LessonAudioFile implements MultipartFile {
    static final String SAFE_FILENAME = "lesson-audio";

    private final String contentType;
    private final byte[] bytes;

    public LessonAudioFile(String contentType, byte[] bytes) {
        this.contentType = contentType;
        this.bytes = bytes.clone();
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return SAFE_FILENAME;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(File destination) throws IOException {
        try (InputStream input = getInputStream()) {
            Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
