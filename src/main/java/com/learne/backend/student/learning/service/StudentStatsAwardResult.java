package com.learne.backend.student.learning.service;

import com.learne.backend.student.learning.dto.StudentStatsResponse;

public record StudentStatsAwardResult(boolean xpAwarded, StudentStatsResponse stats) {
}
