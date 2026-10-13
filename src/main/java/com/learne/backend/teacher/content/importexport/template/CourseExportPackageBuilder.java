package com.learne.backend.teacher.content.importexport.template;

import com.learne.backend.shared.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CourseExportPackageBuilder {
    public byte[] build(long courseId, byte[] workbookBytes, List<ExportedAudioFile> audioFiles) {
        if (workbookBytes == null) {
            throw invalidInput("Workbook không được thiếu bytes.");
        }
        if (audioFiles == null) {
            throw invalidInput("Danh sách file audio không được để trống.");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output)) {
            Set<String> paths = new HashSet<>();
            write(zip, paths, "course-" + courseId + ".xlsm", workbookBytes);
            for (ExportedAudioFile audio : audioFiles) {
                if (audio == null) {
                    throw invalidInput("File audio không được để trống.");
                }
                writeAudio(zip, paths, audio.path(), audio.bytes());
            }
            zip.finish();
            return output.toByteArray();
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể tạo gói xuất khóa học.");
        }
    }

    private void write(ZipOutputStream zip, Set<String> paths, String path, byte[] bytes) throws IOException {
        String safePath = safePath(path);
        if (!paths.add(safePath)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Đường dẫn trong gói xuất bị trùng: " + safePath);
        }
        zip.putNextEntry(new ZipEntry(safePath));
        if (bytes == null) {
            throw invalidInput("File xuất không được thiếu bytes.");
        }
        zip.write(bytes);
        zip.closeEntry();
    }

    private void writeAudio(ZipOutputStream zip, Set<String> paths, String path, byte[] bytes) throws IOException {
        String safePath = safePath(path);
        if (paths.contains(safePath)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Đường dẫn trong gói xuất bị trùng: " + safePath);
        }
        if (!safePath.startsWith("audio/")) {
            throw invalidInput("Đường dẫn file audio phải nằm trong thư mục audio/: " + safePath);
        }
        write(zip, paths, safePath, bytes);
    }

    private ApiException invalidInput(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private String safePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0
                || path.indexOf('\\') >= 0 || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Đường dẫn trong gói xuất không hợp lệ");
        }
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Đường dẫn trong gói xuất không hợp lệ");
            }
            if (segment.equals("..")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Đường dẫn trong gói xuất không hợp lệ");
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }
}
