ALTER TABLE audit_log ALTER COLUMN ip_address TYPE varchar(255) USING ip_address::varchar;
ALTER TABLE sessions ALTER COLUMN ip_address TYPE varchar(255) USING ip_address::varchar;
