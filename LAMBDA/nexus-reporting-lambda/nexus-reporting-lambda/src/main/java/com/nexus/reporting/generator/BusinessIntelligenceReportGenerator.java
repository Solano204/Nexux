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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business Intelligence report: PDF with category trends, CSV breakdown.
 * 1-year retention in S3 (transitions to STANDARD_IA after 90 days).
 */
public class BusinessIntelligenceReportGenerator {

    private static final Logger log =
        LoggerFactory.getLogger(BusinessIntelligenceReportGenerator.class);

    private final S3ReportUploader uploader;

    public BusinessIntelligenceReportGenerator(S3ReportUploader uploader) {
        this.uploader = uploader;
    }

    public ReportResult generate(LocalDate date, DailyReportData data)
            throws Exception {
        List<String> files = new ArrayList<>();
        long totalBytes = 0;

        // ── BI PDF ─────────────────────────────────────
        byte[] pdf = generateBiPdf(date, data);
        String pdfKey = S3ReportUploader.buildKey("business-intelligence", date, "pdf");
        uploader.upload(pdfKey, pdf, "application/pdf");
        files.add(pdfKey);
        totalBytes += pdf.length;

        // ── Category breakdown CSV ─────────────────────
        byte[] csv = generateCategoryCsv(date, data);
        String csvKey = S3ReportUploader.buildKey("category-breakdown", date, "csv");
        uploader.upload(csvKey, csv, "text/csv");
        files.add(csvKey);
        totalBytes += csv.length;

        return new ReportResult("BUSINESS_INTELLIGENCE", files, totalBytes, "SUCCESS");
    }

    private byte[] generateBiPdf(LocalDate date, DailyReportData data)
            throws Exception {
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder()) {
            float y = pdf.newPage();
            y = pdf.drawHeader("Nexus Bank — Inteligencia de Negocio", date, y);

            y = pdf.drawSectionTitle("Metricas Clave", y);
            y = pdf.drawKeyValue("Transacciones", String.valueOf(data.transactionCount()), y);
            y = pdf.drawKeyValue("Volumen Total", fmtMxn(data.totalTransactionVolume()), y);
            y = pdf.drawKeyValue("Usuarios Activos", String.valueOf(data.activeUserCount()), y);

            BigDecimal avgTx = data.transactionCount() > 0
                ? data.totalTransactionVolume().divide(BigDecimal.valueOf(data.transactionCount()), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            y = pdf.drawKeyValue("Monto Promedio por Transaccion", fmtMxn(avgTx), y);

            // Category breakdown table
            y -= 10;
            y = pdf.drawSectionTitle("Desglose por Categoria", y);

            Map<String, CategoryAggregate> agg = aggregateCategories(data);
            String[] headers = {"Categoria", "Monto Total", "# Txns", "Usuarios", "% Vol"};
            float[] widths = {110, 90, 60, 60, 60};

            BigDecimal tv = data.totalTransactionVolume().compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ONE : data.totalTransactionVolume();

            List<String[]> rows = agg.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().totalAmount(), Comparator.reverseOrder()))
                .limit(15)
                .map(e -> {
                    var a = e.getValue();
                    BigDecimal pct = a.totalAmount().divide(tv, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                    return new String[]{
                        e.getKey(), fmtMxn(a.totalAmount()),
                        String.valueOf(a.transactionCount()),
                        String.valueOf(a.userCount()),
                        pct.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%"
                    };
                }).toList();
            y = pdf.drawTable(headers, rows, widths, y);

            // Hourly chart
            if (y < 180) { y = pdf.newPage(); y -= 30; }
            y = pdf.drawSectionTitle("Volumen por Hora", y);
            List<BigDecimal> hourly = data.hourlyVolumes().stream()
                .map(HourlyVolumeRecord::totalVolume).toList();
            y = pdf.drawBarChart(hourly, y, 80);

            pdf.drawFooter(y);
            return pdf.toBytes();
        }
    }

    private byte[] generateCategoryCsv(LocalDate date, DailyReportData data)
            throws Exception {
        Map<String, CategoryAggregate> agg = aggregateCategories(data);
        BigDecimal tv = data.totalTransactionVolume().compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ONE : data.totalTransactionVolume();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (CSVPrinter p = new CSVPrinter(
                new OutputStreamWriter(baos, StandardCharsets.UTF_8),
                CSVFormat.RFC4180.builder().setHeader(
                    "category", "total_amount_mxn", "transaction_count",
                    "user_count", "avg_transaction_amount", "percent_of_total_volume"
                ).build())) {

            agg.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().totalAmount(), Comparator.reverseOrder()))
                .forEach(e -> {
                    var a = e.getValue();
                    BigDecimal avg = a.transactionCount() > 0
                        ? a.totalAmount().divide(BigDecimal.valueOf(a.transactionCount()), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                    BigDecimal pct = a.totalAmount().divide(tv, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                    try {
                        p.printRecord(e.getKey(), a.totalAmount().toPlainString(),
                            a.transactionCount(), a.userCount(),
                            avg.toPlainString(), pct.toPlainString());
                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                });
        }
        return baos.toByteArray();
    }

    private Map<String, CategoryAggregate> aggregateCategories(DailyReportData data) {
        Map<String, CategoryAggregate> result = new HashMap<>();
        data.categoryStats().values().forEach(userCat ->
            userCat.categories().forEach((cat, detail) ->
                result.merge(cat,
                    new CategoryAggregate(detail.totalAmount(), detail.transactionCount(), 1),
                    (a, b) -> new CategoryAggregate(
                        a.totalAmount().add(b.totalAmount()),
                        a.transactionCount() + b.transactionCount(),
                        a.userCount() + 1))));
        return result;
    }

    private String fmtMxn(BigDecimal v) {
        return v != null ? String.format("MXN %,.2f", v) : "MXN 0.00";
    }
}
