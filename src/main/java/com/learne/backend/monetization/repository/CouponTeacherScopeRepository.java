package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.CouponTeacherScope;
import com.learne.backend.monetization.entity.CouponTeacherScopeId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponTeacherScopeRepository extends JpaRepository<CouponTeacherScope, CouponTeacherScopeId> {
}
