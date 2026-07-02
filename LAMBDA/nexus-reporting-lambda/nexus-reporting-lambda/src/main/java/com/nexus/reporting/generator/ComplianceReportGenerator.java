package com.nexus.reporting.generator;

import com.nexus.reporting.data.DailyReportData;
import com.nexus.reporting.model.*;
import com.nexus.reporting.pdf.PdfDocumentBuilder;
import com.nexus.reporting.storage.S3ReportUploader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Compliance report: PDF summary, full transaction CSV, fraud incidents CSV.
 * 7-year retention with CNBV-Required tagging. KMS encrypted in S3.
 * Transitions to Glacier after 1 year.
 */
public class ComplianceReportGenerator {

    private static final Logger log =
        LoggerFactory.getLogger(ComplianceReportGenerator.class);

    private static final Map<String, String> COMPLIANCE_TAGS = Map.of(
        "ReportType", "COMPLIANCE",
        "RegulatoryRetention", "7-YEARS",
        "CNBV-Required", "TRUE"
    );

    private final S3ReportUploader uploader;

    public ComplianceReportGenerator(S3ReportUploader uploader) {
        this.uploader = uploader;
    }

    public ReportResult generate(LocalDate date, DailyReportData data)
            throws Exception {

        List<String> files = new ArrayList<>();
        long totalBytes = 0;

        // ── Compliance PDF ─────────────────────────────
        byte[] pdf = generateCompliancePdf(date, data);
        String pdfKey = S3ReportUploader.buildKey("compliance", date, "pdf");
        uploader.upload(pdfKey, pdf, "application/pdf",
            withDate(COMPLIANCE_TAGS, date));
        files.add(pdfKey);
        totalBytes += pdf.length;

        // ── Full transaction CSV ───────────────────────
        byte[] txnCsv = generateFullTransactionCsv(data.transactions());
        String txnKey = S3ReportUploader.buildKey("transactions-full", date, "csv");
        uploader.upload(txnKey, txnCsv, "text/csv",
            withDate(Map.of("ReportType", "COMPLIANCE_TRANSACTIONS",
                             "RegulatoryRetention", "7-YEARS"), date));
        files.add(txnKey);
        totalBytes += txnCsv.length;

        // ── Fraud incidents CSV ────────────────────────
        byte[] fraudCsv = generateFraudIncidentsCsv(data.fraudAlerts());
        String fraudKey = S3ReportUploader.buildKey("fraud-incidents", date, "csv");
        uploader.upload(fraudKey, fraudCsv, "text/csv",
            withDate(Map.of("ReportType", "COMPLIANCE_FRAUD",
                             "RegulatoryRetention", "7-YEARS"), date));
        files.add(fraudKey);
        totalBytes += fraudCsv.length;

        return new ReportResult("COMPLIANCE", files, totalBytes, "SUCCESS");
    }

    private byte[] generateCompliancePdf(LocalDate date, DailyReportData data)
            throws Exception {
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder()) {
            float y = pdf.newPage();
            y = pdf.drawHeader("Nexus Bank — Reporte de Cumplimiento CNBV", date, y);

            y = pdf.drawSectionTitle("Resumen Regulatorio", y);
            y = pdf.drawKeyValue("Transacciones Procesadas", String.valueOf(data.transactionCount()), y);
            y = pdf.drawKeyValue("Alertas de Fraude", String.valueOf(data.fraudAlertCount()), y);

            long sarCount = data.fraudAlerts().stream().filter(FraudAlertRecord::sarRequired).count();
            y = pdf.drawKeyValue("Consideraciones SAR", String.valueOf(sarCount), y);
            y = pdf.drawKeyValue("Volumen Procesado", formatMxn(data.totalTransactionVolume()), y);
            y = pdf.drawKeyValue("Volumen Bloqueado", formatMxn(data.totalFraudBlockedVolume()), y);

            if (!data.fraudAlerts().isEmpty()) {
                y -= 15;
                y = pdf.drawSectionTitle("Incidentes de Fraude", y);
                String[] h = {"ID Alerta", "Usuario", "Monto", "Score", "Severidad", "SAR"};
                float[] w = {90, 80, 70, 45, 65, 35};
                List<String[]> rows = data.fraudAlerts().stream()
                    .map(a -> new String[]{
                        shorten(a.alertId(), 14), shorten(a.userId(), 12),
                        formatMxn(a.amount()), a.riskScore().toPlainString(),
                        a.severity(), a.sarRequired() ? "SI" : "NO"
                    }).toList();
                y = pdf.drawTable(h, rows, w, y);
            }

            pdf.drawFooter(y);
            return pdf.toBytes();
        }
    }

    private byte[] generateFullTransactionCsv(List<TransactionRecord> txns)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (CSVPrinter p = new CSVPrinter(
                new OutputStreamWriter(baos, StandardCharsets.UTF_8),
                CSVFormat.RFC4180.builder().setHeader(
                    "transaction_id", "user_id", "source_account_id",
                    "target_account_id", "amount", "currency",
                    "transaction_type", "status", "payment_network",
                    "merchant_id", "merchant_name", "merchant_category_code",
                    "category", "fraud_score", "fraud_decision",
                    "created_at", "completed_at", "processing_duration_ms",
                    "source_ip", "description"
                ).build())) {

            for (TransactionRecord tx : txns) {
                p.printRecord(
                    tx.transactionId(), tx.userId(), tx.sourceAccountId(),
                    s(tx.targetAccountId()), bd(tx.amount()), s(tx.currency()),
                    s(tx.transactionType()), s(tx.status()), s(tx.paymentNetwork()),
                    s(tx.merchantId()), s(tx.merchantName()), s(tx.merchantCategoryCode()),
                    s(tx.category()), bd(tx.fraudScore()), s(tx.fraudDecision()),
                    s(tx.createdAt()), s(tx.completedAt()),
                    tx.processingDurationMs() != null ? tx.processingDurationMs() : "",
                    s(tx.sourceIp()), s(tx.description()));
            }
        }
        return baos.toByteArray();
    }

    private byte[] generateFraudIncidentsCsv(List<FraudAlertRecord> alerts)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (CSVPrinter p = new CSVPrinter(
                new OutputStreamWriter(baos, StandardCharsets.UTF_8),
                CSVFormat.RFC4180.builder().setHeader(
                    "alert_id", "transaction_id", "user_id", "amount",
                    "currency", "severity", "alert_category", "risk_score",
                    "recommended_action", "requires_account_action",
                    "sar_required", "sar_id", "review_status", "created_at"
                ).build())) {

            for (FraudAlertRecord a : alerts) {
                p.printRecord(
                    a.alertId(), a.transactionId(), a.userId(),
                    bd(a.amount()), s(a.currency()), s(a.severity()),
                    s(a.alertCategory()), bd(a.riskScore()),
                    s(a.recommendedAction()), a.requiresAccountAction(),
                    a.sarRequired(), s(a.sarId()), s(a.reviewStatus()),
                    s(a.createdAt()));
            }
        }
        return baos.toByteArray();
    }

    private String s(String v) { return v != null ? v : ""; }
    private String bd(BigDecimal v) { return v != null ? v.toPlainString() : ""; }
    private String formatMxn(BigDecimal v) {
        return v != null ? String.format("MXN %,.2f", v) : "MXN 0.00";
    }
    private String shorten(String v, int max) {
        if (v == null) return "";
        return v.length() <= max ? v : v.substring(0, max - 2) + "..";
    }

    private Map<String, String> withDate(Map<String, String> tags, LocalDate d) {
        Map<String, String> m = new HashMap<>(tags);
        m.put("ReportDate", d.toString());
        return m;
    }
}
