CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS fraud_policy_embeddings (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    content     TEXT,
    metadata    JSONB,
    embedding   vector(1536),
    CONSTRAINT pk_fraud_policy_embeddings PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_fraud_policy_embeddings_vector
    ON fraud_policy_embeddings
    USING hnsw (embedding vector_cosine_ops);
