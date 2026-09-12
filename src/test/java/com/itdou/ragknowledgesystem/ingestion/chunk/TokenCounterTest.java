package com.itdou.ragknowledgesystem.ingestion.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    private final TokenCounter tokenCounter = new TokenCounter();

    @Test
    void countShouldReturnZeroForBlankText() {
        assertThat(tokenCounter.count(null)).isZero();
        assertThat(tokenCounter.count("")).isZero();
        assertThat(tokenCounter.count("   \n  ")).isZero();
    }

    @Test
    void countShouldGrowWithTextLength() {
        assertThat(tokenCounter.count("hello world")).isGreaterThan(0);
        assertThat(tokenCounter.count("hello world hello world hello world"))
                .isGreaterThan(tokenCounter.count("hello world"));
    }

    @Test
    void hardSplitShouldSplitLongTextIntoPiecesWithinLimit() {
        String longLine = "alpha beta gamma delta epsilon ".repeat(200);

        List<String> pieces = tokenCounter.hardSplit(longLine, 100);

        assertThat(pieces).hasSizeGreaterThan(1);
        for (String piece : pieces) {
            assertThat(tokenCounter.count(piece)).isLessThanOrEqualTo(100);
            assertThat(piece).isNotBlank();
        }
    }

    @Test
    void hardSplitShouldKeepShortTextIntact() {
        assertThat(tokenCounter.hardSplit("短文本", 100)).containsExactly("短文本");
    }

    // 每段都必须比原文短，否则 hardSplit 里的循环永远退不出来
    @Test
    void hardSplitShouldMakeProgressEvenWhenLimitIsTiny() {
        List<String> pieces = tokenCounter.hardSplit("alpha beta gamma", 1);

        assertThat(pieces).isNotEmpty();
        assertThat(pieces).allSatisfy(piece -> assertThat(piece).isNotBlank());
    }
}
