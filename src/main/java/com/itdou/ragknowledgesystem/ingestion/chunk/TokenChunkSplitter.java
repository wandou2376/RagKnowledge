package com.itdou.ragknowledgesystem.ingestion.chunk;

import com.itdou.ragknowledgesystem.ingestion.parser.ExtractedPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 把清洗后的页面文本切成固定大小、带少量重叠的 chunk。
 *
 * 思路：先把每页拆成行级片段，再把片段按目标 token 数打包成块，
 * 切块时从块尾回退若干行作为下一块的重叠内容，页码信息随片段一起带入块内。
 */
@Component
@Slf4j
public class TokenChunkSplitter {

    private final ChunkingProperties properties;
    private final TokenCounter tokenCounter = new TokenCounter();

    public TokenChunkSplitter(ChunkingProperties properties) {
        this.properties = properties;
    }

    public List<DocumentChunkDraft> split(List<ExtractedPage> pages) {
        List<TextSegment> segments = toSegments(pages);
        if (segments.isEmpty()) {
            log.warn("清洗后没有任何可用文本行，无法分块");
            return List.of();
        }

        List<List<TextSegment>> groups = groupByTargetSize(segments);
        mergeUndersizedTail(groups);
        List<DocumentChunkDraft> drafts = toDrafts(groups);

        log.debug("分块完成 文本片段={} 分块数={} 目标大小={} token 重叠={} token",
                segments.size(), drafts.size(), properties.targetTokens(), properties.overlapTokens());
        return drafts;
    }

    // 逐行展开成片段；单行本身就超过目标大小时先硬切，保证每个片段不会大于一块
    private List<TextSegment> toSegments(List<ExtractedPage> pages) {
        List<TextSegment> segments = new ArrayList<>();
        for (ExtractedPage page : pages) {
            for (String rawLine : page.text().split("\n")) {
                String line = rawLine.strip();
                if (line.isEmpty()) {
                    continue;
                }
                int lineTokens = tokenCounter.count(line);
                if (lineTokens <= properties.targetTokens()) {
                    segments.add(new TextSegment(page.pageNumber(), line, lineTokens));
                    continue;
                }
                log.debug("第 {} 页存在超长行 {} token，按 {} token 硬切",
                        page.pageNumber(), lineTokens, properties.targetTokens());
                for (String piece : tokenCounter.hardSplit(line, properties.targetTokens())) {
                    segments.add(new TextSegment(page.pageNumber(), piece, tokenCounter.count(piece)));
                }
            }
        }
        return segments;
    }

    private List<List<TextSegment>> groupByTargetSize(List<TextSegment> segments) {
        List<List<TextSegment>> groups = new ArrayList<>();
        List<TextSegment> current = new ArrayList<>();
        int currentTokens = 0;
        int carriedOver = 0;

        for (TextSegment segment : segments) {
            // 只有当前块含有新片段时才允许切块；否则重叠片段自己就把块填满了，会陷入死循环
            boolean hasNewContent = current.size() > carriedOver;
            if (hasNewContent && currentTokens + segment.tokenCount() > properties.targetTokens()) {
                groups.add(current);
                current = new ArrayList<>(overlapTail(current));
                carriedOver = current.size();
                currentTokens = sumTokens(current);
            }
            current.add(segment);
            currentTokens += segment.tokenCount();
        }

        // 结尾如果只剩下重叠片段就不再单独成块，这些内容上一块已经包含
        if (current.size() > carriedOver) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 从块尾往前取若干行，作为下一块开头要重叠的内容。
     *
     * 最多取到倒数第二行，保证下一块一定含有新内容；
     * 重叠量以「整行」为单位，不把一行从中间切开。
     */
    private List<TextSegment> overlapTail(List<TextSegment> group) {
        if (properties.overlapTokens() <= 0) {
            return List.of();
        }
        List<TextSegment> tail = new ArrayList<>();
        int tokens = 0;
        for (int index = group.size() - 1; index >= 1 && tokens < properties.overlapTokens(); index--) {
            TextSegment segment = group.get(index);
            if (tokens > 0 && tokens + segment.tokenCount() > properties.overlapTokens()) {
                break;
            }
            tail.add(0, segment);
            tokens += segment.tokenCount();
        }
        return tail;
    }

    // 尾块太短就并回上一块，避免最后剩下只有一两句话的碎片
    private void mergeUndersizedTail(List<List<TextSegment>> groups) {
        if (groups.size() < 2) {
            return;
        }
        List<TextSegment> tail = groups.get(groups.size() - 1);
        if (sumTokens(tail) >= properties.minChunkTokens()) {
            return;
        }
        log.debug("尾块只有 {} token，低于下限 {} token，并入上一块", sumTokens(tail), properties.minChunkTokens());
        groups.remove(groups.size() - 1);
        groups.get(groups.size() - 1).addAll(tail);
    }

    private List<DocumentChunkDraft> toDrafts(List<List<TextSegment>> groups) {
        List<DocumentChunkDraft> drafts = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            List<TextSegment> group = groups.get(index);
            String content = String.join("\n", group.stream().map(TextSegment::text).toList());
            Integer pageStart = group.get(0).pageNumber();
            Integer pageEnd = group.get(group.size() - 1).pageNumber();
            int tokenCount = tokenCounter.count(content);

            drafts.add(new DocumentChunkDraft(index, content, sha256(content), pageStart, pageEnd, tokenCount));
        }
        return drafts;
    }

    private int sumTokens(List<TextSegment> segments) {
        int total = 0;
        for (TextSegment segment : segments) {
            total += segment.tokenCount();
        }
        return total;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            log.error("当前 JVM 不支持 SHA-256，无法计算分块内容哈希", exception);
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }
}
