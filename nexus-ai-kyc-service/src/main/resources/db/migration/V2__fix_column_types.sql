ALTER TABLE kyc_audit_entries
    ALTER COLUMN confidence_score TYPE DOUBLE PRECISION,
    ALTER COLUMN stage1_extraction_confidence TYPE DOUBLE PRECISION,
    ALTER COLUMN stage2_comparison_confidence TYPE DOUBLE PRECISION;