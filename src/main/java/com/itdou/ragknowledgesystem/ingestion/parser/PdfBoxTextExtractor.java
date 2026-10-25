package com.itdou.ragknowledgesystem.ingestion.parser;

import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 PDFBox 的逐页文本提取。
 */
@Component
@Slf4j
public class PdfBoxTextExtractor implements TextExtractor {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    @Override
    public List<ExtractedPage> extract(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            List<ExtractedPage> pages = extractPerPage(document);
            // 提取结果的业务级日志由 DocumentIngestionServiceImpl 统一打（那里有 documentId），
            // 这里只记组件级的细节，避免同一件事打两遍
            log.debug("PDFBox 逐页提取结束 path={} 页数={}", path, pages.size());
            return pages;
        } catch (IOException exception) {
            // 文件损坏、被加密、或根本不是 PDF 都会在这里抛 IOException
            log.error("PDF 无法读取（文件损坏、被加密或不是 PDF）path={}", path, exception);
            throw new DocumentParsingException("无法读取 PDF 文件: " + exception.getMessage(), exception);
        }
    }

    private List<ExtractedPage> extractPerPage(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        // 按坐标排序才能得到接近阅读顺序的文本，否则多栏排版、表格会串行
        stripper.setSortByPosition(true);

        int pageCount = document.getNumberOfPages();
        List<ExtractedPage> pages = new ArrayList<>(pageCount);
        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            // 一页一次提取：页码直接取自页序，后面才能把 chunk 映射回真实页码
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            ExtractedPage page = new ExtractedPage(pageNumber, stripper.getText(document));

            // 只记无文本的页，逐页都打会把大文档的日志刷爆
            if (!page.hasText()) {
                log.debug("第 {} 页未提取到文本（可能是纯图片页或扫描页）", pageNumber);
            }
            pages.add(page);
        }
        return pages;
    }
}
