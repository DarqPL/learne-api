package com.learne.backend.storage.dto;

public record StorageDownloadResult(String contentType, byte[] bytes) {
    public StorageDownloadResult {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
