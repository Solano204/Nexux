package com.nexus.reporting.generator;

import com.nexus.reporting.data.DailyReportData;
import com.nexus.reporting.model.*;
import com.nexus.reporting.pdf.PdfDocumentBuilder;
import com.nexus.reporting.storage.S3ReportUploader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Operations report: PDF (executive summary + charts),
 * JSON (machine-readable), CSV (summary table).
 * 90-day retention in S3.
 */
public class OperationsReportGenerator {

    private static final Logger log =
        LoggerFactory.getLogger(OperationsReportGenerator.class);

    private final S3ReportUploader uploader;
    private final ObjectMapper mapper;

    public OperationsReportGenerator(S3ReportUploader uploader,
                                      ObjectMapper mapper) {
        this.uploader = uploader;
        this.mapper = mapper;
    }

    public ReportResult generate(LocalDate date, DailyReportData data)
            throws Exception {

        List<String> files = new ArrayList<>();
        long totalBytes = 0;

        // ── PDF ────────────────────────────────────────
        byte[] pdf = generatePdf(date, data);
        String pdfKey = S3ReportUploader.buildKey("operations", date, "pdf");
        uploader.upload(pdfKey, pdf, "application/pdf");
        files.add(pdfKey);
        totalBytes += pdf.length;

        // ── JSON ───────────────────────────────────────
        byte[] json = generateJson(date, data);
        String jsonKey = S3ReportUploader.buildKey("operations", date, "json");
        uploader.upload(jsonKey, json, "application/json");
        files.add(jsonKey);
        totalBytes += json.length;

        // ── CSV ────────────────────────────────────────
        byte[] csv = generateCsv(date, data);
        String csvKey = S3ReportUploader.buildKey("operations-summary", date, "csv");
        uploader.upload(csvKey, csv, "text/csv");
        files.add(csvKey);
        totalBytes += csv.length;

        return new ReportResult("OPERATIONS", files, totalBytes, "SUCCESS");
    }

    private byte[] generatePdf(LocalDate date, DailyReportData data) throws Exception {
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder()) {
            // Page 1 — Executive Summary
            float y = pdf.newPage();
            y = pdf.drawHeader("Nexus Bank — Reporte de Operaciones", date, y);
            y = pdf.drawSectionTitle("Resumen del Dia", y);

            y = pdf.drawKeyValue("Total Transacciones", String.valueOf(data.transactionCount()), y);
            y = pdf.drawKeyValue("Volumen Total", formatCurrency(data.totalTransactionVolume()), y);
            y = pdf.drawKeyValue("Usuarios Activos", String.valueOf(data.activeUserCount()), y);
            y = pdf.drawKeyValue("Alertas de Fraude", String.valueOf(data.fraudAlertCount()), y);
            y = pdf.drawKeyValue("Volumen Bloqueado (Fraude)", formatCurrency(data.totalFraudBlockedVolume()), y);
            y = pdf.drawKeyValue("Tasa de Exito", String.format("%.1f%%", data.successRate() * 100), y);

            // Hourly volume chart
            y -= 10;
            y = pdf.drawSectionTitle("Volumen por Hora", y);
            List<BigDecimal> hourlyValues = data.hourlyVolumes().stream()
                .map(HourlyVolumeRecord::totalVolume).toList();
            y = pdf.drawBarChart(hourlyValues, y, 100);

            // Transaction type breakdown
            y -= 10;
            if (y < 200) { y = pdf.newPage(); y -= 30; }
            y = pdf.drawSectionTitle("Desglose por Tipo", y);

            Map<String, Long> typeBreakdown = data.transactions().stream()
                .collect(Collectors.groupingBy(
                    t -> t.transactionType() != null ? t.transactionType() : "UNKNOWN",
                    Collectors.counting()));

            String[] headers = {"Tipo", "Cantidad", "% del Total"};
            float[] widths = {200, 100, 100};
            List<String[]> rows = typeBreakdown.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new String[]{
                    e.getKey(),
                    String.valueOf(e.getValue()),
                    String.format("%.1f%%", 100.0 * e.getValue() / Math.max(data.transactionCount(), 1))
                }).toList();
            y = pdf.drawTable(headers, rows, widths, y);

            // Fraud summary
            if (!data.fraudAlerts().isEmpty()) {
                if (y < 150) { y = pdf.newPage(); y -= 30; }
                y = pdf.drawSectionTitle("Resumen de Fraude", y);
                String[] fHeaders = {"ID Alerta", "Severidad", "Categoria", "Score", "Monto"};
                float[] fWidths = {100, 70, 120, 50, 80};
                List<String[]> fRows = data.fraudAlerts().stream()
                    .limit(20)
                    .map(a -> new String[]{
                        a.alertId().length() > 12 ? a.alertId().substring(0, 12) + "..." : a.alertId(),
                        a.severity(), a.alertCategory(),
                        a.riskScore().toPlainString(), formatCurrency(a.amount())
                    }).toList();
                y = pdf.drawTable(fHeaders, fRows, fWidths, y);
            }

            pdf.drawFooter(y);
            return pdf.toBytes();
        }
    }

    private byte[] generateJson(LocalDate date, DailyReportData data)
            throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportDate", date.toString());
        report.put("reportType", "OPERATIONS");
        report.put("transactionCount", data.transactionCount());
        report.put("totalVolume", data.totalTransactionVolume());
        report.put("activeUsers", data.activeUserCount());
        report.put("fraudAlertCount", data.fraudAlertCount());
        report.put("fraudBlockedVolume", data.totalFraudBlockedVolume());
        report.put("successRate", data.successRate());

        Map<String, Integer> hourlyTxnCounts = new LinkedHashMap<>();
        data.hourlyVolumes().forEach(h ->
            hourlyTxnCounts.put(String.format("%02d:00", h.hour()), h.transactionCount()));
        report.put("hourlyTransactionCounts", hourlyTxnCounts);

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
    }

    private byte[] generateCsv(LocalDate date, DailyReportData data)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (CSVPrinter p = new CSVPrinter(
                new OutputStreamWriter(baos, StandardCharsets.UTF_8),
                CSVFormat.RFC4180.builder()
                    .setHeader("metric", "value").build())) {
            p.printRecord("report_date", date);
            p.printRecord("transaction_count", data.transactionCount());
            p.printRecord("total_volume_mxn", data.totalTransactionVolume().toPlainString());
            p.printRecord("active_users", data.activeUserCount());
            p.printRecord("fraud_alert_count", data.fraudAlertCount());
            p.printRecord("fraud_blocked_volume_mxn", data.totalFraudBlockedVolume().toPlainString());
            p.printRecord("success_rate", String.format("%.4f", data.successRate()));
        }
        return baos.toByteArray();
    }

    private String formatCurrency(BigDecimal amount) {
        if (amount == null) return "MXN 0.00";
        return String.format("MXN %,.2f", amount);
    }
}
