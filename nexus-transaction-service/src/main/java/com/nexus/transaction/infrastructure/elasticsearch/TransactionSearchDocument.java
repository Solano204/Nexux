package com.nexus.transaction.infrastructure.elasticsearch;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transaction Search Document — Elasticsearch index mapping.
 *
 * Optimized for:
 * - Full-text search on description, merchantName
 * - Range queries on amount, initiatedAt
 * - Term filters on status, transactionType, userId
 *
 * Updated asynchronously after every status change.
 */
@Document(indexName = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionSearchDocument {

    @Id
    private String transactionId;

    @Field(type = FieldType.Keyword)
    private String userId;

    @Field(type = FieldType.Keyword)
    private String sourceAccountId;

    @Field(type = FieldType.Keyword)
    private String targetAccountId;

    @Field(type = FieldType.Double)
    private BigDecimal amount;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String transactionType;

    // Full-text searchable fields
    @Field(type = FieldType.Text, analyzer = "spanish")
    private String description;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String merchantName;

    @Field(type = FieldType.Keyword)
    private String merchantCategoryCode;

    @Field(type = FieldType.Double)
    private BigDecimal fraudScore;

    @Field(type = FieldType.Keyword)
    private String fraudDecision;

    @Field(type = FieldType.Date,
            format = DateFormat.date_time)
    private Instant initiatedAt;

    @Field(type = FieldType.Date,
            format = DateFormat.date_time)
    private Instant completedAt;

    @Field(type = FieldType.Text)
    private String failureReason;
}