package com.nexus.reporting;

import com.nexus.reporting.data.DailyReportData;
import com.nexus.reporting.model.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Tag("unit")
class ReportingHandlerTest {

    @Test
    @DisplayName("DailyReportData.empty returns zero counts")
    void emptyReportData() {
        var data = DailyReportData.empty(LocalDate.of(2025, 5, 7));
        assertThat(data.transactionCount()).isZero();
        assertThat(data.fraudAlertCount()).isZero();
        assertThat(data.activeUserCount()).isZero();
        assertThat(data.totalTransactionVolume()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("DailyReportData.successRate handles empty transactions")
    void successRate_empty() {
        var data = DailyReportData.empty(LocalDate.of(2025, 5, 7));
        assertThat(data.successRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("DailyReportData.successRate calculates correctly")
    void successRate_withData() {
        var txns = List.of(
            txn("COMPLETED"), txn("COMPLETED"), txn("COMPLETED"), txn("FAILED"));
        var data = new DailyReportData(LocalDate.of(2025, 5, 7),
            txns, List.of(), Map.of(), Map.of(), List.of(),
            PlatformMetrics.empty(), 4, 0, 1,
            BigDecimal.valueOf(1000), BigDecimal.ZERO);
        assertThat(data.successRate()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("ReportResult.failed sets status")
    void reportResult_failed() {
        var r = ReportResult.failed("COMPLIANCE", "DynamoDB error");
        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.errorMessage()).contains("DynamoDB");
        assertThat(r.generatedFiles()).isEmpty();
    }

    @Test
    @DisplayName("ReportResult.skipped sets status")
    void reportResult_skipped() {
        var r = ReportResult.skipped("BI", "Timeout approaching");
        assertThat(r.status()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("TransactionRecord.isDebit for PAYMENT")
    void transactionRecord_isDebit() {
        var tx = new TransactionRecord("t1", "u1", "a1", "a2",
            BigDecimal.valueOf(500), "MXN", "PAYMENT", "COMPLETED",
            "VISA", null, null, null, null, null, null,
            "2025-05-07T10:00:00Z", "2025-05-07T10:01:00Z",
            null, null, null);
        assertThat(tx.isDebit()).isTrue();
        assertThat(tx.isCredit()).isFalse();
    }

    @Test
    @DisplayName("TransactionRecord.isCredit for REFUND")
    void transactionRecord_isCredit() {
        var tx = new TransactionRecord("t2", "u1", "a1", "a2",
            BigDecimal.valueOf(200), "MXN", "REFUND", "COMPLETED",
            "VISA", null, null, null, null, null, null,
            "2025-05-07T10:00:00Z", "2025-05-07T10:01:00Z",
            null, null, null);
        assertThat(tx.isCredit()).isTrue();
        assertThat(tx.isDebit()).isFalse();
    }

    @Test
    @DisplayName("HourlyVolumeRecord.zero creates empty record")
    void hourlyVolume_zero() {
        var h = HourlyVolumeRecord.zero(14);
        assertThat(h.hour()).isEqualTo(14);
        assertThat(h.totalVolume()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(h.transactionCount()).isZero();
    }

    @Test
    @DisplayName("S3 key format follows expected pattern")
    void s3KeyFormat() {
        String key = com.nexus.reporting.storage.S3ReportUploader
            .buildKey("operations", LocalDate.of(2025, 5, 7), "pdf");
        assertThat(key).isEqualTo("daily/operations/2025/05/nexus-operations-2025-05-07.pdf");
    }

    private TransactionRecord txn(String status) {
        return new TransactionRecord("t", "u", "a1", "a2",
            BigDecimal.valueOf(250), "MXN", "PAYMENT", status,
            "VISA", null, null, null, null, null, null,
            "2025-05-07T10:00:00Z", "2025-05-07T10:01:00Z",
            null, null, null);
    }
}
