package com.itdou.ragknowledgesystem.ingestion.parser;

import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 POI 的 Excel(.xlsx) 文本提取。
 *
 * <p>一个工作表算一个逻辑页，序号就是工作表在工作簿里的顺序（空的也占一个序号，
 * 这样页码和工作表位置始终对得上）。
 *
 * <p>每一行输出成 {@code 列名: 值 | 列名: 值}，而不是用制表符或竖线直接拼单元格：
 * 分块是按行切的，只有把列名带在每一行里，切出来的块才有自解释能力。
 * 否则一块里全是「E-4012 | 401 | 连接认证失败」这种裸值，检索时根本不知道列的含义。
 *
 * <p>第一个非空行当作表头；没有表头时列名退化成分列序号。
 */
@Component
@Slf4j
public class PoiExcelTextExtractor implements TextExtractor {

    private static final String SHEET_PREFIX = "工作表：";

    @Override
    public DocumentFormat format() {
        return DocumentFormat.XLSX;
    }

    @Override
    public List<ExtractedPage> extract(Path path) {
        try (InputStream inputStream = Files.newInputStream(path);
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {

            List<ExtractedPage> pages = extractPerSheet(workbook);
            log.debug("POI 逐表提取结束 path={} 工作表数={}", path, pages.size());
            return pages;
        } catch (IOException exception) {
            log.error("Excel 文件无法读取（文件损坏或不是 xlsx）path={}", path, exception);
            throw new DocumentParsingException("无法读取 Excel 文件: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            // 加密文档、扩展名与实际内容不符（例如 .xls 改成 .xlsx）都会落到这里
            log.error("Excel 文件无法解析 path={}", path, exception);
            throw new DocumentParsingException("无法解析 Excel 文件，可能是加密文档或内容格式不符: "
                    + exception.getMessage(), exception);
        }
    }

    private List<ExtractedPage> extractPerSheet(XSSFWorkbook workbook) {
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        List<ExtractedPage> pages = new ArrayList<>(workbook.getNumberOfSheets());
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            String text = sheetText(sheet, formatter, evaluator);
            // 空工作表也照样入列，保证页码与工作表顺序一致
            pages.add(new ExtractedPage(sheetIndex + 1, text));
        }
        return pages;
    }

    private String sheetText(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        int lastRowNum = sheet.getLastRowNum();
        List<String> headers = null;
        StringBuilder body = new StringBuilder();

        for (int rowIndex = 0; rowIndex <= lastRowNum; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            List<String> values = rowValues(row, formatter, evaluator);
            if (values.isEmpty()) {
                continue;
            }
            if (headers == null) {
                // 第一行非空内容当表头，它本身也作为一行输出，便于单独命中列名
                headers = values;
                body.append('\n').append(String.join(" | ", nonEmpty(values)));
                continue;
            }
            String rowLine = rowText(headers, values);
            if (!rowLine.isEmpty()) {
                body.append('\n').append(rowLine);
            }
        }

        // 整张表没有任何内容时返回空串，让它算作一个空页，而不是产出一个只有表名的分块
        if (headers == null) {
            return "";
        }
        return SHEET_PREFIX + sheet.getSheetName() + body;
    }

    private String rowText(List<String> headers, List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
            String value = values.get(columnIndex);
            // 空格跳过但要保持下标对齐，否则后面的值会和表头错位
            if (value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            String header = columnIndex < headers.size() && !headers.get(columnIndex).isBlank()
                    ? headers.get(columnIndex)
                    : "第 " + (columnIndex + 1) + " 列";
            builder.append(header).append(": ").append(value);
        }
        return builder.toString();
    }

    private List<String> nonEmpty(List<String> values) {
        return values.stream().filter(value -> !value.isEmpty()).toList();
    }

    /**
     * 取一行里所有非空单元格的显示值。空单元格直接省略，避免输出一堆「备注: 」。
     */
    private List<String> rowValues(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        short lastCellNum = row.getLastCellNum();
        for (int columnIndex = 0; columnIndex < lastCellNum; columnIndex++) {
            Cell cell = row.getCell(columnIndex);
            String value = cellValue(cell, formatter, evaluator);
            if (!value.isEmpty()) {
                values.add(value);
            } else {
                // 空单元格也要占位，否则后面的列会在行内整体左移、与表头错位
                values.add("");
            }
        }
        // 去掉尾部占位的空列
        while (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) {
            values.remove(values.size() - 1);
        }
        return values;
    }

    private String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            return formatter.formatCellValue(cell, evaluator).strip();
        } catch (RuntimeException exception) {
            // 公式算不出来（引用了外部工作簿、不支持的函数）时退化成单元格的缓存值，
            // 不能让一个单元格把整份文档的解析带崩
            log.debug("单元格 {} 的公式求值失败，改用缓存值", cell.getAddress(), exception);
            return formatter.formatCellValue(cell).strip();
        }
    }
}
