package com.itdou.ragknowledgesystem.ingestion.parser;

import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoiExcelTextExtractorTest {

    private final PoiExcelTextExtractor extractor = new PoiExcelTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void shouldUseSheetOrderAsPageNumberAndPrefixColumnNames() throws Exception {
        Path xlsx = writeWorkbook();

        List<ExtractedPage> pages = extractor.extract(xlsx);

        assertThat(pages).hasSize(2);
        // 工作表序号就是页码，和工作簿里的顺序一致
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(1).pageNumber()).isEqualTo(2);

        String firstSheet = pages.get(0).text();
        assertThat(firstSheet).startsWith("工作表：错误码");
        // 表头行自己也算一行，列名可以被单独命中
        assertThat(firstSheet).contains("错误码 | 含义");
        // 关键：每行都带上列名，否则一块里全是裸值，检索时不知道列的含义
        assertThat(firstSheet).contains("错误码: E-4012 | 含义: 连接认证失败");
        assertThat(firstSheet).contains("错误码: E-4013 | 含义: 凭据过期");
    }

    @Test
    void shouldKeepEmptySheetAsEmptyPageToPreserveSheetOrder() throws Exception {
        Path xlsx = writeWorkbook();

        List<ExtractedPage> pages = extractor.extract(xlsx);

        // 空工作表也要占一个页码，它后面的工作表序号才不会整体前移
        assertThat(pages.get(1).text()).isEmpty();
        assertThat(pages.get(1).hasText()).isFalse();
    }

    @Test
    void shouldSkipBlankCellsWithoutShiftingColumns() throws Exception {
        Path xlsx = tempDir.resolve("blank.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(xlsx)) {
            Sheet sheet = workbook.createSheet("清单");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("名称");
            header.createCell(1).setCellValue("备注");
            header.createCell(2).setCellValue("负责人");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("部署手册");
            // 第 2 列空着
            data.createCell(2).setCellValue("张三");
            workbook.write(out);
        }

        String text = extractor.extract(xlsx).get(0).text();

        // 空单元格被跳过，但「负责人」必须仍然对到张三，不能错位成「备注: 张三」
        assertThat(text).contains("名称: 部署手册 | 负责人: 张三");
        assertThat(text).doesNotContain("备注: 张三");
    }

    @Test
    void shouldReportFormatAndFailOnBrokenFile() throws Exception {
        assertThat(extractor.format()).isEqualTo(DocumentFormat.XLSX);

        Path broken = tempDir.resolve("broken.xlsx");
        Files.writeString(broken, "this is not an xlsx");

        assertThatThrownBy(() -> extractor.extract(broken))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("Excel 文件");
    }

    // 两个工作表：一个有数据，一个是空的
    private Path writeWorkbook() throws Exception {
        Path xlsx = tempDir.resolve("error-codes.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(xlsx)) {
            Sheet codes = workbook.createSheet("错误码");
            Row header = codes.createRow(0);
            header.createCell(0).setCellValue("错误码");
            header.createCell(1).setCellValue("含义");
            Row second = codes.createRow(1);
            second.createCell(0).setCellValue("E-4012");
            second.createCell(1).setCellValue("连接认证失败");
            Row third = codes.createRow(2);
            third.createCell(0).setCellValue("E-4013");
            third.createCell(1).setCellValue("凭据过期");

            workbook.createSheet("空白页");
            workbook.write(out);
        }
        return xlsx;
    }
}
