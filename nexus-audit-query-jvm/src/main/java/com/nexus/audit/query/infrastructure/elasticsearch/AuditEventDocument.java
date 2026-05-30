package com.nexus.audit.query.infrastructure.elasticsearch;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.Map;

@Document(indexName = "nexus-audit-*", createIndex = false)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventDocument {
    @Id
    private String eventId;
    @Field(type = FieldType.Keyword) private String eventType;
    @Field(type = FieldType.Keyword) private String category;
    @Field(type = FieldType.Keyword) private String severity;
    @Field(type = FieldType.Keyword) private String userId;
    @Field(type = FieldType.Keyword) private String resourceType;
    @Field(type = FieldType.Keyword) private String resourceId;
    @Field(type = FieldType.Object) private Map<String, Object> payload;
    @Field(type = FieldType.Keyword) private String sourceService;
    @Field(type = FieldType.Keyword) private String traceId;
    @Field(type = FieldType.Date) private Instant eventTimestamp;
    @Field(type = FieldType.Boolean) private boolean isFinancialEvent;
    @Field(type = FieldType.Boolean) private boolean requiresSarReview;
}