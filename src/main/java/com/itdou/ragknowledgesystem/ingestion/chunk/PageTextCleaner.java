package com.itdou.ragknowledgesystem.ingestion.chunk;

import com.itdou.ragknowledgesystem.ingestion.parser.ExtractedPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 逐页文本清洗：压缩多余空白、剔除在多数页面上重复出现的页眉和页脚。
 */
@Component
@Slf4j
public class PageTextCleaner {

    // 连续的空格、制表符、全角空格压成一个半角空格
    private static final Pattern INLINE_WHITESPACE = Pattern.compile("[ \\t\\u3000]+");
    private static final int HEADER_FOOTER_SCAN_LINES = 3;

    public List<ExtractedPage> clean(List<ExtractedPage> pages, ChunkingProperties properties) {
        List<List<String>> pageLines = new ArrayList<>(pages.size());
        for (ExtractedPage page : pages) {
            pageLines.add(normalizeLines(page.text()));
        }

        int rawChars = pages.stream()
                .mapToInt(page -> page.text() == null ? 0 : page.text().length())
                .sum();

        Set<String> boilerplate = detectBoilerplate(pageLines, properties);

        List<ExtractedPage> cleaned = new ArrayList<>(pages.size());
        int cleanedChars = 0;
        for (int index = 0; index < pages.size(); index++) {
            List<String> kept = new ArrayList<>();
            for (String line : pageLines.get(index)) {
                // 空行不参与分块（分块时按行重新组织），页眉页脚直接丢弃
                if (!line.isEmpty() && !boilerplate.contains(compareKey(line))) {
                    kept.add(line);
                }
            }
            String text = String.join("\n", kept);
            cleanedChars += text.length();
            cleaned.add(new ExtractedPage(pages.get(index).pageNumber(), text));
        }

        // 清洗的汇总量记 debug：业务层已经有一条「文档解析完成」，那里不该再重复这一份数字
        log.debug("文本清洗完成 总页数={} 识别为重复页眉页脚 {} 种 字符数 {} -> {}",
                pages.size(), boilerplate.size(), rawChars, cleanedChars);
        return cleaned;
    }

    private List<String> normalizeLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.split("\r\n|\r|\n")) {
            lines.add(INLINE_WHITESPACE.matcher(rawLine).replaceAll(" ").strip());
        }
        return lines;
    }

    /**
     * 找出重复出现的页眉页脚。
     *
     * 只看每页顶部和底部的若干行，并且统计前把数字归一化，
     * 这样「第 3 页」和「第 4 页」会被视为同一行。
     */
    private Set<String> detectBoilerplate(List<List<String>> pageLines, ChunkingProperties properties) {
        if (pageLines.size() < properties.boilerplateMinPages()) {
            log.debug("页数 {} 少于阈值 {}，跳过页眉页脚剔除",
                    pageLines.size(), properties.boilerplateMinPages());
            return Set.of();
        }

        Map<String, Integer> occurrences = new HashMap<>();
        for (List<String> lines : pageLines) {
            // 同一页内重复出现的行只算一次，避免某页反复出现同一句话把统计带偏
            Set<String> candidates = new HashSet<>(headerAndFooterLines(lines));
            candidates.remove("");
            for (String candidate : candidates) {
                occurrences.merge(compareKey(candidate), 1, Integer::sum);
            }
        }

        int threshold = (int) Math.ceil(pageLines.size() * properties.boilerplatePageRatio());
        Set<String> boilerplate = new HashSet<>();
        occurrences.forEach((key, count) -> {
            if (count >= threshold) {
                boilerplate.add(key);
            }
        });

        if (!boilerplate.isEmpty()) {
            log.debug("页眉页脚判定阈值={}/{} 页，命中内容={}", threshold, pageLines.size(), boilerplate);
        }
        return boilerplate;
    }

    private List<String> headerAndFooterLines(List<String> lines) {
        List<String> result = new ArrayList<>();
        result.addAll(lines.subList(0, Math.min(HEADER_FOOTER_SCAN_LINES, lines.size())));
        result.addAll(lines.subList(Math.max(0, lines.size() - HEADER_FOOTER_SCAN_LINES), lines.size()));
        return result;
    }

    private String compareKey(String line) {
        return line.replaceAll("\\d+", "#").strip();
    }
}
