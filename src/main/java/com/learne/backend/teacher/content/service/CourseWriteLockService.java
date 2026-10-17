package com.learne.backend.teacher.content.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.repository.CourseRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Entry-only lock: call once, before loading a graph or making any writes. */
@Service
public class CourseWriteLockService {
    private final EntityManager entityManager;
    private final CourseRepository courses;

    public CourseWriteLockService(EntityManager entityManager, CourseRepository courses) {
        this.entityManager = entityManager;
        this.courses = courses;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Course teacherCourse(Long courseId, User teacher) {
        entityManager.clear();
        return courses.findLockedByIdAndTeacher(courseId, teacher)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Course adminCourse(Long courseId) {
        entityManager.clear();
        return courses.findLockedById(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }
}
