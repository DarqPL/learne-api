package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.enums.PlanCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
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

    @Query("""
            select order.id as orderId,
                   order.originalAmountVnd as orderOriginalAmountVnd,
                   order.discountAmountVnd as orderDiscountAmountVnd,
                   item.id as itemId,
                   item.totalAmountVnd as itemTotalAmountVnd,
                   item.course.id as courseId,
                   item.course.name as courseName,
                   item.course.teacher.id as teacherId,
                   item.course.teacher.fullName as teacherName
            from Order order
            join order.items item
            where order.status = com.learne.backend.monetization.enums.OrderStatus.PAID
              and order.orderType = com.learne.backend.monetization.enums.OrderType.COURSE_PURCHASE
              and order.paidAt >= :start
              and order.paidAt < :end
            order by order.id asc, item.course.id asc, item.id asc
            """)
    List<PaidCourseOrderItem> findPaidCourseOrderItems(@Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select item.planCode as planCode,
                   count(order) as paidOrderCount,
                   coalesce(sum(order.originalAmountVnd), 0) as grossRevenueVnd,
                   coalesce(sum(order.discountAmountVnd), 0) as discountAmountVnd,
                   coalesce(sum(order.finalAmountVnd), 0) as netRevenueVnd
            from Order order
            join order.items item
            where order.status = com.learne.backend.monetization.enums.OrderStatus.PAID
              and order.orderType = com.learne.backend.monetization.enums.OrderType.PLAN_SUBSCRIPTION
              and order.paidAt >= :start
              and order.paidAt < :end
            group by item.planCode
            order by item.planCode asc
            """)
    List<PlanSalesAggregate> aggregatePlanSales(@Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select coalesce(sum(order.finalAmountVnd), 0)
            from Order order
            where order.status = com.learne.backend.monetization.enums.OrderStatus.PAID
              and order.orderType = com.learne.backend.monetization.enums.OrderType.PLAN_SUBSCRIPTION
              and order.paidAt >= :start
              and order.paidAt < :end
            """)
    long sumPaidPlanNetRevenue(@Param("start") Instant start, @Param("end") Instant end);

    interface PaidCourseOrderItem {
        Long getOrderId();
        long getOrderOriginalAmountVnd();
        long getOrderDiscountAmountVnd();
        Long getItemId();
        long getItemTotalAmountVnd();
        Long getCourseId();
        String getCourseName();
        Long getTeacherId();
        String getTeacherName();
    }

    interface PlanSalesAggregate {
        PlanCode getPlanCode();
        long getPaidOrderCount();
        long getGrossRevenueVnd();
        long getDiscountAmountVnd();
        long getNetRevenueVnd();
    }
}
