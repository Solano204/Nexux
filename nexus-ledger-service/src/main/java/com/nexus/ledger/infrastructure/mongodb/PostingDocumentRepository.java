package com.nexus.ledger.infrastructure.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostingDocumentRepository
        extends MongoRepository<PostingDocument, String> {

    Optional<PostingDocument> findByTransactionId(String transactionId);
}