-- 阶段 4：给分块加上向量列，用于语义检索。
--
-- 维度 1024 必须与 BAAI/bge-m3 的输出一致，也要与 application-dev.yml 里的
-- app.embedding.dimension 保持一致。换 Embedding 模型时维度会变，那时需要新建迁移改这一列。
--
-- embedding 允许为空：解析和向量化是两步，分块先落库，之后才向量化。
-- 未向量化的分块用 embedded_at IS NULL 判断，所以不要把该列设成 NOT NULL。

ALTER TABLE rag.document_chunk
    ADD COLUMN embedding vector(1024),
    ADD COLUMN embedding_model VARCHAR(100),
    ADD COLUMN embedded_at TIMESTAMPTZ;

-- HNSW + 余弦距离。bge-m3 的向量是归一化的，余弦距离是这类模型的标准选择。
-- 建索引时表还是空的，不会有构建开销。
CREATE INDEX idx_document_chunk_embedding_hnsw
    ON rag.document_chunk
    USING hnsw (embedding vector_cosine_ops);
