package com.learne.backend.monetization.entity;

import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.teacher.content.entity.Course;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    private OrderItemProductType productType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", length = 32)
    private PlanCode planCode;

    @Column(name = "item_name_snapshot", nullable = false)
    private String itemNameSnapshot;

    @Column(name = "unit_amount_vnd", nullable = false)
    private Long unitAmountVnd;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "total_amount_vnd", nullable = false)
    private Long totalAmountVnd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public OrderItemProductType getProductType() { return productType; }
    public void setProductType(OrderItemProductType productType) { this.productType = productType; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public PlanCode getPlanCode() { return planCode; }
    public void setPlanCode(PlanCode planCode) { this.planCode = planCode; }
    public String getItemNameSnapshot() { return itemNameSnapshot; }
    public void setItemNameSnapshot(String itemNameSnapshot) { this.itemNameSnapshot = itemNameSnapshot; }
    public Long getUnitAmountVnd() { return unitAmountVnd; }
    public void setUnitAmountVnd(Long unitAmountVnd) { this.unitAmountVnd = unitAmountVnd; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Long getTotalAmountVnd() { return totalAmountVnd; }
    public void setTotalAmountVnd(Long totalAmountVnd) { this.totalAmountVnd = totalAmountVnd; }
    public Instant getCreatedAt() { return createdAt; }
}
