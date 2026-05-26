package com.nexus.ledger.infrastructure.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountLedgerSummaryRepository
        extends MongoRepository<AccountLedgerSummaryDocument, String> {

    Optional<AccountLedgerSummaryDocument> findByAccountId(String accountId);

    List<AccountLedgerSummaryDocument> findByUserId(String userId);
}