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
}
