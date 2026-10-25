package com.itdou.ragknowledgesystem.ingestion.parser;

/**
 * 一个「逻辑页」的文本提取结果，是清洗和分块的输入。
 *
 * <p>为什么叫逻辑页而不是页：只有 PDF 存在真正的物理页。为避免在 Word / Excel 上撒谎，
 * 各格式的 {@code pageNumber} 含义约定如下（写入 document_chunk 时同样是这套语义）：
 *
 * <ul>
 *   <li>PDF：物理页序，从 1 开始。</li>
 *   <li>Word(.docx)：分页符切出的段序。Word 的页码是渲染时才算的，文件里只有分页符；
 *       文档没有分页符时整篇算 1 页。</li>
 *   <li>Excel(.xlsx)：工作表的序号，从 1 开始，与工作簿里的顺序一致（空工作表也占一个序号）。</li>
 * </ul>
 *
 * @param pageNumber 逻辑页序号，从 1 开始，是后续引用溯源的基础
 * @param text       该页提取到的原始文本，扫描版或纯图片页可能为空
 */
public record ExtractedPage(int pageNumber, String text) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
