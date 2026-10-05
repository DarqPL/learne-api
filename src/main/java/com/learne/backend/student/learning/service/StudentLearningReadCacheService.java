package com.learne.backend.student.learning.service;

import com.learne.backend.cache.CacheNames;
import com.learne.backend.cache.CatalogCacheVersionService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.dto.SharedStudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseSummaryResponse;
import com.learne.backend.student.learning.dto.StudentDashboardResponse;
import com.learne.backend.shared.pagination.CursorPageResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class StudentLearningReadCacheService {
    private final StudentLearningService studentLearningService;
    private final CatalogCacheVersionService catalogCacheVersionService;
    private final ObjectProvider<StudentLearningReadCacheService> selfProvider;

    public StudentLearningReadCacheService(StudentLearningService studentLearningService,
                                           CatalogCacheVersionService catalogCacheVersionService,
                                           ObjectProvider<StudentLearningReadCacheService> selfProvider) {
        this.studentLearningService = studentLearningService;
        this.catalogCacheVersionService = catalogCacheVersionService;
        this.selfProvider = selfProvider;
    }

    @Cacheable(cacheNames = CacheNames.STUDENT_CATALOG,
            key = "T(com.learne.backend.cache.CacheKeySupport).studentCatalogPage(#student.id, #level, #teacherId, #query, #size, #cursor, #root.target.catalogVersion())")
    public CursorPageResponse<StudentCourseSummaryResponse> listCourses(
            User student, String level, Long teacherId, String query, int size, String cursor) {
        return studentLearningService.listCourses(student, level, teacherId, query, size, cursor);
    }

    public StudentCourseDetailResponse getCourse(User student, Long courseId) {
        SharedStudentCourseDetailResponse shared = selfProvider.getObject().getSharedCourseDetail(courseId);
        return studentLearningService.getCourse(student, courseId, shared);
    }

    @Cacheable(cacheNames = CacheNames.STUDENT_COURSE_DETAIL,
            key = "T(com.learne.backend.cache.CacheKeySupport).studentCourseDetail(#courseId, #root.target.catalogVersion())")
    public SharedStudentCourseDetailResponse getSharedCourseDetail(Long courseId) {
        return studentLearningService.getSharedCourseDetail(courseId);
    }

    @Cacheable(cacheNames = CacheNames.STUDENT_DASHBOARD,
            key = "T(com.learne.backend.cache.CacheKeySupport).studentDashboard(#student.id)")
    public StudentDashboardResponse getDashboard(User student) {
        return studentLearningService.getDashboard(student);
    }

    public long catalogVersion() {
        return catalogCacheVersionService.currentVersion();
    }
}
