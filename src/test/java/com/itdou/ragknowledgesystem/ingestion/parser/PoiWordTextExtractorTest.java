package com.itdou.ragknowledgesystem.ingestion.parser;

import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoiWordTextExtractorTest {

    private final PoiWordTextExtractor extractor = new PoiWordTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void shouldSplitPagesOnExplicitPageBreak() throws Exception {
        Path docx = tempDir.resolve("guide.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream out = Files.newOutputStream(docx)) {
            XWPFParagraph first = document.createParagraph();
            first.createRun().setText("第一页的内容");

            XWPFParagraph breaking = document.createParagraph();
            XWPFRun run = breaking.createRun();
            run.setText("第二页开头");
            run.addBreak(BreakType.PAGE);

            XWPFParagraph afterBreak = document.createParagraph();
            afterBreak.createRun().setText("第三页的内容");
            document.write(out);
        }

        List<ExtractedPage> pages = extractor.extract(docx);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(1).pageNumber()).isEqualTo(2);
        // 跨页的段落无法从中间切开，整段归入它起始的那一页
        assertThat(pages.get(0).text()).contains("第一页的内容", "第二页开头");
        assertThat(pages.get(1).text()).contains("第三页的内容");
    }

    @Test
    void shouldTreatWholeDocumentAsOnePageWithoutPageBreak() throws Exception {
        Path docx = tempDir.resolve("single.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream out = Files.newOutputStream(docx)) {
            document.createParagraph().createRun().setText("只有一段");
            document.createParagraph().createRun().setText("还有一段");
            document.write(out);
        }

        List<ExtractedPage> pages = extractor.extract(docx);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).text()).contains("只有一段", "还有一段");
    }

    @Test
    void shouldFlattenTableRowsIntoSingleLinePerRow() throws Exception {
        Path docx = tempDir.resolve("table.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream out = Files.newOutputStream(docx)) {
            document.createParagraph().createRun().setText("错误码速查");
            XWPFTable table = document.createTable(2, 3);
            fillRow(table.getRow(0), "错误码", "含义", "处理");
            fillRow(table.getRow(1), "E-4012", "连接认证失败", "检查凭据");
            document.write(out);
        }

        String text = extractor.extract(docx).get(0).text();

        // 同一行的单元格要留在同一行，否则错误码和它的含义会在分块时被拆散
        assertThat(text).contains("错误码 | 含义 | 处理");
        assertThat(text).contains("E-4012 | 连接认证失败 | 检查凭据");
    }

    @Test
    void shouldSkipLineBreakWhenCountingPages() throws Exception {
        Path docx = tempDir.resolve("line-break.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream out = Files.newOutputStream(docx)) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("上半行");
            run.addBreak();  // 不带 type 的 <w:br/> 是换行，不是分页
            run.setText("下半行");
            document.write(out);
        }

        assertThat(extractor.extract(docx)).hasSize(1);
    }

    @Test
    void shouldReportFormatAndFailOnBrokenFile() throws Exception {
        assertThat(extractor.format()).isEqualTo(DocumentFormat.DOCX);

        Path broken = tempDir.resolve("broken.docx");
        Files.writeString(broken, "this is not a docx");

        assertThatThrownBy(() -> extractor.extract(broken))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("Word 文件");
    }

    private void fillRow(XWPFTableRow row, String... values) {
        for (int index = 0; index < values.length; index++) {
            row.getCell(index).setText(values[index]);
        }
    }
}
