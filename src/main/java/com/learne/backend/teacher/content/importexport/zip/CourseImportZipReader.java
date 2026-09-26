package com.learne.backend.teacher.content.importexport.zip;

import com.learne.backend.shared.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CourseImportZipReader {
    private static final int MAX_ENTRIES = 50;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 100L * 1024L * 1024L;
    private static final String INVALID_ZIP_PATH = "Đường dẫn trong file ZIP không hợp lệ";

    public CourseImportPackage read(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw badRequest("File ZIP không hợp lệ");
        }

        byte[] workbook = null;
        Map<String, CourseImportPackage.ImportFile> audioFiles = new LinkedHashMap<>();
        int entryCount = 0;
        long totalBytes = 0L;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = safePath(entry.getName());
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw badRequest("Gói nhập vượt quá số lượng file cho phép");
                }

                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                EntryBytes entryBytes = readEntry(zip, totalBytes);
                totalBytes = entryBytes.totalBytes();
                byte[] bytes = entryBytes.bytes();

                if (path.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                    if (workbook != null) {
                        throw badRequest("Gói nhập phải có đúng một file workbook .xlsx");
                    }
                    workbook = bytes;
                } else if (path.startsWith("audio/")) {
                    audioFiles.put(path, new CourseImportPackage.ImportFile(path, contentType(path), bytes));
                } else {
                    throw badRequest("File audio phải nằm trong thư mục audio/");
                }

                zip.closeEntry();
            }
        } catch (IOException e) {
            throw badRequest("File ZIP không hợp lệ");
        }

        if (workbook == null) {
            throw badRequest("Gói nhập phải có đúng một file workbook .xlsx");
        }

        return new CourseImportPackage(workbook, Map.copyOf(audioFiles));
    }

    private EntryBytes readEntry(ZipInputStream zip, long currentTotalBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalBytes = currentTotalBytes;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            totalBytes += read;
            if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw badRequest("Gói nhập vượt quá dung lượng cho phép");
            }
            output.write(buffer, 0, read);
        }
        return new EntryBytes(output.toByteArray(), totalBytes);
    }

    private String safePath(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0) {
            throw badRequest(INVALID_ZIP_PATH);
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\") || entryName.matches("^[A-Za-z]:.*")) {
            throw badRequest(INVALID_ZIP_PATH);
        }

        String normalizedSeparators = entryName.replace('\\', '/');
        String[] segments = normalizedSeparators.split("/");
        List<String> safeSegments = new ArrayList<>();
        for (String segment : segments) {
            if (segment.equals("..") || segment.isBlank()) {
                throw badRequest(INVALID_ZIP_PATH);
            }
            if (!segment.equals(".")) {
                safeSegments.add(segment);
            }
        }
        if (safeSegments.isEmpty()) {
            throw badRequest(INVALID_ZIP_PATH);
        }
        return String.join("/", safeSegments);
    }

    private String contentType(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lowerPath.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lowerPath.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lowerPath.endsWith(".ogg")) {
            return "audio/ogg";
        }
        return "application/octet-stream";
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private record EntryBytes(byte[] bytes, long totalBytes) {}
}
