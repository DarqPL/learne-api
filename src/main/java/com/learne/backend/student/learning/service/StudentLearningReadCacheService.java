package com.learne.backend.student.learning.service;

import com.learne.backend.cache.CacheNames;
import com.learne.backend.cache.CatalogCacheVersionService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.dto.StudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseSummaryResponse;
import com.learne.backend.student.learning.dto.StudentDashboardResponse;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class StudentLearningReadCacheService {
    private final StudentLearningService studentLearningService;
    private final CatalogCacheVersionService catalogCacheVersionService;

    public StudentLearningReadCacheService(StudentLearningService studentLearningService,
                                           CatalogCacheVersionService catalogCacheVersionService) {
        this.studentLearningService = studentLearningService;
        this.catalogCacheVersionService = catalogCacheVersionService;
    }

    @Cacheable(cacheNames = CacheNames.STUDENT_CATALOG,
            key = "T(com.learne.backend.cache.CacheKeySupport).studentCatalog(#student.id, #level, #teacherId, #root.target.catalogVersion())")
    public List<StudentCourseSummaryResponse> listCourses(User student, String level, Long teacherId) {
        return studentLearningService.listCourses(student, level, teacherId);
    }

    @Cacheable(cacheNames = CacheNames.STUDENT_COURSE_DETAIL,
            key = "T(com.learne.backend.cache.CacheKeySupport).studentCourseDetail(#student.id, #courseId, #root.target.catalogVersion())")
    public StudentCourseDetailResponse getCourse(User student, Long courseId) {
        return studentLearningService.getCourse(student, courseId);
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
