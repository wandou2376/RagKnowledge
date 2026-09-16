package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.document.dto.ChunkEmbeddingResponse;

import java.util.UUID;

public interface ChunkEmbeddingService {

    /*
     * 把指定文档下尚未向量化（或向量由别的模型产生）的分块送去向量化并写回。
     * 重复调用是幂等的：已经用当前模型处理过的分块会跳过。
     */
    ChunkEmbeddingResponse embedDocument(UUID documentId);
}
