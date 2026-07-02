package com.nexus.reporting.metrics;

import com.nexus.reporting.model.ReportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CloudWatch metrics for report generation.
 * Key metric: ReportGenerationComplete — watched by ReportNotGenerated alarm.
 */
public class ReportingMetricsEmitter {

    private static final Logger log =
        LoggerFactory.getLogger(ReportingMetricsEmitter.class);

    private final CloudWatchClient cw;
    private final String namespace;

    public ReportingMetricsEmitter(CloudWatchClient cw, String namespace) {
        this.cw = cw;
        this.namespace = namespace;
    }

    public void emitCompletionMetrics(List<ReportResult> results,
                                       long durationMs) {
        List<MetricDatum> metrics = new ArrayList<>();
        Instant now = Instant.now();

        boolean allOk = results.stream()
            .allMatch(r -> "SUCCESS".equals(r.status()));

        metrics.add(MetricDatum.builder()
            .metricName("ReportGenerationComplete")
            .value(1.0).unit(StandardUnit.COUNT).timestamp(now)
            .dimensions(Dimension.builder()
                .name("Status").value(allOk ? "SUCCESS" : "PARTIAL").build())
            .build());

        metrics.add(MetricDatum.builder()
            .metricName("ReportGenerationDuration")
            .value((double) durationMs)
            .unit(StandardUnit.MILLISECONDS).timestamp(now)
            .build());

        for (ReportResult r : results) {
            metrics.add(MetricDatum.builder()
                .metricName("ReportTypeGenerated")
                .value(1.0).unit(StandardUnit.COUNT).timestamp(now)
                .dimensions(
                    Dimension.builder().name("ReportType").value(r.reportType()).build(),
                    Dimension.builder().name("Status").value(r.status()).build())
                .build());

            metrics.add(MetricDatum.builder()
                .metricName("ReportSizeBytes")
                .value((double) r.totalBytes())
                .unit(StandardUnit.BYTES).timestamp(now)
                .dimensions(Dimension.builder()
                    .name("ReportType").value(r.reportType()).build())
                .build());
        }

        try {
            cw.putMetricData(PutMetricDataRequest.builder()
                .namespace(namespace).metricData(metrics).build());
        } catch (Exception e) {
            log.warn("Failed to emit CloudWatch metrics: {}", e.getMessage());
        }
    }
}
