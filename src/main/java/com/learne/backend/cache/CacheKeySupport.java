package com.learne.backend.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class CacheKeySupport {
    private CacheKeySupport() {
    }

    public static String studentCatalog(Long studentId, String level, Long teacherId, Long catalogVersion) {
        return "student:%d:level:%s:teacher:%s:catalogVersion:%d".formatted(
                Objects.requireNonNull(studentId, "studentId is required"),
                normalize(level),
                teacherId == null ? "all" : teacherId,
                catalogVersion == null ? 0L : catalogVersion);
    }

    public static String studentCourseDetail(Long studentId, Long courseId, Long catalogVersion) {
        return "student:%d:course:%d:catalogVersion:%d".formatted(
                Objects.requireNonNull(studentId, "studentId is required"),
                Objects.requireNonNull(courseId, "courseId is required"),
                catalogVersion == null ? 0L : catalogVersion);
    }

    public static String studentDashboard(Long studentId) {
        return "student:%d".formatted(Objects.requireNonNull(studentId, "studentId is required"));
    }

    public static String teacherDashboard(Long teacherId) {
        return "teacher:%d".formatted(Objects.requireNonNull(teacherId, "teacherId is required"));
    }

    public static String adminDashboard() {
        return "admin:global";
    }

    public static String leaderboard(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return "top:%d".formatted(limit);
    }

    public static String courseAccess(Long studentId) {
        return "student:%d".formatted(Objects.requireNonNull(studentId, "studentId is required"));
    }

    public static String logKeyHash(Object key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "all";
        }
        return value.trim();
    }
}
