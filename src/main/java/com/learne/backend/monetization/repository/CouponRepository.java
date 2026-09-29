package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.Coupon;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    boolean existsByCodeNormalized(String codeNormalized);
    Optional<Coupon> findByCodeNormalized(String codeNormalized);
    List<Coupon> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select coupon from Coupon coupon where coupon.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
}
