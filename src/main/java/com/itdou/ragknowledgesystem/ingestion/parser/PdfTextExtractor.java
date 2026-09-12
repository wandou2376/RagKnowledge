package com.itdou.ragknowledgesystem.ingestion.parser;

import java.nio.file.Path;
import java.util.List;

public interface PdfTextExtractor {

    // 按页提取 PDF 文本，返回顺序与 PDF 物理页序一致，页码从 1 开始
    List<PdfPage> extract(Path pdfPath);
}
