package com.learne.backend.monetization.service;

import com.learne.backend.monetization.dto.CourseSalesReportResponse;
import com.learne.backend.monetization.dto.CourseSalesReportResponse.CourseSalesRow;
import com.learne.backend.monetization.dto.LearningActivityReportResponse;
import com.learne.backend.monetization.dto.LearningActivityReportResponse.LearningActivityRow;
import com.learne.backend.monetization.dto.PlanSalesReportResponse;
import com.learne.backend.monetization.dto.PlanSalesReportResponse.PlanSalesRow;
import com.learne.backend.monetization.dto.TeacherShareReportResponse;
import com.learne.backend.monetization.dto.TeacherShareReportResponse.TeacherShareRow;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.repository.OrderRepository.PaidCourseOrderItem;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import java.math.BigInteger;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRevenueReportService {
    private final OrderRepository orderRepository;
    private final LessonProgressRepository lessonProgressRepository;
    private final QuizAttemptRepository quizAttemptRepository;

    public AdminRevenueReportService(OrderRepository orderRepository,
                                     LessonProgressRepository lessonProgressRepository,
                                     QuizAttemptRepository quizAttemptRepository) {
        this.orderRepository = orderRepository;
        this.lessonProgressRepository = lessonProgressRepository;
        this.quizAttemptRepository = quizAttemptRepository;
    }

    @Transactional(readOnly = true)
    public CourseSalesReportResponse courseSales(String month) {
        MonthRange range = parseMonth(month);
        return new CourseSalesReportResponse(range.month(), courseSalesRows(range));
    }

    @Transactional(readOnly = true)
    public PlanSalesReportResponse planSales(String month) {
        MonthRange range = parseMonth(month);
        List<PlanSalesRow> rows = orderRepository.aggregatePlanSales(range.start(), range.end()).stream()
                .map(row -> new PlanSalesRow(
                        row.getPlanCode(),
                        row.getPaidOrderCount(),
                        row.getGrossRevenueVnd(),
                        row.getDiscountAmountVnd(),
                        row.getNetRevenueVnd()))
                .toList();
        return new PlanSalesReportResponse(range.month(), rows);
    }

    @Transactional(readOnly = true)
    public LearningActivityReportResponse learningActivity(String month) {
        MonthRange range = parseMonth(month);
        return new LearningActivityReportResponse(range.month(), learningActivityRows(range));
    }

    @Transactional(readOnly = true)
    public TeacherShareReportResponse teacherShare(String month) {
        MonthRange range = parseMonth(month);
        long planRevenuePool = orderRepository.sumPaidPlanNetRevenue(range.start(), range.end());

        Map<Long, TeacherActivity> activityByTeacher = new HashMap<>();
        for (LearningActivityRow row : learningActivityRows(range)) {
            TeacherActivity existing = activityByTeacher.computeIfAbsent(
                    row.teacherId(),
                    ignored -> new TeacherActivity(row.teacherId(), row.teacherName(), 0));
            existing.activityScore += row.activityScore();
        }
        courseSalesRows(range)
                .forEach(row -> {
                    TeacherActivity existing = activityByTeacher.computeIfAbsent(
                            row.teacherId(),
                            ignored -> new TeacherActivity(row.teacherId(), row.teacherName(), 0));
                    existing.directCourseRevenue += row.netRevenueVnd();
                });
        long totalActivityScore = activityByTeacher.values().stream()
                .mapToLong(activity -> activity.activityScore)
                .sum();

        List<TeacherShareRow> rows = activityByTeacher.values().stream()
                .sorted(Comparator.comparing(activity -> activity.teacherId))
                .map(activity -> {
                    long suggestedPlanShare = totalActivityScore == 0
                            ? 0
                            : planRevenuePool * activity.activityScore / totalActivityScore;
                    return new TeacherShareRow(
                            activity.teacherId,
                            activity.teacherName,
                            activity.activityScore,
                            activity.directCourseRevenue,
                            suggestedPlanShare,
                            activity.directCourseRevenue + suggestedPlanShare);
                })
                .toList();
        return new TeacherShareReportResponse(range.month(), planRevenuePool, totalActivityScore, rows);
    }

    private List<LearningActivityRow> learningActivityRows(MonthRange range) {
        Map<ActivityKey, ActivityAccumulator> activityByCourse = new HashMap<>();
        lessonProgressRepository.aggregateCompletedLessons(range.start(), range.end())
                .forEach(row -> activityByCourse.computeIfAbsent(
                        new ActivityKey(row.getCourseId(), row.getCourseName(), row.getTeacherId(), row.getTeacherName()),
                        ActivityAccumulator::new).completedLessons += row.getCompletedLessons());
        quizAttemptRepository.aggregateSubmittedQuizzes(range.start(), range.end())
                .forEach(row -> activityByCourse.computeIfAbsent(
                        new ActivityKey(row.getCourseId(), row.getCourseName(), row.getTeacherId(), row.getTeacherName()),
                        ActivityAccumulator::new).submittedQuizzes += row.getSubmittedQuizzes());

        List<ActivityAccumulator> accumulators = new ArrayList<>(activityByCourse.values());
        accumulators.sort(Comparator.comparing(accumulator -> accumulator.key.courseId()));
        return accumulators.stream()
                .map(accumulator -> new LearningActivityRow(
                        accumulator.key.courseId(),
                        accumulator.key.courseName(),
                        accumulator.key.teacherId(),
                        accumulator.key.teacherName(),
                        accumulator.completedLessons,
                        accumulator.submittedQuizzes,
                        accumulator.completedLessons + accumulator.submittedQuizzes))
                .toList();
    }

    private List<CourseSalesRow> courseSalesRows(MonthRange range) {
        Map<Long, List<PaidCourseOrderItem>> itemsByOrder = new HashMap<>();
        for (PaidCourseOrderItem item : orderRepository.findPaidCourseOrderItems(range.start(), range.end())) {
            itemsByOrder.computeIfAbsent(item.getOrderId(), ignored -> new ArrayList<>()).add(item);
        }

        Map<Long, CourseSalesAccumulator> salesByCourse = new HashMap<>();
        for (List<PaidCourseOrderItem> orderItems : itemsByOrder.values()) {
            orderItems.sort(Comparator.comparing(PaidCourseOrderItem::getCourseId)
                    .thenComparing(PaidCourseOrderItem::getItemId));
            for (AllocatedCourseItem allocatedItem : allocateOrderDiscount(orderItems)) {
                PaidCourseOrderItem item = allocatedItem.item();
                CourseSalesAccumulator accumulator = salesByCourse.computeIfAbsent(
                        item.getCourseId(),
                        ignored -> new CourseSalesAccumulator(
                                item.getCourseId(),
                                item.getCourseName(),
                                item.getTeacherId(),
                                item.getTeacherName()));
                accumulator.orderIds.add(item.getOrderId());
                accumulator.grossRevenue += item.getItemTotalAmountVnd();
                accumulator.discountAmount += allocatedItem.discountAmountVnd();
                accumulator.netRevenue += item.getItemTotalAmountVnd() - allocatedItem.discountAmountVnd();
            }
        }

        return salesByCourse.values().stream()
                .sorted(Comparator.comparing(accumulator -> accumulator.courseId))
                .map(accumulator -> new CourseSalesRow(
                        accumulator.courseId,
                        accumulator.courseName,
                        accumulator.teacherId,
                        accumulator.teacherName,
                        accumulator.orderIds.size(),
                        accumulator.grossRevenue,
                        accumulator.discountAmount,
                        accumulator.netRevenue))
                .toList();
    }

    private List<AllocatedCourseItem> allocateOrderDiscount(List<PaidCourseOrderItem> orderItems) {
        if (orderItems.isEmpty()) {
            return List.of();
        }

        long orderOriginalAmount = orderItems.get(0).getOrderOriginalAmountVnd();
        long orderDiscountAmount = orderItems.get(0).getOrderDiscountAmountVnd();
        List<AllocatedCourseItem> allocatedItems = new ArrayList<>();
        long allocatedDiscountTotal = 0L;
        for (PaidCourseOrderItem item : orderItems) {
            Allocation allocation = discountAllocation(item, orderOriginalAmount, orderDiscountAmount);
            allocatedItems.add(new AllocatedCourseItem(item, allocation.discountAmountVnd(), allocation.remainder()));
            allocatedDiscountTotal += allocation.discountAmountVnd();
        }

        long remainingDiscount = orderDiscountAmount - allocatedDiscountTotal;
        if (remainingDiscount > 0) {
            List<AllocatedCourseItem> byRemainder = new ArrayList<>(allocatedItems);
            byRemainder.sort(Comparator.comparing(AllocatedCourseItem::remainder).reversed()
                    .thenComparing(allocatedItem -> allocatedItem.item().getCourseId())
                    .thenComparing(allocatedItem -> allocatedItem.item().getItemId()));
            int index = 0;
            while (remainingDiscount > 0 && !byRemainder.isEmpty()) {
                AllocatedCourseItem allocatedItem = byRemainder.get(index % byRemainder.size());
                allocatedItem.addDiscount(1L);
                remainingDiscount--;
                index++;
            }
        }

        return allocatedItems;
    }

    private Allocation discountAllocation(PaidCourseOrderItem item, long orderOriginalAmount, long orderDiscountAmount) {
        if (orderOriginalAmount <= 0 || orderDiscountAmount <= 0) {
            return new Allocation(0L, BigInteger.ZERO);
        }
        BigInteger numerator = BigInteger.valueOf(item.getItemTotalAmountVnd())
                .multiply(BigInteger.valueOf(orderDiscountAmount));
        BigInteger denominator = BigInteger.valueOf(orderOriginalAmount);
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return new Allocation(quotientAndRemainder[0].longValue(), quotientAndRemainder[1]);
    }

    private MonthRange parseMonth(String month) {
        if (month == null || !month.matches("\\d{4}-\\d{2}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "month must use YYYY-MM format.");
        }
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "month must use YYYY-MM format.");
        }
        Instant start = yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new MonthRange(month, start, end);
    }

    private record MonthRange(String month, Instant start, Instant end) { }
    private record ActivityKey(Long courseId, String courseName, Long teacherId, String teacherName) { }
    private record Allocation(long discountAmountVnd, BigInteger remainder) { }

    private static class ActivityAccumulator {
        private final ActivityKey key;
        private long completedLessons;
        private long submittedQuizzes;

        private ActivityAccumulator(ActivityKey key) {
            this.key = key;
        }
    }

    private static class TeacherActivity {
        private final Long teacherId;
        private final String teacherName;
        private long activityScore;
        private long directCourseRevenue;

        private TeacherActivity(Long teacherId, String teacherName, long activityScore) {
            this.teacherId = teacherId;
            this.teacherName = teacherName;
            this.activityScore = activityScore;
        }
    }

    private static class CourseSalesAccumulator {
        private final Long courseId;
        private final String courseName;
        private final Long teacherId;
        private final String teacherName;
        private final Set<Long> orderIds = new HashSet<>();
        private long grossRevenue;
        private long discountAmount;
        private long netRevenue;

        private CourseSalesAccumulator(Long courseId, String courseName, Long teacherId, String teacherName) {
            this.courseId = courseId;
            this.courseName = courseName;
            this.teacherId = teacherId;
            this.teacherName = teacherName;
        }
    }

    private static class AllocatedCourseItem {
        private final PaidCourseOrderItem item;
        private long discountAmountVnd;
        private final BigInteger remainder;

        private AllocatedCourseItem(PaidCourseOrderItem item, long discountAmountVnd, BigInteger remainder) {
            this.item = item;
            this.discountAmountVnd = discountAmountVnd;
            this.remainder = remainder;
        }

        private PaidCourseOrderItem item() {
            return item;
        }

        private long discountAmountVnd() {
            return discountAmountVnd;
        }

        private BigInteger remainder() {
            return remainder;
        }

        private void addDiscount(long amountVnd) {
            discountAmountVnd += amountVnd;
        }
    }
}
