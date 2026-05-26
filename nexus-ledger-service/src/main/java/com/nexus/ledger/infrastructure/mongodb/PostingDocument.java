package com.nexus.ledger.infrastructure.mongodb;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CQRS Read Model — Posting document for quick retrieval.
 * One document per posting with both entry sides.
 */
@Document(collection = "posting_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostingDocument {

    @Id
    private String postingId;

    private String transactionId;
    private String postingType;
    private String description;
    private BigDecimal amount;
    private String currency;
    private Instant postedAt;
    private boolean isReversed;
    private String reversalPostingId;
}