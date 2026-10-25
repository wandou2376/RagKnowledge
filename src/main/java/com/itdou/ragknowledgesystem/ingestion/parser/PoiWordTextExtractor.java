package com.itdou.ragknowledgesystem.ingestion.parser;

import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 POI 的 Word(.docx) 文本提取。
 *
 * <p>Word 文件里没有「页」这个对象——页码是 Word 渲染时才算出来的。所以要按分页符推算逻辑页：
 * 硬分页符 {@code <w:br w:type="page"/>} 和 Word 保存时写入的 {@code <w:lastRenderedPageBreak/>}
 * 都算一次分页。一个段落中间跨页时无法从中间切开，整段归入当前页再进位，
 * 所以页码在段落粒度上是近似的，但这已经是文件里能拿到的全部信息。
 *
 * <p>没有任何分页符的文档整篇算第 1 页。
 */
@Component
@Slf4j
public class PoiWordTextExtractor implements TextExtractor {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.DOCX;
    }

    @Override
    public List<ExtractedPage> extract(Path path) {
        try (InputStream inputStream = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(inputStream)) {

            List<ExtractedPage> pages = extractPerPage(document);
            log.debug("POI 逐段提取结束 path={} 逻辑页数={}", path, pages.size());
            return pages;
        } catch (IOException exception) {
            log.error("Word 文件无法读取（文件损坏或不是 docx）path={}", path, exception);
            throw new DocumentParsingException("无法读取 Word 文件: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            // 加密文档、扩展名与实际内容不符（例如 .doc 改成 .docx）都会落到这里
            log.error("Word 文件无法解析 path={}", path, exception);
            throw new DocumentParsingException("无法解析 Word 文件，可能是加密文档或内容格式不符: "
                    + exception.getMessage(), exception);
        }
    }

    private List<ExtractedPage> extractPerPage(XWPFDocument document) {
        List<ExtractedPage> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        int pageNumber = 1;

        for (IBodyElement element : document.getBodyElements()) {
            String text = elementText(element);
            if (text.isEmpty()) {
                continue;
            }
            if (currentPage.length() > 0) {
                currentPage.append('\n');
            }
            currentPage.append(text);

            int breaks = pageBreaks(element);
            for (int index = 0; index < breaks; index++) {
                pages.add(new ExtractedPage(pageNumber++, currentPage.toString()));
                currentPage.setLength(0);
            }
        }

        // 结尾可能没有分页符，剩下的内容仍然是一页
        if (currentPage.length() > 0 || pages.isEmpty()) {
            pages.add(new ExtractedPage(pageNumber, currentPage.toString()));
        }
        return pages;
    }

    private String elementText(IBodyElement element) {
        if (element instanceof XWPFParagraph paragraph) {
            return paragraph.getText() == null ? "" : paragraph.getText().strip();
        }
        if (element instanceof XWPFTable table) {
            return tableText(table);
        }
        return "";
    }

    /**
     * 表格按行展开成「单元格 | 单元格」。
     *
     * <p>不保留表格的视觉结构，但保留了同一行里各列的相邻关系——
     * 这对检索来说已经够用：一行里的错误码和它的含义仍然挨在一起。
     */
    private String tableText(XWPFTable table) {
        StringBuilder builder = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText() == null ? "" : cell.getText().replaceAll("\\s+", " ").strip();
                if (!cellText.isEmpty()) {
                    cells.add(cellText);
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(String.join(" | ", cells));
        }
        return builder.toString();
    }

    /**
     * 统计该元素内的分页次数。只认分页，行内换行 {@code <w:br/>} 不算。
     */
    private int pageBreaks(IBodyElement element) {
        if (!(element instanceof XWPFParagraph paragraph)) {
            return 0;
        }
        int breaks = 0;
        for (XWPFRun run : paragraph.getRuns()) {
            CTR runInside = run.getCTR();
            for (int index = 0; index < runInside.sizeOfBrArray(); index++) {
                if (runInside.getBrArray(index).getType() == STBrType.PAGE) {
                    breaks++;
                }
            }
            // Word 保存分页信息时会写这个标记，它对应的是布局断页，含金量比硬分页符更高
            breaks += runInside.sizeOfLastRenderedPageBreakArray();
        }
        return breaks;
    }
}
