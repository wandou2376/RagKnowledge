package com.itdou.ragknowledgesystem.ingestion.parser;

import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBoxTextExtractorTest {

    private final PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();

    @Test
    void shouldExtractEveryPageWithSequentialPageNumbers() throws Exception {
        List<PdfPage> pages = extractor.extract(resourcePath("test.pdf"));

        assertThat(pages).isNotEmpty();
        // 页码必须严格等于物理页序，引用溯源依赖这一点
        for (int index = 0; index < pages.size(); index++) {
            assertThat(pages.get(index).pageNumber()).isEqualTo(index + 1);
        }
        assertThat(pages.stream().filter(PdfPage::hasText)).as("test.pdf 中至少有页面能提取到文本").isNotEmpty();
    }

    @Test
    void shouldFailWithParsingExceptionForNonPdfContent() throws Exception {
        Path notAPdf = Files.createTempFile("not-a-pdf", ".pdf");
        try {
            Files.writeString(notAPdf, "this is plain text, not a pdf");

            assertThatThrownBy(() -> extractor.extract(notAPdf))
                    .isInstanceOf(DocumentParsingException.class)
                    .hasMessageContaining("无法读取 PDF 文件");
        } finally {
            Files.deleteIfExists(notAPdf);
        }
    }

    private Path resourcePath(String name) throws Exception {
        URL resource = getClass().getClassLoader().getResource(name);
        assertThat(resource).as("测试资源 %s 不存在", name).isNotNull();
        return Path.of(resource.toURI());
    }
}
