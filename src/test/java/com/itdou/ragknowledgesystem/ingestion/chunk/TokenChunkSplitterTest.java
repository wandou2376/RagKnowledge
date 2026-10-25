package com.itdou.ragknowledgesystem.ingestion.chunk;

import com.itdou.ragknowledgesystem.ingestion.parser.ExtractedPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TokenChunkSplitterTest {

    private static final int TARGET_TOKENS = 100;
    private static final int MIN_CHUNK_TOKENS = 30;

    private final ChunkingProperties properties =
            new ChunkingProperties(TARGET_TOKENS, 20, MIN_CHUNK_TOKENS, 0.6, 3);
    private final TokenChunkSplitter splitter = new TokenChunkSplitter(properties);

    @Test
    void shouldReturnNoChunkWhenThereIsNoText() {
        assertThat(splitter.split(List.of())).isEmpty();
        assertThat(splitter.split(List.of(new ExtractedPage(1, "")))).isEmpty();
        assertThat(splitter.split(List.of(new ExtractedPage(1, "   \n  ")))).isEmpty();
    }

    @Test
    void shouldKeepShortPageInASingleChunk() {
        List<DocumentChunkDraft> drafts = splitter.split(List.of(new ExtractedPage(1, "第一行\n第二行")));

        assertThat(drafts).hasSize(1);
        DocumentChunkDraft draft = drafts.get(0);
        assertThat(draft.chunkIndex()).isZero();
        assertThat(draft.content()).isEqualTo("第一行\n第二行");
        assertThat(draft.pageNumberStart()).isEqualTo(1);
        assertThat(draft.pageNumberEnd()).isEqualTo(1);
        assertThat(draft.tokenCount()).isPositive();
        // SHA-256 十六进制串固定 64 个字符
        assertThat(draft.contentHash()).hasSize(64);
    }

    @Test
    void shouldSplitLongTextIntoMultipleChunks() {
        List<DocumentChunkDraft> drafts = splitter.split(List.of(new ExtractedPage(1, repeatLines(40))));

        assertThat(drafts).hasSizeGreaterThan(1);
        // 尾块并回上一块会略微超出目标大小，这里给出 1.5 倍的上界
        assertThat(drafts).allSatisfy(draft ->
                assertThat(draft.tokenCount()).isLessThanOrEqualTo(TARGET_TOKENS * 3 / 2));
    }

    @Test
    void shouldNumberChunksSequentiallyFromZero() {
        List<DocumentChunkDraft> drafts = splitter.split(List.of(new ExtractedPage(1, repeatLines(40))));

        assertThat(drafts).extracting(DocumentChunkDraft::chunkIndex)
                .containsExactlyElementsOf(IntStream.range(0, drafts.size()).boxed().toList());
    }

    // 相邻分块必须重叠：下一块的首行应该能在上一块里找到
    @Test
    void shouldOverlapAdjacentChunks() {
        List<DocumentChunkDraft> drafts = splitter.split(List.of(new ExtractedPage(1, repeatLines(40))));

        assertThat(drafts).hasSizeGreaterThan(1);
        for (int index = 1; index < drafts.size(); index++) {
            String firstLineOfNext = drafts.get(index).content().lines().findFirst().orElseThrow();
            assertThat(drafts.get(index - 1).content().lines().toList()).contains(firstLineOfNext);
        }
    }

    @Test
    void shouldKeepPageNumbersOrderedAcrossPages() {
        List<DocumentChunkDraft> drafts = splitter.split(List.of(
                new ExtractedPage(1, repeatLines(20)),
                new ExtractedPage(2, repeatLines(20))
        ));

        assertThat(drafts).hasSizeGreaterThan(1);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.pageNumberStart()).isBetween(1, 2);
            assertThat(draft.pageNumberEnd()).isBetween(1, 2);
            assertThat(draft.pageNumberStart()).isLessThanOrEqualTo(draft.pageNumberEnd());
        });
        assertThat(drafts.get(0).pageNumberStart()).isEqualTo(1);
        assertThat(drafts.get(drafts.size() - 1).pageNumberEnd()).isEqualTo(2);
    }

    @Test
    void shouldHardSplitSingleLineLongerThanTarget() {
        String hugeLine = "alpha beta gamma delta epsilon ".repeat(60);

        List<DocumentChunkDraft> drafts = splitter.split(List.of(new ExtractedPage(1, hugeLine)));

        assertThat(drafts).hasSizeGreaterThan(1);
        assertThat(drafts).allSatisfy(draft ->
                assertThat(draft.tokenCount()).isLessThanOrEqualTo(TARGET_TOKENS * 3 / 2));
    }

    @Test
    void shouldNotLeaveAFragmentAsTheLastChunk() {
        List<DocumentChunkDraft> drafts = splitter.split(List.of(new ExtractedPage(1, repeatLines(40))));

        if (drafts.size() > 1) {
            assertThat(drafts.get(drafts.size() - 1).tokenCount()).isGreaterThanOrEqualTo(MIN_CHUNK_TOKENS);
        }
    }

    @Test
    void shouldNotContainBlankLinesInChunkContent() {
        List<DocumentChunkDraft> drafts = splitter.split(List.of(new ExtractedPage(1, "第一行\n\n\n第二行\n\n第三行")));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).content()).isEqualTo("第一行\n第二行\n第三行");
    }

    // 每行都不同，避免分块内容重复影响判断；单行控制在 10 个 token 以内，不会触发硬切
    private String repeatLines(int lineCount) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < lineCount; index++) {
            text.append("line ").append(index).append(" alpha beta gamma delta epsilon\n");
        }
        return text.toString();
    }
}
