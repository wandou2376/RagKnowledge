package com.itdou.ragknowledgesystem.ingestion.parser;

import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;

import java.nio.file.Path;
import java.util.List;

/**
 * 某种文档格式的文本抽取器。每种支持的格式有一个实现，由 DocumentIngestionServiceImpl
 * 按文档的 media_type 选一个。
 */
public interface TextExtractor {

    DocumentFormat format();

    // 按逻辑页提取文本，返回顺序与页序一致，页码从 1 开始
    List<ExtractedPage> extract(Path path);
}
