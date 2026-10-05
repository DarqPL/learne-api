package com.learne.backend.shared.pagination;

import java.util.List;

public record CursorPageResponse<T>(List<T> content, String nextCursor, boolean hasNext, int size) {
}
