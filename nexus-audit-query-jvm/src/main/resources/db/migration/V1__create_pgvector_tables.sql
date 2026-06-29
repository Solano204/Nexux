CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS audit_event_embeddings (
    id      UUID    DEFAULT uuid_generate_v4() PRIMARY KEY,
    content TEXT,
    metadata JSON,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS audit_event_embeddings_embedding_idx
    ON audit_event_embeddings USING hnsw (embedding vector_cosine_ops);
