package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.CouponRequest;
import com.learne.backend.monetization.dto.CouponResponse;
import com.learne.backend.monetization.entity.Coupon;
import com.learne.backend.monetization.entity.CouponCourseScope;
import com.learne.backend.monetization.entity.CouponTeacherScope;
import com.learne.backend.monetization.enums.CouponDiscountType;
import com.learne.backend.monetization.enums.CouponProductScope;
import com.learne.backend.monetization.repository.CouponRepository;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CouponService {
    private final CouponRepository couponRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    CouponService(
            CouponRepository couponRepository,
            CourseRepository courseRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.couponRepository = couponRepository;
        this.courseRepository = courseRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CouponResponse create(User admin, CouponRequest request) {
        ValidatedCouponRequest validated = validate(request);
        if (couponRepository.existsByCodeNormalized(validated.codeNormalized())) {
            throw new ApiException(HttpStatus.CONFLICT, "Coupon code already exists.");
        }

        Coupon coupon = new Coupon();
        coupon.setCreatedBy(admin);
        apply(coupon, validated);
        if (validated.active() == null) {
            coupon.setActive(true);
        }
        return toResponse(saveCoupon(coupon));
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> list() {
        return couponRepository.findAllByOrderByIdAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CouponResponse get(Long id) {
        return toResponse(findCoupon(id));
    }

    @Transactional
    public CouponResponse update(Long id, JsonNode request) {
        Coupon coupon = findCoupon(id);
        ValidatedCouponRequest validated = validate(mergePatch(coupon, request));
        couponRepository.findByCodeNormalized(validated.codeNormalized())
                .filter(existing -> !existing.getId().equals(coupon.getId()))
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT, "Coupon code already exists.");
                });

        apply(coupon, validated);
        return toResponse(saveCoupon(coupon));
    }

    @Transactional
    public CouponResponse disable(Long id) {
        Coupon coupon = findCoupon(id);
        coupon.setActive(false);
        return toResponse(coupon);
    }

    private Coupon findCoupon(Long id) {
        return couponRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Coupon not found."));
    }

    private void apply(Coupon coupon, ValidatedCouponRequest request) {
        coupon.setCode(request.code());
        coupon.setCodeNormalized(request.codeNormalized());
        coupon.setDiscountType(request.discountType());
        coupon.setDiscountValue(request.discountValue());
        coupon.setMinOrderAmountVnd(request.minOrderAmountVnd());
        coupon.setMaxDiscountAmountVnd(request.maxDiscountAmountVnd());
        coupon.setMinimumFinalAmountVnd(request.minimumFinalAmountVnd());
        coupon.setUsageLimitTotal(request.usageLimitTotal());
        coupon.setUsageLimitPerUser(request.usageLimitPerUser());
        coupon.setValidFrom(request.validFrom());
        coupon.setValidUntil(request.validUntil());
        if (request.active() != null) {
            coupon.setActive(request.active());
        }

        coupon.getProductScopes().clear();
        coupon.getProductScopes().addAll(request.productScopes());

        replaceCourseScopes(coupon, request.courseIds());
        replaceTeacherScopes(coupon, request.teacherIds());
    }

    private Coupon saveCoupon(Coupon coupon) {
        try {
            return couponRepository.saveAndFlush(coupon);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Coupon code already exists.");
        }
    }

    private CouponRequest mergePatch(Coupon coupon, JsonNode patch) {
        if (patch == null || patch.isNull() || !patch.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid request.");
        }
        CouponRequest request;
        try {
            request = objectMapper.treeToValue(patch, CouponRequest.class);
        } catch (JacksonException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid request.");
        }
        return new CouponRequest(
                patch.has("code") ? request.code() : coupon.getCode(),
                patch.has("discountType") ? request.discountType() : coupon.getDiscountType(),
                patch.has("discountValue") ? request.discountValue() : coupon.getDiscountValue(),
                patch.has("minOrderAmountVnd") ? request.minOrderAmountVnd() : coupon.getMinOrderAmountVnd(),
                patch.has("maxDiscountAmountVnd") ? request.maxDiscountAmountVnd() : coupon.getMaxDiscountAmountVnd(),
                patch.has("minimumFinalAmountVnd") ? request.minimumFinalAmountVnd() : coupon.getMinimumFinalAmountVnd(),
                patch.has("usageLimitTotal") ? request.usageLimitTotal() : coupon.getUsageLimitTotal(),
                patch.has("usageLimitPerUser") ? request.usageLimitPerUser() : coupon.getUsageLimitPerUser(),
                patch.has("validFrom") ? request.validFrom() : coupon.getValidFrom(),
                patch.has("validUntil") ? request.validUntil() : coupon.getValidUntil(),
                patch.has("active") ? request.active() : coupon.isActive(),
                patch.has("productScopes") ? request.productScopes() : List.copyOf(coupon.getProductScopes()),
                patch.has("courseIds")
                        ? request.courseIds()
                        : coupon.getCourseScopes().stream().map(CouponCourseScope::getCourseId).toList(),
                patch.has("teacherIds")
                        ? request.teacherIds()
                        : coupon.getTeacherScopes().stream().map(CouponTeacherScope::getTeacherId).toList());
    }

    private void replaceCourseScopes(Coupon coupon, List<Long> courseIds) {
        Set<Long> requestedIds = new LinkedHashSet<>(courseIds);
        coupon.getCourseScopes().removeIf(scope -> !requestedIds.contains(scope.getCourseId()));
        Set<Long> existingIds = coupon.getCourseScopes().stream()
                .map(CouponCourseScope::getCourseId)
                .collect(java.util.stream.Collectors.toSet());
        requestedIds.stream()
                .filter(courseId -> !existingIds.contains(courseId))
                .forEach(courseId -> coupon.getCourseScopes().add(new CouponCourseScope(coupon, courseId)));
    }

    private void replaceTeacherScopes(Coupon coupon, List<Long> teacherIds) {
        Set<Long> requestedIds = new LinkedHashSet<>(teacherIds);
        coupon.getTeacherScopes().removeIf(scope -> !requestedIds.contains(scope.getTeacherId()));
        Set<Long> existingIds = coupon.getTeacherScopes().stream()
                .map(CouponTeacherScope::getTeacherId)
                .collect(java.util.stream.Collectors.toSet());
        requestedIds.stream()
                .filter(teacherId -> !existingIds.contains(teacherId))
                .forEach(teacherId -> coupon.getTeacherScopes().add(new CouponTeacherScope(coupon, teacherId)));
    }

    private ValidatedCouponRequest validate(CouponRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid request.");
        }
        String code = request.code() == null ? null : request.code().trim();
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "code is required.");
        }
        if (code.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "code must be at most 64 characters.");
        }
        CouponDiscountType discountType = request.discountType();
        if (discountType == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "discountType is required.");
        }
        Long discountValue = request.discountValue();
        if (discountValue == null || discountValue <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "discountValue must be positive.");
        }
        if (discountType == CouponDiscountType.PERCENT && discountValue > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Percent discountValue must be at most 100.");
        }
        Instant validFrom = request.validFrom();
        Instant validUntil = request.validUntil();
        if (validFrom == null || validUntil == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validFrom and validUntil are required.");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validUntil must be after validFrom.");
        }

        Long minOrderAmountVnd = request.minOrderAmountVnd() == null ? 0L : request.minOrderAmountVnd();
        Long minimumFinalAmountVnd = request.minimumFinalAmountVnd() == null ? 0L : request.minimumFinalAmountVnd();
        requireNonNegative(minOrderAmountVnd, "minOrderAmountVnd");
        requireNonNegative(request.maxDiscountAmountVnd(), "maxDiscountAmountVnd");
        requireNonNegative(minimumFinalAmountVnd, "minimumFinalAmountVnd");
        requirePositive(request.usageLimitTotal(), "usageLimitTotal");
        requirePositive(request.usageLimitPerUser(), "usageLimitPerUser");

        List<CouponProductScope> productScopes = request.productScopes() == null ? List.of() : request.productScopes();
        if (productScopes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "productScopes must not be empty.");
        }
        Set<CouponProductScope> distinctProductScopes = new LinkedHashSet<>(productScopes);
        if (distinctProductScopes.contains(null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "productScopes must not contain null.");
        }

        return new ValidatedCouponRequest(
                code,
                code.toUpperCase(Locale.ROOT),
                discountType,
                discountValue,
                minOrderAmountVnd,
                request.maxDiscountAmountVnd(),
                minimumFinalAmountVnd,
                request.usageLimitTotal(),
                request.usageLimitPerUser(),
                validFrom,
                validUntil,
                request.active(),
                new ArrayList<>(distinctProductScopes),
                validateCourseIds(distinctIds(request.courseIds())),
                validateTeacherIds(distinctIds(request.teacherIds())));
    }

    private void requireNonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " must be non-negative.");
        }
    }

    private void requirePositive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " must be positive.");
        }
    }

    private List<Long> distinctIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>(ids);
        if (distinctIds.contains(null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Scope ids must not contain null.");
        }
        return new ArrayList<>(distinctIds);
    }

    private List<Long> validateCourseIds(List<Long> courseIds) {
        if (!courseIds.isEmpty() && courseRepository.findAllById(courseIds).size() != courseIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course scope is invalid.");
        }
        return courseIds;
    }

    private List<Long> validateTeacherIds(List<Long> teacherIds) {
        if (!teacherIds.isEmpty()) {
            long validTeachers = userRepository.findAllById(teacherIds).stream()
                    .filter(user -> user.getRole() == UserRole.TEACHER)
                    .count();
            if (validTeachers != teacherIds.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Teacher scope is invalid.");
            }
        }
        return teacherIds;
    }

    private CouponResponse toResponse(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinOrderAmountVnd(),
                coupon.getMaxDiscountAmountVnd(),
                coupon.getMinimumFinalAmountVnd(),
                coupon.getUsageLimitTotal(),
                coupon.getUsageLimitPerUser(),
                coupon.getValidFrom(),
                coupon.getValidUntil(),
                coupon.isActive(),
                List.copyOf(coupon.getProductScopes()),
                coupon.getCourseScopes().stream().map(CouponCourseScope::getCourseId).toList(),
                coupon.getTeacherScopes().stream().map(CouponTeacherScope::getTeacherId).toList());
    }

    private record ValidatedCouponRequest(
            String code,
            String codeNormalized,
            CouponDiscountType discountType,
            Long discountValue,
            Long minOrderAmountVnd,
            Long maxDiscountAmountVnd,
            Long minimumFinalAmountVnd,
            Integer usageLimitTotal,
            Integer usageLimitPerUser,
            Instant validFrom,
            Instant validUntil,
            Boolean active,
            List<CouponProductScope> productScopes,
            List<Long> courseIds,
            List<Long> teacherIds) {
    }
}
