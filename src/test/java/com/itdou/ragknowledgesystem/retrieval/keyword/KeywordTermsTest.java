package com.itdou.ragknowledgesystem.retrieval.keyword;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordTermsTest {

    @Test
    void shouldSplitChineseIntoBigramsAndKeepWholeRun() {
        Set<String> terms = KeywordTerms.extract("机器人");

        // 三字词：整段 + 两个二字窗口
        assertThat(terms).containsExactlyInAnyOrder("机器人", "机器", "器人");
    }

    @Test
    void shouldKeepAlphanumericTokens() {
        Set<String> terms = KeywordTerms.extract("错误码 E-4012 是什么");

        assertThat(terms).contains("4012", "错误码", "错误", "误码", "什么");
        // 单字符的 E 会被丢弃，但「4012」已经足以匹配到这个错误码
        assertThat(terms).doesNotContain("E");
    }

    @Test
    void shouldDropSingleCharacterTokens() {
        // 单字太泛，命中率低还引入噪音，直接丢弃
        assertThat(KeywordTerms.extract("的 了 是")).isEmpty();
    }

    @Test
    void shouldDeduplicateRepeatedRuns() {
        Set<String> terms = KeywordTerms.extract("期刊 期刊 中文期刊");

        assertThat(terms).containsExactlyInAnyOrder("期刊", "中文", "文期", "中文期刊");
    }

    // 词元用 | 拼进 SQL，% 和 _ 是 LIKE 通配符，都不能出现在词元里
    @Test
    void shouldStripCharactersUnsafeForLikeAndSeparator() {
        Set<String> terms = KeywordTerms.extract("100%_测试|注入");

        assertThat(terms).noneMatch(term -> term.contains("%") || term.contains("_") || term.contains("|"));
        assertThat(terms).contains("测试", "注入");
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        assertThat(KeywordTerms.extract(null)).isEmpty();
        assertThat(KeywordTerms.extract("   ")).isEmpty();
        assertThat(KeywordTerms.extract("！？。，")).isEmpty();
    }

    @Test
    void shouldHandleLongNaturalLanguageQuestion() {
        Set<String> terms = KeywordTerms.extract("T3 类里有哪些中文期刊？");

        // 关键的中文二字词要能抽出来，这是不用分词器也能匹配到内容的前提
        assertThat(terms).contains("中文", "期刊", "T3");
    }
}
