package com.learne.backend.shared.pagination;

import java.util.List;

public record NumberedPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
