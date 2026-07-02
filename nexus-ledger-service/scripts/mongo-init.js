// scripts/mongo-init.js
// Creates nexus_ledger CQRS read model collections with indexes.

db = db.getSiblingDB('nexus_ledger');

// account_ledger_summaries — pre-aggregated per-account ledger view
db.createCollection('account_ledger_summaries');

db.account_ledger_summaries.createIndex(
    { accountCode: 1 },
    { unique: true, name: "idx_ledger_summaries_account_code" }
);
db.account_ledger_summaries.createIndex(
    { userId: 1 },
    { name: "idx_ledger_summaries_user_id" }
);
db.account_ledger_summaries.createIndex(
    { lastUpdated: -1 },
    { name: "idx_ledger_summaries_last_updated" }
);

// posting_documents — fast read model for posting queries
db.createCollection('posting_documents');

db.posting_documents.createIndex(
    { postingId: 1 },
    { unique: true, name: "idx_postings_posting_id" }
);
db.posting_documents.createIndex(
    { accountCode: 1, postedAt: -1 },
    { name: "idx_postings_account_date" }
);
db.posting_documents.createIndex(
    { transactionId: 1 },
    { name: "idx_postings_transaction_id" }
);

print('MongoDB nexus_ledger database initialized');
print('Collections: account_ledger_summaries, posting_documents');
