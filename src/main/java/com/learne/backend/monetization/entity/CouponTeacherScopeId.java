package com.learne.backend.monetization.entity;

import java.io.Serializable;
import java.util.Objects;

public class CouponTeacherScopeId implements Serializable {
    private Long coupon;
    private Long teacherId;

    public CouponTeacherScopeId() {
    }

    public CouponTeacherScopeId(Long coupon, Long teacherId) {
        this.coupon = coupon;
        this.teacherId = teacherId;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof CouponTeacherScopeId that)) {
            return false;
        }
        return Objects.equals(coupon, that.coupon) && Objects.equals(teacherId, that.teacherId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coupon, teacherId);
    }
}
