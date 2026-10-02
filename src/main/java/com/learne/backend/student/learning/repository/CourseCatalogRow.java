package com.learne.backend.student.learning.repository;

import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;

public interface CourseCatalogRow {
    Long getId();
    String getName();
    String getDescription();
    CourseType getType();
    String getLevel();
    Long getTeacherId();
    String getTeacherFullName();
    String getTeacherEmail();
    String getTeacherAvatarUrl();
    long getApprovedTopicCount();
    long getApprovedLessonCount();
    long getApprovedQuestionCount();
    Instant getPublishedAt();
    CoursePricingType getPricingType();
    Long getPriceAmountVnd();
}
