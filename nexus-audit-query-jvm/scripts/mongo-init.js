// scripts/mongo-init.js
// Creates nexus_audit database with compliance_reports collection.

db = db.getSiblingDB('nexus_audit');

db.createCollection('compliance_reports');

db.compliance_reports.createIndex(
    { complianceQueryId: 1 },
    { unique: true, name: "idx_compliance_query_id" }
);
db.compliance_reports.createIndex(
    { targetUserId: 1, createdAt: -1 },
    { name: "idx_compliance_user_date" }
);
db.compliance_reports.createIndex(
    { queryType: 1, severity: 1 },
    { name: "idx_compliance_type_severity" }
);
db.compliance_reports.createIndex(
    { createdAt: 1 },
    { expireAfterSeconds: 86400 * 365 * 7,  // 7-year retention (CNBV requirement)
      name: "idx_compliance_reports_ttl" }
);

print('MongoDB nexus_audit database initialized');
print('Collection: compliance_reports (7-year retention index set)');
