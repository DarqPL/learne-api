package com.learne.backend.monetization;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class CouponApiTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.learne.backend.monetization.service.PaymentConfirmationMailService paymentMail;
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void adminCanCreateListDetailPatchAndDisableCoupon() throws Exception {
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());

        MvcResult createResult = mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(welcome20Request()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("WELCOME20"))
                .andExpect(jsonPath("$.discountType").value("PERCENT"))
                .andExpect(jsonPath("$.discountValue").value(20))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.productScopes", containsInAnyOrder("COURSE_PURCHASE", "VIP")))
                .andReturn();
        long couponId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(couponId));

        mockMvc.perform(get("/api/admin/coupons/%d".formatted(couponId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(couponId))
                .andExpect(jsonPath("$.maxDiscountAmountVnd").value(50000));

        mockMvc.perform(patch("/api/admin/coupons/%d".formatted(couponId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixedAmountRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("VIP50K"))
                .andExpect(jsonPath("$.discountType").value("FIXED_AMOUNT"))
                .andExpect(jsonPath("$.discountValue").value(50000))
                .andExpect(jsonPath("$.productScopes", containsInAnyOrder("VIP")));

        mockMvc.perform(post("/api/admin/coupons/%d/disable".formatted(couponId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void couponCodeIsCaseInsensitiveUnique() throws Exception {
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());
        createCoupon(adminToken, welcome20Request());

        mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(welcome20Request().replace("WELCOME20", "welcome20")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Coupon code already exists."));
    }

    @Test
    void nonAdminCannotManageCoupons() throws Exception {
        String studentToken = loginAndGetAccessToken(saveUser("student@example.com", UserRole.STUDENT).getEmail());
        String teacherToken = loginAndGetAccessToken(saveUser("teacher@example.com", UserRole.TEACHER).getEmail());

        mockMvc.perform(get("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(welcome20Request()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPercentDiscountIsRejected() throws Exception {
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());

        mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(welcome20Request().replace("\"discountValue\": 20", "\"discountValue\": 101")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Percent discountValue must be at most 100."));
    }

    @Test
    void invalidCourseScopeIsRejectedWithoutServerError() throws Exception {
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());

        mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopedRequest("COURSE_ONLY", "999999", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Course scope is invalid."));
    }

    @Test
    void invalidTeacherScopeIsRejectedWithoutServerError() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());

        mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopedRequest("TEACHER_ONLY", "", String.valueOf(student.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Teacher scope is invalid."));
    }

    @Test
    void updateCanReplaceNonEmptyScopesWhileKeepingExistingScope() throws Exception {
        User teacher1 = saveUser("teacher1@example.com", UserRole.TEACHER);
        User teacher2 = saveUser("teacher2@example.com", UserRole.TEACHER);
        User teacher3 = saveUser("teacher3@example.com", UserRole.TEACHER);
        Course course1 = saveCourse(teacher1, "Course 1");
        Course course2 = saveCourse(teacher2, "Course 2");
        Course course3 = saveCourse(teacher3, "Course 3");
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());
        long couponId = createCoupon(adminToken, scopedRequest(
                "SCOPED", ids(course1.getId(), course2.getId()), ids(teacher1.getId(), teacher2.getId())));

        mockMvc.perform(patch("/api/admin/coupons/%d".formatted(couponId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopedRequest(
                                "SCOPED2", ids(course2.getId(), course3.getId()), ids(teacher2.getId(), teacher3.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseIds", containsInAnyOrder(
                        course2.getId().intValue(), course3.getId().intValue())))
                .andExpect(jsonPath("$.teacherIds", containsInAnyOrder(
                        teacher2.getId().intValue(), teacher3.getId().intValue())));
    }

    @Test
    void patchWithOnlyActivePreservesExistingRequiredFields() throws Exception {
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());
        long couponId = createCoupon(adminToken, welcome20Request());

        mockMvc.perform(patch("/api/admin/coupons/%d".formatted(couponId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "active": false }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WELCOME20"))
                .andExpect(jsonPath("$.discountType").value("PERCENT"))
                .andExpect(jsonPath("$.discountValue").value(20))
                .andExpect(jsonPath("$.maxDiscountAmountVnd").value(50000))
                .andExpect(jsonPath("$.usageLimitTotal").value(100))
                .andExpect(jsonPath("$.usageLimitPerUser").value(1))
                .andExpect(jsonPath("$.productScopes", containsInAnyOrder("COURSE_PURCHASE", "VIP")))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void patchWithExplicitNullClearsNullableLimitFields() throws Exception {
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());
        long couponId = createCoupon(adminToken, welcome20Request());

        mockMvc.perform(patch("/api/admin/coupons/%d".formatted(couponId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxDiscountAmountVnd": null,
                                  "usageLimitTotal": null,
                                  "usageLimitPerUser": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxDiscountAmountVnd", nullValue()))
                .andExpect(jsonPath("$.usageLimitTotal", nullValue()))
                .andExpect(jsonPath("$.usageLimitPerUser", nullValue()))
                .andExpect(jsonPath("$.code").value("WELCOME20"))
                .andExpect(jsonPath("$.discountType").value("PERCENT"))
                .andExpect(jsonPath("$.discountValue").value(20));
    }

    @Test
    void updateToExistingCouponCodeIsConflict() throws Exception {
        String adminToken = loginAndGetAccessToken(saveUser("admin@example.com", UserRole.ADMIN).getEmail());
        createCoupon(adminToken, welcome20Request());
        long couponId = createCoupon(adminToken, fixedAmountRequest());

        mockMvc.perform(patch("/api/admin/coupons/%d".formatted(couponId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(welcome20Request().replace("WELCOME20", "welcome20")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Coupon code already exists."));
    }

    private long createCoupon(String adminToken, String requestBody) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String welcome20Request() {
        Instant now = Instant.now();
        return """
                {
                  "code": "WELCOME20",
                  "discountType": "PERCENT",
                  "discountValue": 20,
                  "minOrderAmountVnd": 100000,
                  "maxDiscountAmountVnd": 50000,
                  "minimumFinalAmountVnd": 10000,
                  "usageLimitTotal": 100,
                  "usageLimitPerUser": 1,
                  "validFrom": "%s",
                  "validUntil": "%s",
                  "productScopes": ["COURSE_PURCHASE", "VIP"],
                  "courseIds": [],
                  "teacherIds": []
                }
                """.formatted(now.minusSeconds(3_600), now.plusSeconds(86_400));
    }

    private String fixedAmountRequest() {
        Instant now = Instant.now();
        return """
                {
                  "code": "VIP50K",
                  "discountType": "FIXED_AMOUNT",
                  "discountValue": 50000,
                  "minOrderAmountVnd": 0,
                  "maxDiscountAmountVnd": null,
                  "minimumFinalAmountVnd": 10000,
                  "usageLimitTotal": 50,
                  "usageLimitPerUser": 1,
                  "validFrom": "%s",
                  "validUntil": "%s",
                  "productScopes": ["VIP"],
                  "courseIds": [],
                  "teacherIds": []
                }
                """.formatted(now.minusSeconds(3_600), now.plusSeconds(86_400));
    }

    private String scopedRequest(String code, String courseIds, String teacherIds) {
        Instant now = Instant.now();
        return """
                {
                  "code": "%s",
                  "discountType": "PERCENT",
                  "discountValue": 20,
                  "minOrderAmountVnd": 100000,
                  "maxDiscountAmountVnd": 50000,
                  "minimumFinalAmountVnd": 10000,
                  "usageLimitTotal": 100,
                  "usageLimitPerUser": 1,
                  "validFrom": "%s",
                  "validUntil": "%s",
                  "productScopes": ["COURSE_PURCHASE"],
                  "courseIds": [%s],
                  "teacherIds": [%s]
                }
                """.formatted(code, now.minusSeconds(3_600), now.plusSeconds(86_400), courseIds, teacherIds);
    }

    private String ids(Long first, Long second) {
        return "%d, %d".formatted(first, second);
    }

    private Course saveCourse(User teacher, String name) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName(name);
        course.setDescription(name + " description");
        course.setType(CourseType.GRAMMAR);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(ContentStatus.APPROVED);
        course.setPublishedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return courseRepository.save(course);
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setActive(true);
        return userRepository.save(user);
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Password123!"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
