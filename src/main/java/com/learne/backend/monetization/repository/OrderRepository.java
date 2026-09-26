package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
