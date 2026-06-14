// scripts/mongo-init.js
// Creates nexus_kyc database with collections for KYC documents and GridFS.

db = db.getSiblingDB('nexus_kyc');

// KYC documents collection
db.createCollection('kyc_documents');

db.kyc_documents.createIndex(
    { userId: 1, status: 1 },
    { name: "idx_kyc_user_status" }
);
db.kyc_documents.createIndex(
    { verificationId: 1 },
    { unique: true, name: "idx_kyc_verification_id" }
);
db.kyc_documents.createIndex(
    { createdAt: 1 },
    { expireAfterSeconds: 86400 * 365 * 7,  // 7-year retention (CNBV)
      name: "idx_kyc_documents_ttl" }
);

// GridFS collections are auto-created by MongoDB when first used
// but we pre-create indexes for query performance

// fs.files — GridFS file metadata
db.createCollection('fs.files');
db['fs.files'].createIndex(
    { filename: 1, uploadDate: -1 },
    { name: "idx_gridfs_filename" }
);
db['fs.files'].createIndex(
    { 'metadata.userId': 1 },
    { name: "idx_gridfs_user" }
);

print('MongoDB nexus_kyc database initialized');
print('Collections: kyc_documents (7-year TTL), fs.files');
