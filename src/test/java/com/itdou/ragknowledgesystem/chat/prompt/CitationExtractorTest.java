package com.itdou.ragknowledgesystem.chat.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationExtractorTest {

    @Test
    void shouldExtractSingleCitation() {
        assertThat(CitationExtractor.extract("数据库中存的是向量[1]。", 5)).containsExactly(1);
    }

    @Test
    void shouldExtractMultipleCitationsInOrder() {
        assertThat(CitationExtractor.extract("A[3] 和 B[1] 都提到了这点[2]。", 5))
                .containsExactly(1, 2, 3);
    }

    @Test
    void shouldDeduplicateRepeatedCitations() {
        assertThat(CitationExtractor.extract("这段[1]和那段[1]是同一个来源[1]。", 5)).containsExactly(1);
    }

    @Test
    void shouldSupportTwoDigitIndex() {
        assertThat(CitationExtractor.extract("见[10]。", 10)).containsExactly(10);
    }

    // 模型编造来源是「不编造」要求的典型违反，必须丢掉而不是当成有效引用
    @Test
    void shouldDropCitationsBeyondAvailableSources() {
        assertThat(CitationExtractor.extract("参考了[1]和[7]，还有[99]。", 5)).containsExactly(1);
    }

    @Test
    void shouldDropZeroIndex() {
        assertThat(CitationExtractor.extract("无效编号[0]和有效编号[2]。", 5)).containsExactly(2);
    }

    @Test
    void shouldReturnEmptyWhenAnswerHasNoCitation() {
        assertThat(CitationExtractor.extract("根据现有资料无法回答该问题。", 5)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForBlankAnswerOrNoSources() {
        assertThat(CitationExtractor.extract(null, 5)).isEmpty();
        assertThat(CitationExtractor.extract("   ", 5)).isEmpty();
        assertThat(CitationExtractor.extract("内容[1]", 0)).isEmpty();
        assertThat(CitationExtractor.extract("内容[1]", -1)).isEmpty();
    }

    // 方括号里的非数字、以及不带方括号的数字都不该被当成引用
    @Test
    void shouldIgnoreNonNumericBracketsAndBareNumbers() {
        assertThat(CitationExtractor.extract("2025 年的目录[注]里第 3 条这样写。", 5)).isEmpty();
    }
}
