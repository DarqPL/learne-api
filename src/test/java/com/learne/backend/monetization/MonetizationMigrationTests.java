package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class MonetizationMigrationTests {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void monetizationColumnsAndTablesExist() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertColumnExists(connection, "users", "account_origin");
            assertColumnExists(connection, "users", "owner_teacher_id");
            assertColumnExists(connection, "courses", "pricing_type");
            assertColumnExists(connection, "courses", "price_amount_vnd");
            assertColumnExists(connection, "courses", "proposed_price_amount_vnd");
            assertColumnExists(connection, "courses", "price_status");
            assertColumnExists(connection, "course_revisions", "pricing_type");
            assertColumnExists(connection, "course_revisions", "proposed_price_amount_vnd");
            assertTableExists(connection, "orders");
            assertColumnExists(connection, "orders", "dedupe_key");
            assertTableExists(connection, "order_items");
            assertTableExists(connection, "course_entitlements");
        }
    }

    @Test
    void planSubscriptionAndAiUsageTablesExist() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertColumnExists(connection, "order_items", "plan_code");
            assertColumnExists(connection, "course_entitlements", "subscription_id");
            assertTableExists(connection, "subscriptions");
            assertTableExists(connection, "vip_course_selections");
            assertTableExists(connection, "ai_usage_events");
            assertIndexExists(connection, "course_entitlements", "idx_course_entitlements_user_source_expires_at");
        }

        String suffix = UUID.randomUUID().toString();
        Long studentId = insertUser("plan-student-" + suffix + "@example.com", "STUDENT");
        Long orderId = insertPlanSubscriptionOrder(studentId);

        jdbcTemplate.update("""
                insert into order_items (order_id, product_type, plan_code, item_name_snapshot,
                    unit_amount_vnd, quantity, total_amount_vnd)
                values (?, 'PLAN', 'VIP', 'VIP plan', 100000, 1, 100000)
                """, orderId);
    }

    @Test
    void duplicateActiveCoursePurchaseEntitlementsAreRejected() {
        String suffix = UUID.randomUUID().toString();
        Long teacherId = insertUser("teacher-" + suffix + "@example.com", "TEACHER");
        Long studentId = insertUser("student-" + suffix + "@example.com", "STUDENT");
        Long courseId = insertCourse(teacherId, "Course " + suffix);

        jdbcTemplate.update("""
                insert into course_entitlements (user_id, course_id, source, starts_at)
                values (?, ?, 'COURSE_PURCHASE', current_timestamp)
                """, studentId, courseId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into course_entitlements (user_id, course_id, source, starts_at)
                values (?, ?, 'COURSE_PURCHASE', current_timestamp)
                """, studentId, courseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateVipSelectionRowsAreRejectedByDatabase() {
        String suffix = UUID.randomUUID().toString();
        Long teacherId = insertUser("vip-teacher-" + suffix + "@example.com", "TEACHER");
        Long studentId = insertUser("vip-student-" + suffix + "@example.com", "STUDENT");
        Long courseId = insertCourse(teacherId, "VIP Course " + suffix);
        Long orderId = insertPlanSubscriptionOrder(studentId);
        Long subscriptionId = insertSubscription(studentId, orderId);

        insertVipSelection(subscriptionId, studentId, courseId);

        assertThatThrownBy(() -> insertVipSelection(subscriptionId, studentId, courseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderItemsRejectMixedProductReferences() {
        String suffix = UUID.randomUUID().toString();
        Long teacherId = insertUser("mixed-teacher-" + suffix + "@example.com", "TEACHER");
        Long studentId = insertUser("mixed-student-" + suffix + "@example.com", "STUDENT");
        Long courseId = insertCourse(teacherId, "Mixed Reference Course " + suffix);
        Long courseOrderId = insertCoursePurchaseOrder(studentId);
        Long planOrderId = insertPlanSubscriptionOrder(studentId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into order_items (order_id, product_type, course_id, plan_code, item_name_snapshot,
                    unit_amount_vnd, quantity, total_amount_vnd)
                values (?, 'COURSE', ?, 'VIP', 'Course item with plan', 100000, 1, 100000)
                """, courseOrderId, courseId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into order_items (order_id, product_type, course_id, plan_code, item_name_snapshot,
                    unit_amount_vnd, quantity, total_amount_vnd)
                values (?, 'PLAN', ?, 'VIP', 'Plan item with course', 100000, 1, 100000)
                """, planOrderId, courseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long insertUser(String email, String role) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into users (email, password_hash, full_name, role)
                    values (?, 'hash', 'Migration Test User', ?)
                    """, new String[] {"id"});
            statement.setString(1, email);
            statement.setString(2, role);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long insertCourse(Long teacherId, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into courses (teacher_id, name, description, type, level, order_index, status)
                    values (?, ?, 'Premium English course', 'COMPREHENSIVE', 'A2', 1, 'APPROVED')
                    """, new String[] {"id"});
            statement.setLong(1, teacherId);
            statement.setString(2, name);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long insertPlanSubscriptionOrder(Long studentId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into orders (user_id, order_type, status, original_amount_vnd,
                        discount_amount_vnd, final_amount_vnd, payment_provider, expires_at)
                    values (?, 'PLAN_SUBSCRIPTION', 'PENDING_PAYMENT', 100000, 0, 100000,
                        'INTERNAL', current_timestamp)
                    """, new String[] {"id"});
            statement.setLong(1, studentId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long insertCoursePurchaseOrder(Long studentId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into orders (user_id, order_type, status, original_amount_vnd,
                        discount_amount_vnd, final_amount_vnd, payment_provider, expires_at)
                    values (?, 'COURSE_PURCHASE', 'PENDING_PAYMENT', 100000, 0, 100000,
                        'INTERNAL', current_timestamp)
                    """, new String[] {"id"});
            statement.setLong(1, studentId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long insertSubscription(Long studentId, Long orderId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into subscriptions (user_id, plan_code, status, starts_at, ends_at, order_id)
                    values (?, 'VIP', 'ACTIVE', current_timestamp, dateadd('DAY', 30, current_timestamp), ?)
                    """, new String[] {"id"});
            statement.setLong(1, studentId);
            statement.setLong(2, orderId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertVipSelection(Long subscriptionId, Long studentId, Long courseId) {
        jdbcTemplate.update("""
                insert into vip_course_selections (subscription_id, user_id, course_id, level)
                values (?, ?, ?, 'A2')
                """, subscriptionId, studentId, courseId);
    }

    private void assertTableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName, null)) {
            assertThat(tables.next()).as("table %s exists", tableName).isTrue();
        }
    }

    private void assertColumnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            assertThat(columns.next()).as("%s.%s exists", tableName, columnName).isTrue();
        }
    }

    private void assertIndexExists(Connection connection, String tableName, String indexName) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            boolean found = false;
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("index %s exists on %s", indexName, tableName).isTrue();
        }
    }
}
