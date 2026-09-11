CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS rag;

CREATE FUNCTION rag.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TABLE rag.knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_knowledge_base_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED'))
);

CREATE TABLE rag.document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES rag.knowledge_base (id) ON DELETE CASCADE,
    original_file_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    file_hash CHAR(64) NOT NULL,
    parse_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    parsed_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_storage_key UNIQUE (storage_key),
    CONSTRAINT uk_document_file_hash UNIQUE (knowledge_base_id, file_hash),
    CONSTRAINT ck_document_file_size CHECK (file_size >= 0),
    CONSTRAINT ck_document_parse_status CHECK (parse_status IN ('PENDING', 'PARSING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE rag.document_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES rag.document (id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES rag.knowledge_base (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    page_number_start INTEGER,
    page_number_end INTEGER,
    section_title VARCHAR(500),
    token_count INTEGER NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_chunk_index UNIQUE (document_id, chunk_index),
    CONSTRAINT uk_knowledge_base_chunk_hash UNIQUE (knowledge_base_id, content_hash),
    CONSTRAINT ck_document_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_document_chunk_token_count CHECK (token_count > 0),
    CONSTRAINT ck_document_chunk_page_range CHECK (
        page_number_start IS NULL
        OR page_number_end IS NULL
        OR page_number_start <= page_number_end
    )
);

CREATE TABLE rag.ingestion_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES rag.document (id) ON DELETE CASCADE,
    task_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    progress SMALLINT NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ingestion_task_type CHECK (task_type IN ('PARSE', 'CHUNK', 'EMBED')),
    CONSTRAINT ck_ingestion_task_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_ingestion_task_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT ck_ingestion_task_retry_count CHECK (retry_count >= 0)
);

CREATE INDEX idx_document_knowledge_base_id ON rag.document (knowledge_base_id);
CREATE INDEX idx_document_parse_status ON rag.document (parse_status);
CREATE INDEX idx_document_chunk_document_id ON rag.document_chunk (document_id);
CREATE INDEX idx_document_chunk_knowledge_base_id ON rag.document_chunk (knowledge_base_id);
CREATE INDEX idx_document_chunk_metadata ON rag.document_chunk USING GIN (metadata);
CREATE INDEX idx_ingestion_task_document_id ON rag.ingestion_task (document_id);
CREATE INDEX idx_ingestion_task_status ON rag.ingestion_task (status);

CREATE TRIGGER trg_knowledge_base_updated_at
BEFORE UPDATE ON rag.knowledge_base
FOR EACH ROW EXECUTE FUNCTION rag.set_updated_at();

CREATE TRIGGER trg_document_updated_at
BEFORE UPDATE ON rag.document
FOR EACH ROW EXECUTE FUNCTION rag.set_updated_at();

CREATE TRIGGER trg_document_chunk_updated_at
BEFORE UPDATE ON rag.document_chunk
FOR EACH ROW EXECUTE FUNCTION rag.set_updated_at();

CREATE TRIGGER trg_ingestion_task_updated_at
BEFORE UPDATE ON rag.ingestion_task
FOR EACH ROW EXECUTE FUNCTION rag.set_updated_at();
