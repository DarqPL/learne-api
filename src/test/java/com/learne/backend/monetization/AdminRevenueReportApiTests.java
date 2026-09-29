package com.learne.backend.monetization;

import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.OrderType;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.student.learning.entity.QuizAttempt;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.enums.QuizAttemptStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
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
class AdminRevenueReportApiTests {
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private CourseEnrollmentRepository courseEnrollmentRepository;
    @Autowired private LessonProgressRepository lessonProgressRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void courseSalesReportAggregatesPaidCourseOrdersByMonthCourseAndTeacher() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "A2 Premium", 250000L);
        saveCourseOrder(student, course, OrderStatus.PAID, Instant.parse("2026-09-15T12:00:00Z"), 250000L, 50000L, 200000L);
        saveCourseOrder(student, course, OrderStatus.PAID, Instant.parse("2026-09-30T23:59:59Z"), 250000L, 0L, 250000L);
        saveCourseOrder(student, course, OrderStatus.PENDING_PAYMENT, Instant.parse("2026-09-20T12:00:00Z"), 250000L, 0L, 250000L);
        saveCourseOrder(student, course, OrderStatus.PAID, Instant.parse("2026-10-01T00:00:00Z"), 250000L, 0L, 250000L);

        mockMvc.perform(get("/api/admin/revenue/course-sales")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].courseId").value(course.getId()))
                .andExpect(jsonPath("$.rows[0].courseName").value("A2 Premium"))
                .andExpect(jsonPath("$.rows[0].teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.rows[0].teacherName").value("TEACHER User"))
                .andExpect(jsonPath("$.rows[0].paidOrderCount").value(2))
                .andExpect(jsonPath("$.rows[0].grossRevenueVnd").value(500000))
                .andExpect(jsonPath("$.rows[0].discountAmountVnd").value(50000))
                .andExpect(jsonPath("$.rows[0].netRevenueVnd").value(450000));
    }

    @Test
    void courseSalesReportAllocatesMultiItemCourseOrderRevenueByItemTotal() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher1 = saveUser("teacher1@example.com", UserRole.TEACHER);
        User teacher2 = saveUser("teacher2@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course1 = saveCourse(teacher1, "A2 Premium", 300000L);
        Course course2 = saveCourse(teacher2, "B1 Premium", 100000L);
        saveMultiCourseOrder(student, course1, course2, Instant.parse("2026-09-15T12:00:00Z"));

        mockMvc.perform(get("/api/admin/revenue/course-sales")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].courseId").value(course1.getId()))
                .andExpect(jsonPath("$.rows[0].paidOrderCount").value(1))
                .andExpect(jsonPath("$.rows[0].grossRevenueVnd").value(300000))
                .andExpect(jsonPath("$.rows[0].discountAmountVnd").value(60000))
                .andExpect(jsonPath("$.rows[0].netRevenueVnd").value(240000))
                .andExpect(jsonPath("$.rows[1].courseId").value(course2.getId()))
                .andExpect(jsonPath("$.rows[1].paidOrderCount").value(1))
                .andExpect(jsonPath("$.rows[1].grossRevenueVnd").value(100000))
                .andExpect(jsonPath("$.rows[1].discountAmountVnd").value(20000))
                .andExpect(jsonPath("$.rows[1].netRevenueVnd").value(80000));
    }

    @Test
    void courseSalesReportReconcilesMultiItemCourseOrderDiscountRemainder() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher1 = saveUser("teacher1@example.com", UserRole.TEACHER);
        User teacher2 = saveUser("teacher2@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course1 = saveCourse(teacher1, "A2 Premium", 100L);
        Course course2 = saveCourse(teacher2, "B1 Premium", 100L);
        saveMultiCourseOrder(student, course1, course2, Instant.parse("2026-09-15T12:00:00Z"), 200L, 1L, 199L);

        MvcResult result = mockMvc.perform(get("/api/admin/revenue/course-sales")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andReturn();

        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString()).get("rows");
        long totalDiscount = 0L;
        long totalNet = 0L;
        for (JsonNode row : rows) {
            totalDiscount += row.get("discountAmountVnd").asLong();
            totalNet += row.get("netRevenueVnd").asLong();
        }
        assertThat(totalDiscount).isEqualTo(1L);
        assertThat(totalNet).isEqualTo(199L);
    }

    @Test
    void courseSalesReportRequiresAdminAuthentication() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);

        mockMvc.perform(get("/api/admin/revenue/course-sales")
                        .param("month", "2026-09"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/revenue/course-sales")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isForbidden());
    }

    @Test
    void planSalesReportAggregatesPaidPlanOrdersByMonthAndPlan() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        savePlanOrder(student, PlanCode.PRO, OrderStatus.PAID, Instant.parse("2026-09-10T00:00:00Z"), 500000L, 100000L, 400000L);
        savePlanOrder(student, PlanCode.PRO, OrderStatus.PAID, Instant.parse("2026-09-20T00:00:00Z"), 500000L, 0L, 500000L);
        savePlanOrder(student, PlanCode.VIP, OrderStatus.PAID, Instant.parse("2026-09-12T00:00:00Z"), 200000L, 0L, 200000L);
        savePlanOrder(student, PlanCode.PRO, OrderStatus.PAID, Instant.parse("2026-08-31T23:59:59Z"), 500000L, 0L, 500000L);

        mockMvc.perform(get("/api/admin/revenue/plan-sales")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].planCode").value("PRO"))
                .andExpect(jsonPath("$.rows[0].paidOrderCount").value(2))
                .andExpect(jsonPath("$.rows[0].grossRevenueVnd").value(1000000))
                .andExpect(jsonPath("$.rows[0].discountAmountVnd").value(100000))
                .andExpect(jsonPath("$.rows[0].netRevenueVnd").value(900000))
                .andExpect(jsonPath("$.rows[1].planCode").value("VIP"))
                .andExpect(jsonPath("$.rows[1].netRevenueVnd").value(200000));
    }

    @Test
    void learningActivityReportAggregatesCompletedLessonsAndSubmittedQuizzesByCourseAndTeacher() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Activity Course", 250000L);
        Lesson lesson1 = saveLesson(course, teacher, "Lesson 1");
        Lesson lesson2 = saveLesson(course, teacher, "Lesson 2");
        Lesson lesson3 = saveLesson(course, teacher, "Lesson 3");
        CourseEnrollment enrollment = saveEnrollment(student, course);
        saveLessonProgress(student, enrollment, lesson1, LessonProgressStatus.COMPLETED, Instant.parse("2026-09-02T00:00:00Z"));
        saveLessonProgress(student, enrollment, lesson2, LessonProgressStatus.COMPLETED, Instant.parse("2026-09-03T00:00:00Z"));
        saveLessonProgress(student, enrollment, lesson3, LessonProgressStatus.IN_PROGRESS, null);
        saveQuizAttempt(student, enrollment, lesson1, Instant.parse("2026-09-04T00:00:00Z"));
        saveQuizAttempt(student, enrollment, lesson2, Instant.parse("2026-10-01T00:00:00Z"));

        mockMvc.perform(get("/api/admin/revenue/learning-activity")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].courseId").value(course.getId()))
                .andExpect(jsonPath("$.rows[0].courseName").value("Activity Course"))
                .andExpect(jsonPath("$.rows[0].teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.rows[0].completedLessons").value(2))
                .andExpect(jsonPath("$.rows[0].submittedQuizzes").value(1))
                .andExpect(jsonPath("$.rows[0].activityScore").value(3));
    }

    @Test
    void teacherShareReportDistributesPlanNetRevenueByLearningActivityScore() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher1 = saveUser("teacher1@example.com", UserRole.TEACHER);
        User teacher2 = saveUser("teacher2@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course1 = saveCourse(teacher1, "Teacher 1 Course", 250000L);
        Course course2 = saveCourse(teacher2, "Teacher 2 Course", 250000L);
        Lesson lesson1 = saveLesson(course1, teacher1, "Teacher 1 Lesson");
        Lesson lesson2 = saveLesson(course2, teacher2, "Teacher 2 Lesson");
        CourseEnrollment enrollment1 = saveEnrollment(student, course1);
        CourseEnrollment enrollment2 = saveEnrollment(student, course2);
        saveLessonProgress(student, enrollment1, lesson1, LessonProgressStatus.COMPLETED, Instant.parse("2026-09-02T00:00:00Z"));
        saveQuizAttempt(student, enrollment1, lesson1, Instant.parse("2026-09-03T00:00:00Z"));
        saveLessonProgress(student, enrollment2, lesson2, LessonProgressStatus.COMPLETED, Instant.parse("2026-09-04T00:00:00Z"));
        saveCourseOrder(student, course1, OrderStatus.PAID, Instant.parse("2026-09-10T00:00:00Z"), 250000L, 0L, 250000L);
        saveCourseOrder(student, course2, OrderStatus.PAID, Instant.parse("2026-09-10T00:00:00Z"), 250000L, 50000L, 200000L);
        savePlanOrder(student, PlanCode.PRO, OrderStatus.PAID, Instant.parse("2026-09-11T00:00:00Z"), 500000L, 50000L, 450000L);

        mockMvc.perform(get("/api/admin/revenue/teacher-share")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.planRevenuePoolVnd").value(450000))
                .andExpect(jsonPath("$.totalActivityScore").value(3))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].teacherId").value(teacher1.getId()))
                .andExpect(jsonPath("$.rows[0].activityScore").value(2))
                .andExpect(jsonPath("$.rows[0].directCourseNetRevenueVnd").value(250000))
                .andExpect(jsonPath("$.rows[0].suggestedPlanShareVnd").value(300000))
                .andExpect(jsonPath("$.rows[0].suggestedTotalShareVnd").value(550000))
                .andExpect(jsonPath("$.rows[1].teacherId").value(teacher2.getId()))
                .andExpect(jsonPath("$.rows[1].activityScore").value(1))
                .andExpect(jsonPath("$.rows[1].directCourseNetRevenueVnd").value(200000))
                .andExpect(jsonPath("$.rows[1].suggestedPlanShareVnd").value(150000))
                .andExpect(jsonPath("$.rows[1].suggestedTotalShareVnd").value(350000));
    }

    @Test
    void teacherShareReportIncludesDirectCourseRevenueWhenTeacherHasNoLearningActivity() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Direct Sale Course", 250000L);
        saveCourseOrder(student, course, OrderStatus.PAID, Instant.parse("2026-09-10T00:00:00Z"), 250000L, 50000L, 200000L);

        mockMvc.perform(get("/api/admin/revenue/teacher-share")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.totalActivityScore").value(0))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.rows[0].activityScore").value(0))
                .andExpect(jsonPath("$.rows[0].directCourseNetRevenueVnd").value(200000))
                .andExpect(jsonPath("$.rows[0].suggestedPlanShareVnd").value(0))
                .andExpect(jsonPath("$.rows[0].suggestedTotalShareVnd").value(200000));
    }

    @Test
    void teacherShareReportAllocatesMultiItemCourseOrderRevenueByItemTotal() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher1 = saveUser("teacher1@example.com", UserRole.TEACHER);
        User teacher2 = saveUser("teacher2@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course1 = saveCourse(teacher1, "A2 Premium", 300000L);
        Course course2 = saveCourse(teacher2, "B1 Premium", 100000L);
        saveMultiCourseOrder(student, course1, course2, Instant.parse("2026-09-15T12:00:00Z"));

        mockMvc.perform(get("/api/admin/revenue/teacher-share")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].teacherId").value(teacher1.getId()))
                .andExpect(jsonPath("$.rows[0].directCourseNetRevenueVnd").value(240000))
                .andExpect(jsonPath("$.rows[0].suggestedTotalShareVnd").value(240000))
                .andExpect(jsonPath("$.rows[1].teacherId").value(teacher2.getId()))
                .andExpect(jsonPath("$.rows[1].directCourseNetRevenueVnd").value(80000))
                .andExpect(jsonPath("$.rows[1].suggestedTotalShareVnd").value(80000));
    }

    @Test
    void teacherShareReportUsesReconciledMultiItemCourseOrderDiscountAllocation() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher1 = saveUser("teacher1@example.com", UserRole.TEACHER);
        User teacher2 = saveUser("teacher2@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course1 = saveCourse(teacher1, "A2 Premium", 100L);
        Course course2 = saveCourse(teacher2, "B1 Premium", 100L);
        saveMultiCourseOrder(student, course1, course2, Instant.parse("2026-09-15T12:00:00Z"), 200L, 1L, 199L);

        MvcResult result = mockMvc.perform(get("/api/admin/revenue/teacher-share")
                        .param("month", "2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andReturn();

        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString()).get("rows");
        long totalDirectCourseNetRevenue = 0L;
        for (JsonNode row : rows) {
            totalDirectCourseNetRevenue += row.get("directCourseNetRevenueVnd").asLong();
        }
        assertThat(totalDirectCourseNetRevenue).isEqualTo(199L);
    }

    @Test
    void reportRejectsInvalidMonthFormat() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);

        mockMvc.perform(get("/api/admin/revenue/course-sales")
                        .param("month", "2026-9")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("month must use YYYY-MM format."));
    }

    private Order saveCourseOrder(User student, Course course, OrderStatus status, Instant paidAt,
                                  long originalAmountVnd, long discountAmountVnd, long finalAmountVnd) {
        Order order = new Order();
        order.setUser(student);
        order.setOrderType(OrderType.COURSE_PURCHASE);
        order.setStatus(status);
        order.setOriginalAmountVnd(originalAmountVnd);
        order.setDiscountAmountVnd(discountAmountVnd);
        order.setFinalAmountVnd(finalAmountVnd);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setPaidAt(paidAt);
        order.setExpiresAt(paidAt.plusSeconds(900));

        OrderItem item = new OrderItem();
        item.setProductType(OrderItemProductType.COURSE);
        item.setCourse(course);
        item.setItemNameSnapshot(course.getName());
        item.setUnitAmountVnd(originalAmountVnd);
        item.setQuantity(1);
        item.setTotalAmountVnd(originalAmountVnd);
        order.addItem(item);

        return orderRepository.save(order);
    }

    private Order savePlanOrder(User student, PlanCode planCode, OrderStatus status, Instant paidAt,
                                long originalAmountVnd, long discountAmountVnd, long finalAmountVnd) {
        Order order = new Order();
        order.setUser(student);
        order.setOrderType(OrderType.PLAN_SUBSCRIPTION);
        order.setStatus(status);
        order.setOriginalAmountVnd(originalAmountVnd);
        order.setDiscountAmountVnd(discountAmountVnd);
        order.setFinalAmountVnd(finalAmountVnd);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setPaidAt(paidAt);
        order.setExpiresAt(paidAt.plusSeconds(900));

        OrderItem item = new OrderItem();
        item.setProductType(OrderItemProductType.PLAN);
        item.setPlanCode(planCode);
        item.setItemNameSnapshot(planCode.name());
        item.setUnitAmountVnd(originalAmountVnd);
        item.setQuantity(1);
        item.setTotalAmountVnd(originalAmountVnd);
        order.addItem(item);

        return orderRepository.save(order);
    }

    private Order saveMultiCourseOrder(User student, Course course1, Course course2, Instant paidAt) {
        return saveMultiCourseOrder(student, course1, course2, paidAt, 400000L, 80000L, 320000L);
    }

    private Order saveMultiCourseOrder(User student, Course course1, Course course2, Instant paidAt,
                                       long originalAmountVnd, long discountAmountVnd, long finalAmountVnd) {
        Order order = new Order();
        order.setUser(student);
        order.setOrderType(OrderType.COURSE_PURCHASE);
        order.setStatus(OrderStatus.PAID);
        order.setOriginalAmountVnd(originalAmountVnd);
        order.setDiscountAmountVnd(discountAmountVnd);
        order.setFinalAmountVnd(finalAmountVnd);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setPaidAt(paidAt);
        order.setExpiresAt(paidAt.plusSeconds(900));

        order.addItem(courseOrderItem(course1, course1.getPriceAmountVnd()));
        order.addItem(courseOrderItem(course2, course2.getPriceAmountVnd()));

        return orderRepository.save(order);
    }

    private OrderItem courseOrderItem(Course course, long totalAmountVnd) {
        OrderItem item = new OrderItem();
        item.setProductType(OrderItemProductType.COURSE);
        item.setCourse(course);
        item.setItemNameSnapshot(course.getName());
        item.setUnitAmountVnd(totalAmountVnd);
        item.setQuantity(1);
        item.setTotalAmountVnd(totalAmountVnd);
        return item;
    }

    private Course saveCourse(User teacher, String name, long priceAmountVnd) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName(name);
        course.setDescription(name + " description");
        course.setType(CourseType.GRAMMAR);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(ContentStatus.APPROVED);
        course.setPublishedAt(Instant.parse("2026-09-01T00:00:00Z"));
        course.setPricingType(CoursePricingType.PAID);
        course.setPriceStatus(CoursePriceStatus.APPROVED);
        course.setPriceAmountVnd(priceAmountVnd);
        return courseRepository.save(course);
    }

    private Lesson saveLesson(Course course, User teacher, String title) {
        Topic topic = new Topic();
        topic.setCourse(course);
        topic.setName(title + " Topic");
        topic.setDescription("Topic description");
        topic.setOrderIndex(1);
        topic = topicRepository.save(topic);

        Lesson lesson = new Lesson();
        lesson.setTopic(topic);
        lesson.setTeacher(teacher);
        lesson.setTitle(title);
        lesson.setContent("Lesson content");
        lesson.setSkillType(SkillType.READING);
        lesson.setOrderIndex(1);
        lesson.setStatus(ContentStatus.APPROVED);
        lesson.setPublishedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return lessonRepository.save(lesson);
    }

    private CourseEnrollment saveEnrollment(User student, Course course) {
        CourseEnrollment enrollment = new CourseEnrollment();
        enrollment.setUser(student);
        enrollment.setCourse(course);
        enrollment.setStatus(EnrollmentStatus.ACTIVE);
        enrollment.setProgressPercent(0);
        enrollment.setEnrolledAt(Instant.parse("2026-09-01T00:00:00Z"));
        return courseEnrollmentRepository.save(enrollment);
    }

    private void saveLessonProgress(User student, CourseEnrollment enrollment, Lesson lesson,
                                    LessonProgressStatus status, Instant completedAt) {
        LessonProgress progress = new LessonProgress();
        progress.setUser(student);
        progress.setCourseEnrollment(enrollment);
        progress.setLesson(lesson);
        progress.setStatus(status);
        progress.setProgressPercent(status == LessonProgressStatus.COMPLETED ? 100 : 50);
        progress.setStartedAt(Instant.parse("2026-09-01T00:00:00Z"));
        progress.setCompletedAt(completedAt);
        lessonProgressRepository.save(progress);
    }

    private void saveQuizAttempt(User student, CourseEnrollment enrollment, Lesson lesson, Instant submittedAt) {
        QuizAttempt attempt = new QuizAttempt();
        attempt.setUser(student);
        attempt.setCourseEnrollment(enrollment);
        attempt.setLesson(lesson);
        attempt.setStatus(QuizAttemptStatus.SUBMITTED);
        attempt.setScorePercent(90);
        attempt.setEarnedXp(10);
        attempt.setPassed(true);
        attempt.setSubmittedAt(submittedAt);
        quizAttemptRepository.save(attempt);
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
