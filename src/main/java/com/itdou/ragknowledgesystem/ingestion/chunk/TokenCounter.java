package com.itdou.ragknowledgesystem.ingestion.chunk;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * token 计数的统一入口。
 *
 * 直接复用 Spring AI 的 JTokkitTokenCountEstimator（内部是 jtokkit 的 CL100K_BASE 编码），
 * 不额外引入分词库，保证 document_chunk.token_count 和后续送进模型的上下文长度口径一致。
 */
class TokenCounter {

    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    int count(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return estimator.estimate(text);
    }

    /**
     * 把超过 maxTokens 的单行硬切成多段。
     *
     * 做法是在字符长度上二分，找出 token 数不超过上限的最长前缀，再对剩余部分重复。
     * 这样只需要分词库的计数能力，不用碰它内部的 token 列表 API。
     */
    List<String> hardSplit(String text, int maxTokens) {
        List<String> pieces = new ArrayList<>();
        String remaining = text.strip();

        while (count(remaining) > maxTokens) {
            int cut = longestPrefixWithinLimit(remaining, maxTokens);
            addIfNotBlank(pieces, remaining.substring(0, cut));
            // cut 至少为 1，保证每次循环都真的变短，不会死循环
            remaining = remaining.substring(cut).strip();
        }
        addIfNotBlank(pieces, remaining);
        return pieces;
    }

    private void addIfNotBlank(List<String> pieces, String candidate) {
        String stripped = candidate.strip();
        if (!stripped.isEmpty()) {
            pieces.add(stripped);
        }
    }

    private int longestPrefixWithinLimit(String text, int maxTokens) {
        int low = 1;
        int high = text.length();
        int best = 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (count(text.substring(0, middle)) <= maxTokens) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }
}
