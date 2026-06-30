CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS financial_literacy_embeddings (
    id        UUID    DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS financial_literacy_embeddings_embedding_idx
    ON financial_literacy_embeddings USING hnsw (embedding vector_cosine_ops);
