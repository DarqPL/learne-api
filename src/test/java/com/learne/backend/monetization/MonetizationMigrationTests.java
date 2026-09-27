package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class MonetizationMigrationTests {
    @Autowired
    private DataSource dataSource;

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
