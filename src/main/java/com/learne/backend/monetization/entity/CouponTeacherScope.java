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
@Table(name = "coupon_teacher_scopes")
@IdClass(CouponTeacherScopeId.class)
public class CouponTeacherScope {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Id
    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    public CouponTeacherScope() {
    }

    public CouponTeacherScope(Coupon coupon, Long teacherId) {
        this.coupon = coupon;
        this.teacherId = teacherId;
    }

    public Coupon getCoupon() { return coupon; }
    public void setCoupon(Coupon coupon) { this.coupon = coupon; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
}
