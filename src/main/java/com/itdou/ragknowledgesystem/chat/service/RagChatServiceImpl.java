package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.dto.ChatRequest;
import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;
import com.itdou.ragknowledgesystem.chat.dto.CitationResponse;
import com.itdou.ragknowledgesystem.chat.prompt.CitationExtractor;
import com.itdou.ragknowledgesystem.chat.prompt.RagPrompt;
import com.itdou.ragknowledgesystem.chat.prompt.RagPromptBuilder;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.service.VectorSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库问答的编排：检索 -> 组装提示词 -> 调用模型 -> 把引用编号映射回分块。
 *
 * 这里没有数据库写入，所以不需要事务；调用模型是阻塞的外部请求，
 * 更不能包在事务里。
 */
@Service
@Slf4j
public class RagChatServiceImpl implements RagChatService {

    // 一条参考资料都没召回到时的固定回答。此时不调用模型：既省一次费用，也避免模型脱离上下文自由发挥
    private static final String NO_CONTEXT_ANSWER = "根据现有资料无法回答该问题。";

    private final VectorSearchService vectorSearchService;
    private final RagPromptBuilder promptBuilder;
    private final AnswerGenerator answerGenerator;

    public RagChatServiceImpl(
            VectorSearchService vectorSearchService,
            RagPromptBuilder promptBuilder,
            AnswerGenerator answerGenerator
    ) {
        this.vectorSearchService = vectorSearchService;
        this.promptBuilder = promptBuilder;
        this.answerGenerator = answerGenerator;
    }

    @Override
    public ChatResponse ask(UUID knowledgeBaseId, ChatRequest request) {
        long startedAt = System.currentTimeMillis();
        log.info("收到提问 knowledgeBaseId={} topK={} 问题长度={}",
                knowledgeBaseId, request.topK(), request.question().length());
        // 问题原文放 debug：它是用户输入，不要在 INFO 级别的日志里重复铺开
        log.debug("提问原文: {}", request.question());

        List<SearchResultResponse> sources = vectorSearchService.search(
                knowledgeBaseId, new SearchRequest(request.question(), request.topK()));

        if (sources.isEmpty()) {
            log.warn("知识库中没有检索到相关内容，跳过模型调用 knowledgeBaseId={}", knowledgeBaseId);
            return new ChatResponse(NO_CONTEXT_ANSWER, List.of(), null, null, null);
        }

        RagPrompt prompt = promptBuilder.build(request.question(), sources);
        GeneratedAnswer generated = answerGenerator.generate(prompt);

        List<Integer> citedIndices = CitationExtractor.extract(generated.content(), sources.size());
        List<CitationResponse> citations = toCitations(sources, citedIndices);

        log.info("提问处理完成 knowledgeBaseId={} 召回={} 被引用={} 耗时={}ms",
                knowledgeBaseId, sources.size(), citedIndices.size(), System.currentTimeMillis() - startedAt);

        return new ChatResponse(
                generated.content(),
                citations,
                generated.promptTokens(),
                generated.completionTokens(),
                generated.totalTokens());
    }

    // 编号从 1 开始，与提示词里给模型的编号一致
    private static List<CitationResponse> toCitations(
            List<SearchResultResponse> sources,
            List<Integer> citedIndices
    ) {
        Set<Integer> cited = new HashSet<>(citedIndices);
        return java.util.stream.IntStream.range(0, sources.size())
                .mapToObj(position -> {
                    int index = position + 1;
                    SearchResultResponse source = sources.get(position);
                    return new CitationResponse(
                            index,
                            cited.contains(index),
                            source.chunkId(),
                            source.documentId(),
                            source.originalFileName(),
                            source.content(),
                            source.pageNumberStart(),
                            source.pageNumberEnd(),
                            source.score());
                })
                .toList();
    }
}
