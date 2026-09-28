package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.monetization.entity.SepayWebhookEvent;
import com.learne.backend.monetization.enums.SepayWebhookEventStatus;
import com.learne.backend.monetization.repository.SepayWebhookEventRepository;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class SepayMigrationTests {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private SepayWebhookEventRepository sepayWebhookEventRepository;

    @Test
    void sepayWebhookEventsTableAndIndexesExist() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTableExists(connection, "sepay_webhook_events");
            assertColumnExists(connection, "sepay_webhook_events", "sepay_transaction_id");
            assertColumnExists(connection, "sepay_webhook_events", "payment_reference");
            assertColumnExists(connection, "sepay_webhook_events", "raw_payload");
            assertIndexExists(connection, "sepay_webhook_events", "idx_sepay_webhook_events_payment_reference");
            assertIndexExists(connection, "sepay_webhook_events", "idx_sepay_webhook_events_order_id");
            assertIndexExists(connection, "sepay_webhook_events", "idx_sepay_webhook_events_status_received_at");
        }
    }

    @Test
    void sepayWebhookEventsRejectDuplicateTransactionIds() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("""
                    insert into sepay_webhook_events (
                        sepay_transaction_id, transfer_type, transfer_amount_vnd,
                        status, raw_payload, received_at
                    ) values (12345, 'in', 100000, 'IGNORED', '{}', current_timestamp)
                    """);

            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    insert into sepay_webhook_events (
                        sepay_transaction_id, transfer_type, transfer_amount_vnd,
                        status, raw_payload, received_at
                    ) values (12345, 'in', 100000, 'IGNORED', '{}', current_timestamp)
                    """))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void sepayWebhookEventsRejectInvalidStatusAndNegativeAmount() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    insert into sepay_webhook_events (
                        sepay_transaction_id, transfer_type, transfer_amount_vnd,
                        status, raw_payload, received_at
                    ) values (999, 'in', -1, 'FAILED', '{}', current_timestamp)
                    """))
                    .isInstanceOf(Exception.class);

            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    insert into sepay_webhook_events (
                        sepay_transaction_id, transfer_type, transfer_amount_vnd,
                        status, raw_payload, received_at
                    ) values (1000, 'in', 100000, 'BAD', '{}', current_timestamp)
                    """))
                    .isInstanceOf(Exception.class);

            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    insert into sepay_webhook_events (
                        sepay_transaction_id, transfer_type, transfer_amount_vnd,
                        status, raw_payload, received_at
                    ) values (1001, 'in', 100000, 'RECEIVED', '{}', current_timestamp)
                    """))
                    .isInstanceOf(Exception.class);

            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    insert into sepay_webhook_events (
                        sepay_transaction_id, transfer_type, transfer_amount_vnd,
                        status, raw_payload, received_at
                    ) values (1002, 'in', 100000, 'UNMATCHED', '{}', current_timestamp)
                    """))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void sepayWebhookEventDefaultsReceivedAtWhenPersisted() {
        SepayWebhookEvent event = new SepayWebhookEvent();
        event.setSepayTransactionId(987654321L);
        event.setTransferType("in");
        event.setTransferAmountVnd(100000L);
        event.setStatus(SepayWebhookEventStatus.IGNORED);
        event.setRawPayload("{}");

        SepayWebhookEvent saved = sepayWebhookEventRepository.saveAndFlush(event);

        assertThat(saved.getReceivedAt()).isNotNull();
    }

    private void assertTableExists(Connection connection, String tableName) throws Exception {
        try (var result = connection.getMetaData().getTables(null, null, tableName.toUpperCase(), null)) {
            if (result.next()) return;
        }
        try (var result = connection.getMetaData().getTables(null, null, tableName, null)) {
            assertThat(result.next()).as("table %s exists", tableName).isTrue();
        }
    }

    private void assertColumnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (var result = connection.getMetaData().getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            if (result.next()) return;
        }
        try (var result = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            assertThat(result.next()).as("column %s.%s exists", tableName, columnName).isTrue();
        }
    }

    private void assertIndexExists(Connection connection, String tableName, String indexName) throws Exception {
        String[] schemas = {null, connection.getSchema()};
        String[] tableNames = {tableName.toUpperCase(), tableName};

        for (String schema : schemas) {
            for (String candidateTableName : tableNames) {
                try (var result = connection.getMetaData().getIndexInfo(
                        connection.getCatalog(), schema, candidateTableName, false, false)) {
                    while (result.next()) {
                        String candidateIndexName = result.getString("INDEX_NAME");
                        if (indexName.equalsIgnoreCase(candidateIndexName)) {
                            return;
                        }
                    }
                }
            }
        }

        assertThat(false).as("index %s exists", indexName).isTrue();
    }
}
