package com.learne.backend.teacher.content.audio.controller;

import com.learne.backend.shared.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Global because multipart parsing and consumes matching can fail before a handler is selected. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LessonAudioExceptionHandler {
    private static final Pattern AUDIO_UPLOAD = Pattern.compile(
            "/api/teacher/content/(lessons|lesson-revisions)/[0-9]+/audio");

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MultipartException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiErrorResponse> handleUploadRequest(Exception exception, HttpServletRequest request)
            throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!"POST".equals(request.getMethod()) || !AUDIO_UPLOAD.matcher(path).matches()) {
            // Let the existing framework resolver preserve non-audio status and body contracts.
            throw exception;
        }
        HttpStatus status;
        String message;
        if (exception instanceof MaxUploadSizeExceededException) {
            status = HttpStatus.PAYLOAD_TOO_LARGE;
            message = "File vượt quá dung lượng cho phép";
        } else if (exception instanceof HttpMediaTypeNotSupportedException) {
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            message = "Audio upload requires multipart/form-data";
        } else {
            status = HttpStatus.BAD_REQUEST;
            message = "File audio không hợp lệ";
        }
        return ResponseEntity.status(status).body(new ApiErrorResponse(message));
    }
}
