ALTER TABLE kyc_audit_entries
    ALTER COLUMN ai_model_stage1 DROP NOT NULL,
    ALTER COLUMN ai_model_stage2 DROP NOT NULL;
