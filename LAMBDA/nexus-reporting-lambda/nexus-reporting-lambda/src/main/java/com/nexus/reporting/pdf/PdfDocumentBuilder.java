package com.nexus.reporting.pdf;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * PDFBox helper for building Nexus report PDFs.
 * Uses Standard14Fonts (Helvetica) for portability — no TTF bundling needed.
 * All methods return the updated Y position for chaining.
 */
public class PdfDocumentBuilder implements AutoCloseable {

    private final PDDocument document;
    private final PDFont regularFont;
    private final PDFont boldFont;
    private PDPage currentPage;
    private PDPageContentStream cs;

    private static final float LEFT_MARGIN = 50;
    private static final float TOP_MARGIN = 770;
    private static final float PAGE_WIDTH = 595;     // A4
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * LEFT_MARGIN;
    private static final float ROW_HEIGHT = 22;

    public PdfDocumentBuilder() {
        this.document = new PDDocument();
        this.regularFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        this.boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    public float newPage() throws IOException {
        if (cs != null) cs.close();
        currentPage = new PDPage(PDRectangle.A4);
        document.addPage(currentPage);
        cs = new PDPageContentStream(document, currentPage);
        return TOP_MARGIN;
    }

    public float drawHeader(String title, LocalDate date, float y) throws IOException {
        // Dark header background
        cs.setNonStrokingColor(0.10f, 0.10f, 0.18f);
        cs.addRect(0, y - 10, PAGE_WIDTH, 50);
        cs.fill();

        cs.setNonStrokingColor(1f, 1f, 1f);
        cs.beginText();
        cs.setFont(boldFont, 16);
        cs.newLineAtOffset(LEFT_MARGIN, y + 10);
        cs.showText(title);
        cs.endText();

        cs.beginText();
        cs.setFont(regularFont, 10);
        cs.newLineAtOffset(PAGE_WIDTH - 180, y + 10);
        cs.showText("Fecha: " + date.toString());
        cs.endText();

        cs.setNonStrokingColor(0, 0, 0);
        return y - 60;
    }

    public float drawSectionTitle(String title, float y) throws IOException {
        cs.setNonStrokingColor(0.10f, 0.10f, 0.18f);
        cs.beginText();
        cs.setFont(boldFont, 13);
        cs.newLineAtOffset(LEFT_MARGIN, y);
        cs.showText(title);
        cs.endText();

        cs.setStrokingColor(0.10f, 0.10f, 0.18f);
        cs.setLineWidth(1f);
        cs.moveTo(LEFT_MARGIN, y - 4);
        cs.lineTo(LEFT_MARGIN + CONTENT_WIDTH, y - 4);
        cs.stroke();

        cs.setNonStrokingColor(0, 0, 0);
        return y - 24;
    }

    public float drawKeyValue(String label, String value, float y) throws IOException {
        cs.setNonStrokingColor(0.4f, 0.4f, 0.4f);
        cs.beginText();
        cs.setFont(regularFont, 10);
        cs.newLineAtOffset(LEFT_MARGIN + 8, y);
        cs.showText(label);
        cs.endText();

        cs.setNonStrokingColor(0, 0, 0);
        cs.beginText();
        cs.setFont(boldFont, 10);
        cs.newLineAtOffset(LEFT_MARGIN + 250, y);
        cs.showText(value);
        cs.endText();

        return y - ROW_HEIGHT;
    }

    /**
     * Draw a simple table with headers and rows.
     * Returns the Y position after the last row.
     */
    public float drawTable(String[] headers, List<String[]> rows,
                            float[] colWidths, float y) throws IOException {
        float x = LEFT_MARGIN;

        // Header background
        cs.setNonStrokingColor(0.92f, 0.92f, 0.95f);
        cs.addRect(x, y - ROW_HEIGHT + 4, CONTENT_WIDTH, ROW_HEIGHT);
        cs.fill();

        // Header text
        cs.setNonStrokingColor(0.15f, 0.15f, 0.15f);
        float cx = x + 4;
        for (int i = 0; i < headers.length; i++) {
            cs.beginText();
            cs.setFont(boldFont, 8);
            cs.newLineAtOffset(cx, y - ROW_HEIGHT + 10);
            cs.showText(truncate(headers[i], colWidths[i]));
            cs.endText();
            cx += colWidths[i];
        }
        y -= ROW_HEIGHT;

        // Data rows
        for (int r = 0; r < rows.size(); r++) {
            if (y < 60) {
                // Need new page
                y = newPage();
                y -= 30;
            }

            String[] row = rows.get(r);
            if (r % 2 == 0) {
                cs.setNonStrokingColor(0.97f, 0.97f, 0.98f);
                cs.addRect(x, y - ROW_HEIGHT + 4, CONTENT_WIDTH, ROW_HEIGHT);
                cs.fill();
            }

            cs.setNonStrokingColor(0.2f, 0.2f, 0.2f);
            cx = x + 4;
            for (int i = 0; i < row.length && i < colWidths.length; i++) {
                cs.beginText();
                cs.setFont(regularFont, 8);
                cs.newLineAtOffset(cx, y - ROW_HEIGHT + 10);
                cs.showText(truncate(row[i] != null ? row[i] : "", colWidths[i]));
                cs.endText();
                cx += colWidths[i];
            }
            y -= ROW_HEIGHT;
        }

        cs.setNonStrokingColor(0, 0, 0);
        return y - 10;
    }

    /**
     * Draw a simple horizontal bar chart for hourly volumes.
     */
    public float drawBarChart(List<BigDecimal> values, float y, float chartHeight)
            throws IOException {
        if (values == null || values.isEmpty()) return y;

        BigDecimal max = values.stream()
            .filter(v -> v != null)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ONE);
        if (max.compareTo(BigDecimal.ZERO) == 0) max = BigDecimal.ONE;

        float barWidth = CONTENT_WIDTH / values.size() - 2;
        float chartY = y - chartHeight;

        for (int i = 0; i < values.size(); i++) {
            BigDecimal v = values.get(i) != null ? values.get(i) : BigDecimal.ZERO;
            float barH = v.divide(max, 4, RoundingMode.HALF_UP).floatValue() * chartHeight;
            float barX = LEFT_MARGIN + i * (barWidth + 2);

            float intensity = 0.3f + (barH / chartHeight) * 0.7f;
            cs.setNonStrokingColor(0.2f, 0.4f * intensity, 0.8f);
            cs.addRect(barX, chartY, barWidth, Math.max(barH, 1));
            cs.fill();

            if (i % 4 == 0) {
                cs.setNonStrokingColor(0.5f, 0.5f, 0.5f);
                cs.beginText();
                cs.setFont(regularFont, 6);
                cs.newLineAtOffset(barX, chartY - 10);
                cs.showText(String.format("%02d:00", i));
                cs.endText();
            }
        }

        // Border
        cs.setStrokingColor(0.7f, 0.7f, 0.7f);
        cs.setLineWidth(0.5f);
        cs.addRect(LEFT_MARGIN, chartY, CONTENT_WIDTH, chartHeight);
        cs.stroke();

        cs.setNonStrokingColor(0, 0, 0);
        return chartY - 20;
    }

    public float drawFooter(float y) throws IOException {
        y = Math.min(y, 50);
        cs.setNonStrokingColor(0.6f, 0.6f, 0.6f);
        cs.beginText();
        cs.setFont(regularFont, 7);
        cs.newLineAtOffset(LEFT_MARGIN, 25);
        cs.showText("Nexus Bank - Reporte generado automaticamente. Confidencial.");
        cs.endText();
        cs.setNonStrokingColor(0, 0, 0);
        return y;
    }

    public byte[] toBytes() throws IOException {
        if (cs != null) { cs.close(); cs = null; }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos);
        return baos.toByteArray();
    }

    @Override
    public void close() throws IOException {
        if (cs != null) cs.close();
        document.close();
    }

    private String truncate(String s, float maxWidth) {
        int maxChars = (int) (maxWidth / 5);
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars - 1) + "…";
    }
}
