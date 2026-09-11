-- Hashes are variable-length strings in the JPA entities, not fixed CHAR values.
ALTER TABLE rag.document
    ALTER COLUMN file_hash TYPE VARCHAR(64)
    USING btrim(file_hash)::VARCHAR(64);

ALTER TABLE rag.document_chunk
    ALTER COLUMN content_hash TYPE VARCHAR(64)
    USING btrim(content_hash)::VARCHAR(64);

ALTER TABLE rag.document
    ALTER COLUMN error_message TYPE VARCHAR(255)
    USING left(error_message, 255);
