package com.nexus.reporting.generator;

import com.nexus.reporting.data.DailyReportData;
import com.nexus.reporting.model.*;
import com.nexus.reporting.pdf.PdfDocumentBuilder;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag("unit")
class OperationsReportGeneratorTest {

    @Test
    @DisplayName("PdfDocumentBuilder produces non-empty bytes")
    void pdfBuilder_producesOutput() throws Exception {
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder()) {
            LocalDate date = LocalDate.of(2025, 5, 7);
            float y = pdf.newPage();
            y = pdf.drawHeader("Test Report", date, y);
            y = pdf.drawSectionTitle("Section 1", y);
            y = pdf.drawKeyValue("Metric", "42", y);

            byte[] bytes = pdf.toBytes();
            assertThat(bytes).isNotEmpty();
            assertThat(bytes.length).isGreaterThan(100);
            // PDF magic number
            assertThat(bytes[0]).isEqualTo((byte) '%');
            assertThat(bytes[1]).isEqualTo((byte) 'P');
            assertThat(bytes[2]).isEqualTo((byte) 'D');
            assertThat(bytes[3]).isEqualTo((byte) 'F');
        }
    }

    @Test
    @DisplayName("PdfDocumentBuilder drawTable handles empty rows")
    void pdfBuilder_emptyTable() throws Exception {
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder()) {
            float y = pdf.newPage();
            String[] headers = {"A", "B"};
            float[] widths = {100, 100};
            y = pdf.drawTable(headers, List.of(), widths, y);
            byte[] bytes = pdf.toBytes();
            assertThat(bytes).isNotEmpty();
        }
    }

    @Test
    @DisplayName("PdfDocumentBuilder drawBarChart with zero values")
    void pdfBuilder_barChart_zeros() throws Exception {
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder()) {
            float y = pdf.newPage();
            List<BigDecimal> values = List.of(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            y = pdf.drawBarChart(values, y, 80);
            assertThat(pdf.toBytes()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("CategoryAggregate merge works")
    void categoryAggregate_merge() {
        var a = new CategoryAggregate(BigDecimal.valueOf(100), 5, 1);
        var b = new CategoryAggregate(BigDecimal.valueOf(200), 3, 1);
        var merged = new CategoryAggregate(
            a.totalAmount().add(b.totalAmount()),
            a.transactionCount() + b.transactionCount(),
            a.userCount() + b.userCount());
        assertThat(merged.totalAmount()).isEqualByComparingTo("300");
        assertThat(merged.transactionCount()).isEqualTo(8);
        assertThat(merged.userCount()).isEqualTo(2);
    }
}
