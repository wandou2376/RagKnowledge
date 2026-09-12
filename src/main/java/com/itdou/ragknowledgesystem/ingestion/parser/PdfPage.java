package com.itdou.ragknowledgesystem.ingestion.parser;

/**
 * 单页 PDF 的文本提取结果。
 *
 * @param pageNumber 页码，从 1 开始，直接对应 PDF 的物理页序，是后续引用溯源的基础
 * @param text       该页提取到的原始文本，扫描版或纯图片页可能为空
 */
public record PdfPage(int pageNumber, String text) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
