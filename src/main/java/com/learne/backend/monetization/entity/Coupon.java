package com.learne.backend.monetization.entity;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.enums.CouponDiscountType;
import com.learne.backend.monetization.enums.CouponProductScope;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "coupons")
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(name = "code_normalized", nullable = false, unique = true, length = 64)
    private String codeNormalized;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 32)
    private CouponDiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private Long discountValue;

    @Column(name = "min_order_amount_vnd", nullable = false)
    private Long minOrderAmountVnd = 0L;

    @Column(name = "max_discount_amount_vnd")
    private Long maxDiscountAmountVnd;

    @Column(name = "minimum_final_amount_vnd", nullable = false)
    private Long minimumFinalAmountVnd = 0L;

    @Column(name = "usage_limit_total")
    private Integer usageLimitTotal;

    @Column(name = "usage_limit_per_user")
    private Integer usageLimitPerUser;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coupon_product_scopes", joinColumns = @JoinColumn(name = "coupon_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "product_scope", nullable = false, length = 32)
    private Set<CouponProductScope> productScopes = new LinkedHashSet<>();

    @OneToMany(mappedBy = "coupon", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CouponCourseScope> courseScopes = new ArrayList<>();

    @OneToMany(mappedBy = "coupon", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CouponTeacherScope> teacherScopes = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getCodeNormalized() { return codeNormalized; }
    public void setCodeNormalized(String codeNormalized) { this.codeNormalized = codeNormalized; }
    public CouponDiscountType getDiscountType() { return discountType; }
    public void setDiscountType(CouponDiscountType discountType) { this.discountType = discountType; }
    public Long getDiscountValue() { return discountValue; }
    public void setDiscountValue(Long discountValue) { this.discountValue = discountValue; }
    public Long getMinOrderAmountVnd() { return minOrderAmountVnd; }
    public void setMinOrderAmountVnd(Long minOrderAmountVnd) { this.minOrderAmountVnd = minOrderAmountVnd; }
    public Long getMaxDiscountAmountVnd() { return maxDiscountAmountVnd; }
    public void setMaxDiscountAmountVnd(Long maxDiscountAmountVnd) { this.maxDiscountAmountVnd = maxDiscountAmountVnd; }
    public Long getMinimumFinalAmountVnd() { return minimumFinalAmountVnd; }
    public void setMinimumFinalAmountVnd(Long minimumFinalAmountVnd) { this.minimumFinalAmountVnd = minimumFinalAmountVnd; }
    public Integer getUsageLimitTotal() { return usageLimitTotal; }
    public void setUsageLimitTotal(Integer usageLimitTotal) { this.usageLimitTotal = usageLimitTotal; }
    public Integer getUsageLimitPerUser() { return usageLimitPerUser; }
    public void setUsageLimitPerUser(Integer usageLimitPerUser) { this.usageLimitPerUser = usageLimitPerUser; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Set<CouponProductScope> getProductScopes() { return productScopes; }
    public List<CouponCourseScope> getCourseScopes() { return courseScopes; }
    public List<CouponTeacherScope> getTeacherScopes() { return teacherScopes; }
}
