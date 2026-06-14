package com.nexus.reporting.generator;

import com.nexus.reporting.data.DailyReportData;
import com.nexus.reporting.model.*;
import com.nexus.reporting.storage.S3ReportUploader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * User statement data: JSON (per-user tx list), CSV (user summaries).
 * Consumed by downstream process for individual statement generation.
 * 1-year retention in S3.
 */
public class UserStatementGenerator {

    private static final Logger log =
        LoggerFactory.getLogger(UserStatementGenerator.class);

    private final S3ReportUploader uploader;
    private final ObjectMapper mapper;

    public UserStatementGenerator(S3ReportUploader uploader,
                                   ObjectMapper mapper) {
        this.uploader = uploader;
        this.mapper = mapper;
    }

    public ReportResult generate(LocalDate date, DailyReportData data)
            throws Exception {
        List<String> files = new ArrayList<>();
        long totalBytes = 0;

        // ── JSON with per-user transaction lists ───────
        byte[] json = generateStatementJson(date, data);
        String jsonKey = S3ReportUploader.buildKey("statements-data", date, "json");
        uploader.upload(jsonKey, json, "application/json");
        files.add(jsonKey);
        totalBytes += json.length;

        // ── CSV with user summaries ────────────────────
        byte[] csv = generateSummariesCsv(date, data);
        String csvKey = S3ReportUploader.buildKey("user-summaries", date, "csv");
        uploader.upload(csvKey, csv, "text/csv");
        files.add(csvKey);
        totalBytes += csv.length;

        return new ReportResult("USER_STATEMENTS", files, totalBytes, "SUCCESS");
    }

    private byte[] generateStatementJson(LocalDate date, DailyReportData data)
            throws Exception {

        List<UserStatementData> statements = data.userDailyStats().entrySet().stream()
            .map(entry -> {
                String userId = entry.getKey();
                DailyUserStats stats = entry.getValue();

                List<UserStatementData.StatementTransaction> userTxns =
                    data.transactions().stream()
                        .filter(tx -> userId.equals(tx.userId()))
                        .sorted(Comparator.comparing(
                            tx -> tx.completedAt() != null ? tx.completedAt() : ""))
                        .map(tx -> new UserStatementData.StatementTransaction(
                            tx.transactionId(), tx.transactionType(),
                            tx.amount(), tx.currency(),
                            tx.merchantName() != null ? tx.merchantName() : tx.description(),
                            tx.category(), tx.completedAt(),
                            tx.isDebit() ? "DEBIT" : "CREDIT"))
                        .toList();

                return new UserStatementData(userId, date.toString(),
                    stats.totalSpent(), stats.totalReceived(),
                    stats.transactionCount(), stats.currency(), userTxns);
            }).toList();

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
            Map.of("reportDate", date.toString(),
                   "generatedAt", Instant.now().toString(),
                   "userCount", statements.size(),
                   "statements", statements));
    }

    private byte[] generateSummariesCsv(LocalDate date, DailyReportData data)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (CSVPrinter p = new CSVPrinter(
                new OutputStreamWriter(baos, StandardCharsets.UTF_8),
                CSVFormat.RFC4180.builder().setHeader(
                    "user_id", "date", "total_spent", "total_received",
                    "transaction_count", "currency"
                ).build())) {

            data.userDailyStats().forEach((userId, stats) -> {
                try {
                    p.printRecord(userId, date.toString(),
                        stats.totalSpent().toPlainString(),
                        stats.totalReceived().toPlainString(),
                        stats.transactionCount(), stats.currency());
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        return baos.toByteArray();
    }
}
