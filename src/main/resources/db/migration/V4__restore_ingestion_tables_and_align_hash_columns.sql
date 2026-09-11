-- V3 was already applied in an earlier local database. Restore the tables it removed
-- and bring hash columns in line with the String fields in the JPA entities.
ALTER TABLE rag.document
    ALTER COLUMN file_hash TYPE VARCHAR(64)
    USING btrim(file_hash)::VARCHAR(64);

ALTER TABLE rag.document
    ALTER COLUMN error_message TYPE VARCHAR(255)
    USING left(error_message, 255);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'document_knowledge_base_id_fkey'
          AND conrelid = 'rag.document'::regclass
    ) THEN
        ALTER TABLE rag.document
            ADD CONSTRAINT document_knowledge_base_id_fkey
            FOREIGN KEY (knowledge_base_id)
            REFERENCES rag.knowledge_base (id)
            ON DELETE CASCADE;
    END IF;
END;
$$;

CREATE TABLE IF NOT EXISTS rag.document_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES rag.document (id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES rag.knowledge_base (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
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

ALTER TABLE rag.document_chunk
    ALTER COLUMN content_hash TYPE VARCHAR(64)
    USING btrim(content_hash)::VARCHAR(64);

CREATE TABLE IF NOT EXISTS rag.ingestion_task (
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

CREATE INDEX IF NOT EXISTS idx_document_chunk_document_id ON rag.document_chunk (document_id);
CREATE INDEX IF NOT EXISTS idx_document_chunk_knowledge_base_id ON rag.document_chunk (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_document_chunk_metadata ON rag.document_chunk USING GIN (metadata);
CREATE INDEX IF NOT EXISTS idx_ingestion_task_document_id ON rag.ingestion_task (document_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_task_status ON rag.ingestion_task (status);

DROP TRIGGER IF EXISTS trg_document_chunk_updated_at ON rag.document_chunk;
CREATE TRIGGER trg_document_chunk_updated_at
BEFORE UPDATE ON rag.document_chunk
FOR EACH ROW EXECUTE FUNCTION rag.set_updated_at();

DROP TRIGGER IF EXISTS trg_ingestion_task_updated_at ON rag.ingestion_task;
CREATE TRIGGER trg_ingestion_task_updated_at
BEFORE UPDATE ON rag.ingestion_task
FOR EACH ROW EXECUTE FUNCTION rag.set_updated_at();
