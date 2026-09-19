package com.itdou.ragknowledgesystem.chat.prompt;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型回答里抽取引用编号。
 */
@Slf4j
public final class CitationExtractor {

    // 只认 [1] 这种方括号数字，且限制位数避免把年份之类的数字当引用
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private CitationExtractor() {
    }

    /**
     * 抽取回答里出现过的引用编号，去重并按升序返回。
     *
     * @param answer   模型回答
     * @param maxIndex 本次提供的参考资料条数。超出这个范围的编号说明模型在编造来源，
     *                 直接丢弃并记一条 warn——这是「不编造来源」这条要求的技术兜底。
     */
    public static List<Integer> extract(String answer, int maxIndex) {
        if (answer == null || answer.isBlank() || maxIndex <= 0) {
            return List.of();
        }

        Set<Integer> cited = new TreeSet<>();
        Set<Integer> hallucinated = new TreeSet<>();

        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= maxIndex) {
                cited.add(index);
            } else {
                hallucinated.add(index);
            }
        }

        if (!hallucinated.isEmpty()) {
            log.warn("模型引用了不存在的参考资料编号，已丢弃 {}（本次可用范围 1-{}）", hallucinated, maxIndex);
        }
        return List.copyOf(cited);
    }
}
