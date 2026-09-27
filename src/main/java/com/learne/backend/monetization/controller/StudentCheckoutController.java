package com.learne.backend.monetization.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.service.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/checkout")
class StudentCheckoutController {
    private final CheckoutService checkoutService;

    StudentCheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/course/{courseId}")
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse checkoutCourse(@PathVariable Long courseId, Authentication authentication) {
        return checkoutService.createCourseOrder((User) authentication.getPrincipal(), courseId);
    }

    @PostMapping("/plans/{planCode}")
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse checkoutPlan(@PathVariable PlanCode planCode, Authentication authentication) {
        return checkoutService.createPlanOrder((User) authentication.getPrincipal(), planCode);
    }
}
