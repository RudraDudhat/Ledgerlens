package com.ledgerlens;

import com.ledgerlens.entity.ExceptionOrigin;
import com.ledgerlens.entity.ExceptionRecord;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.entity.IngestBatch;
import com.ledgerlens.entity.IngestSource;
import com.ledgerlens.entity.MerchantOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.jpa.open-in-view=false"
})
class SchemaSmokeTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @Test
    void allTablesExist() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'ingest_batches', 'orders', 'payments', 'refunds', 'disputes',
                    'settlement_batches', 'settlement_lines', 'bank_entries',
                    'matches', 'exceptions', 'audit_log')
                """, Integer.class);
        assertThat(count).isEqualTo(11);
    }

    @Test
    void allMoneyColumnsAreNumeric14_2() {
        Integer offenders = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name IN ('amount', 'fee', 'gst', 'net_amount')
                  AND NOT (data_type = 'numeric' AND numeric_precision = 14 AND numeric_scale = 2)
                """, Integer.class);
        assertThat(offenders).isZero();
    }

    @Test
    void auditLogIsAppendOnly() {
        jdbc.update("INSERT INTO audit_log (action, detail) VALUES ('SMOKE_TEST', 'x')");

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'TAMPERED' WHERE action = 'SMOKE_TEST'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE action = 'SMOKE_TEST'"))
                .hasMessageContaining("append-only");

        Integer survivors = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'SMOKE_TEST' AND detail = 'x'", Integer.class);
        assertThat(survivors).isEqualTo(1);
    }

    @Test
    @Transactional
    void entityRoundTripIncludingJsonbAndMoney() {
        IngestBatch batch = new IngestBatch();
        batch.setId(UUID.randomUUID());
        batch.setSource(IngestSource.CSV);
        batch.setCreatedAt(LocalDateTime.now());
        em.persist(batch);

        MerchantOrder order = new MerchantOrder();
        order.setBatchId(batch.getId());
        order.setOrderId("ORD-SMOKE-1");
        order.setOrderTs(LocalDateTime.now());
        order.setAmount(new BigDecimal("499.50"));
        em.persist(order);

        ExceptionRecord exception = new ExceptionRecord();
        exception.setBatchId(batch.getId());
        exception.setStatus(ExceptionStatus.AMOUNT_MISMATCH);
        exception.setEntityRef("ORD-SMOKE-1");
        exception.setReason("bank credit off by 12 rupees");
        exception.setConfidence(new BigDecimal("0.950"));
        exception.setOrigin(ExceptionOrigin.RULE);
        exception.setSourceRowIds(List.of(1L, 2L, 3L));
        exception.setCreatedAt(LocalDateTime.now());
        em.persist(exception);

        em.flush();
        em.clear();

        MerchantOrder loadedOrder = em.find(MerchantOrder.class, order.getId());
        assertThat(loadedOrder.getAmount()).isEqualByComparingTo("499.50");

        ExceptionRecord loadedException = em.find(ExceptionRecord.class, exception.getId());
        assertThat(loadedException.getSourceRowIds()).containsExactly(1L, 2L, 3L);
        assertThat(loadedException.getConfidence()).isEqualByComparingTo("0.95");
        assertThat(loadedException.getStatus()).isEqualTo(ExceptionStatus.AMOUNT_MISMATCH);
    }
}
