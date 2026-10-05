package com.learne.backend.monetization.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.CouponRequest;
import com.learne.backend.monetization.dto.CouponResponse;
import com.learne.backend.shared.pagination.NumberedPageResponse;
import com.learne.backend.monetization.service.CouponService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupons")
class AdminCouponController {
    private final CouponService couponService;

    AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CouponResponse create(@AuthenticationPrincipal User admin, @RequestBody CouponRequest request) {
        return couponService.create(admin, request);
    }

    @GetMapping
    NumberedPageResponse<CouponResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int normalizedPage = page == null ? 0 : Math.max(0, page);
        int normalizedSize = size == null ? 20 : Math.max(1, Math.min(size, 100));
        return couponService.list(normalizedPage, normalizedSize);
    }

    @GetMapping("/{id}")
    CouponResponse get(@PathVariable Long id) {
        return couponService.get(id);
    }

    @PatchMapping("/{id}")
    CouponResponse update(@PathVariable Long id, @RequestBody JsonNode request) {
        return couponService.update(id, request);
    }

    @PostMapping("/{id}/disable")
    CouponResponse disable(@PathVariable Long id) {
        return couponService.disable(id);
    }
}
