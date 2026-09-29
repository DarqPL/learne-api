package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
