// scripts/mongo-init.js
db = db.getSiblingDB('nexus_audit');

db.createCollection('compliance_alerts');

db.compliance_alerts.createIndex(
    { userId: 1, createdAt: -1 },
    { name: "idx_alerts_user_date" }
);
db.compliance_alerts.createIndex(
    { ruleType: 1, severity: 1 },
    { name: "idx_alerts_type_severity" }
);
db.compliance_alerts.createIndex(
    { createdAt: 1 },
    { expireAfterSeconds: 86400 * 365 * 7,  // 7-year retention
      name: "idx_alerts_ttl" }
);

print('MongoDB nexus_audit initialized — compliance_alerts collection ready');
