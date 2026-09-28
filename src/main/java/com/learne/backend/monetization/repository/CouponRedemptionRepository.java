package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.CouponRedemption;
import com.learne.backend.domain.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {
    long countByCouponId(Long couponId);
    long countByCouponIdAndUser(Long couponId, User user);
    boolean existsByOrderId(Long orderId);
}
