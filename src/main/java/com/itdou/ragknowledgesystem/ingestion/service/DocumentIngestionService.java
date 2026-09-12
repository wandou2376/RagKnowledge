package com.itdou.ragknowledgesystem.ingestion.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentChunkResponse;
import com.itdou.ragknowledgesystem.document.dto.DocumentParseResponse;

import java.util.List;
import java.util.UUID;

public interface DocumentIngestionService {

    /*
     * 解析指定文档：提取文本 -> 清洗 -> 分块 -> 写入 document_chunk，
     * 并同步维护 document.parse_status。
     * 重复调用是幂等的：会先清掉该文档已有的分块再重新写入。
     */
    DocumentParseResponse parse(UUID documentId);

    List<DocumentChunkResponse> listChunks(UUID documentId);
}
