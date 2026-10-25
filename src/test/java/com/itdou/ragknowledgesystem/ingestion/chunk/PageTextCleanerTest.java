package com.itdou.ragknowledgesystem.ingestion.chunk;

import com.itdou.ragknowledgesystem.ingestion.parser.ExtractedPage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageTextCleanerTest {

    // 正文用纯字母单词，避免页码数字被归一化后各页正文看起来一样，干扰页眉页脚判定
    private static final String[] WORDS =
            {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa"};

    private static final int BODY_LINES_PER_PAGE = 8;

    private final PageTextCleaner cleaner = new PageTextCleaner();
    private final ChunkingProperties properties = new ChunkingProperties(600, 80, 120, 0.6, 3);

    @Test
    void shouldRemoveRepeatedHeaderAndFooterFromEveryPage() {
        List<ExtractedPage> cleaned = cleaner.clean(buildDocument(5), properties);

        assertThat(cleaned).hasSize(5);
        for (ExtractedPage page : cleaned) {
            assertThat(page.text()).doesNotContain("机密文档");
            assertThat(page.text()).doesNotContain("内部资料");
            assertThat(page.text().lines()).hasSize(BODY_LINES_PER_PAGE);
        }
    }

    @Test
    void shouldKeepHeaderWhenPageCountIsTooSmallToJudge() {
        // 只有 2 页，低于 boilerplateMinPages，任何行都不应该被当成页眉页脚剔除
        List<ExtractedPage> cleaned = cleaner.clean(buildDocument(2), properties);

        assertThat(cleaned.get(0).text()).contains("机密文档");
        assertThat(cleaned.get(0).text()).contains("内部资料");
    }

    @Test
    void shouldCollapseRedundantWhitespace() {
        List<ExtractedPage> cleaned = cleaner.clean(
                List.of(new ExtractedPage(1, "  前    后\t制表     空格  ")), properties);

        assertThat(cleaned.get(0).text()).isEqualTo("前 后 制表 空格");
    }

    @Test
    void shouldPreservePageNumbers() {
        List<ExtractedPage> cleaned = cleaner.clean(buildDocument(5), properties);

        assertThat(cleaned).extracting(ExtractedPage::pageNumber).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void shouldNotFailOnPagesWithoutText() {
        List<ExtractedPage> cleaned = cleaner.clean(
                List.of(new ExtractedPage(1, ""), new ExtractedPage(2, null), new ExtractedPage(3, "正文")), properties);

        assertThat(cleaned).hasSize(3);
        assertThat(cleaned.get(0).hasText()).isFalse();
        assertThat(cleaned.get(2).text()).isEqualTo("正文");
    }

    // 每页都是「页眉 + 8 行独有正文 + 页脚」，页眉页脚只靠数字区分页次
    private List<ExtractedPage> buildDocument(int pageCount) {
        List<ExtractedPage> pages = new ArrayList<>(pageCount);
        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            StringBuilder text = new StringBuilder();
            text.append("机密文档 第 ").append(pageNumber).append(" 页\n");
            for (int lineNumber = 0; lineNumber < BODY_LINES_PER_PAGE; lineNumber++) {
                text.append(WORDS[pageNumber]).append(' ').append(WORDS[lineNumber]).append(" 正文段落\n");
            }
            text.append("2026-09-11 内部资料\n");
            pages.add(new ExtractedPage(pageNumber, text.toString()));
        }
        return pages;
    }
}
