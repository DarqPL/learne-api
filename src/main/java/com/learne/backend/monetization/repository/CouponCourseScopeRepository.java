package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.CouponCourseScope;
import com.learne.backend.monetization.entity.CouponCourseScopeId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponCourseScopeRepository extends JpaRepository<CouponCourseScope, CouponCourseScopeId> {
}
