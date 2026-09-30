package com.learne.backend.admin.dashboard.repository;

public interface AdminDashboardOverviewRow {
    long getTotalStudents();
    long getLockedStudents();
    long getTotalTeachers();
    long getLockedTeachers();
    long getTotalCourses();
    long getTotalEnrollments();
}
