package com.learne.backend.monetization.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.VipCourseSelectionResponse;
import com.learne.backend.monetization.service.VipSelectionService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/vip/selections")
public class StudentVipSelectionController {
    private final VipSelectionService vipSelectionService;

    StudentVipSelectionController(VipSelectionService vipSelectionService) {
        this.vipSelectionService = vipSelectionService;
    }

    @GetMapping
    List<VipCourseSelectionResponse> list(Authentication authentication) {
        return vipSelectionService.listActiveSelections(currentUser(authentication));
    }

    @PostMapping
    VipCourseSelectionResponse select(@RequestBody VipCourseSelectionRequest request, Authentication authentication) {
        return vipSelectionService.select(currentUser(authentication), request.courseId());
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }

    record VipCourseSelectionRequest(Long courseId) {
    }
}
