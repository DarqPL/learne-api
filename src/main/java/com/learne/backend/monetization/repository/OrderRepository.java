package com.learne.backend.monetization.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.teacher.content.entity.Course;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("""
            select count(order) > 0
            from Order order
            join order.items item
            where order.user = :user
              and order.status = :status
              and order.expiresAt > :now
              and item.course = :course
            """)
    boolean existsPendingUnexpiredCourseOrder(@Param("user") User user,
                                               @Param("course") Course course,
                                               @Param("status") OrderStatus status,
                                               @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select order from Order order where order.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select order
            from Order order
            where order.dedupeKey = :dedupeKey
            """)
    List<Order> findByDedupeKeyForUpdate(@Param("dedupeKey") String dedupeKey);
}
