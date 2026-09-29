package com.learne.backend.monetization.entity;

import java.io.Serializable;
import java.util.Objects;

public class CouponCourseScopeId implements Serializable {
    private Long coupon;
    private Long courseId;

    public CouponCourseScopeId() {
    }

    public CouponCourseScopeId(Long coupon, Long courseId) {
        this.coupon = coupon;
        this.courseId = courseId;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof CouponCourseScopeId that)) {
            return false;
        }
        return Objects.equals(coupon, that.coupon) && Objects.equals(courseId, that.courseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coupon, courseId);
    }
}
