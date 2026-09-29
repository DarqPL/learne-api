package com.learne.backend.monetization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "coupon_course_scopes")
@IdClass(CouponCourseScopeId.class)
public class CouponCourseScope {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Id
    @Column(name = "course_id", nullable = false)
    private Long courseId;

    public CouponCourseScope() {
    }

    public CouponCourseScope(Coupon coupon, Long courseId) {
        this.coupon = coupon;
        this.courseId = courseId;
    }

    public Coupon getCoupon() { return coupon; }
    public void setCoupon(Coupon coupon) { this.coupon = coupon; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
}
