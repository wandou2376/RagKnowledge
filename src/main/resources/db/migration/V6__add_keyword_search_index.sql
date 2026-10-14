-- 阶段 7：给分块内容加关键词检索能力。
--
-- 为什么不是 PostgreSQL 原生全文检索（tsvector）：
-- PostgreSQL 默认的分词器不认识中文，实测 to_tsvector('simple', '计算领域高质量科技期刊分级目录') 
-- 只会切出一个 token，搜「期刊」根本匹配不上；镜像里也没有 zhparser / pg_jieba 这类中文分词扩展。
-- pg_trgm 走的是字符三元组，对中英文都能工作，所以这里用它做关键词检索。
--
-- 注意：三元组索引加速的是 LIKE '%关键词%' 这类包含匹配，这一点在中文场景下刚好是需要的。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- content 上的三元组 GIN 索引。数据量小的时候规划器仍会选择顺序扫描，
-- 索引要到数据量上去之后才会体现出价值。
CREATE INDEX idx_document_chunk_content_trgm
    ON rag.document_chunk
    USING gin (content gin_trgm_ops);
