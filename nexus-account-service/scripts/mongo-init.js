// scripts/mongo-init.js
// Runs automatically when MongoDB container starts for the first time.
// Creates the nexus_account database with indexes for the account analytics collection.

db = db.getSiblingDB('nexus_account');

// Create account_analytics collection with indexes
db.createCollection('account_analytics');

db.account_analytics.createIndex(
    { accountId: 1 },
    { unique: true, name: "idx_account_analytics_account_id" }
);

db.account_analytics.createIndex(
    { userId: 1 },
    { name: "idx_account_analytics_user_id" }
);

db.account_analytics.createIndex(
    { "currentPeriod.periodStart": -1 },
    { name: "idx_account_analytics_period" }
);

// Create account_advisor_memory collection (session memory for AI advisor)
db.createCollection('account_advisor_memory');

db.account_advisor_memory.createIndex(
    { accountId: 1, sessionId: 1 },
    { name: "idx_advisor_memory_account_session" }
);

db.account_advisor_memory.createIndex(
    { createdAt: 1 },
    { expireAfterSeconds: 86400 * 30, name: "idx_advisor_memory_ttl" } // 30-day TTL
);

print('MongoDB nexus_account database initialized');
print('Collections: account_analytics, account_advisor_memory');
