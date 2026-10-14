package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.dto.ChatMessageResponse;
import com.itdou.ragknowledgesystem.chat.dto.ChatRequest;
import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;
import com.itdou.ragknowledgesystem.chat.dto.CitationResponse;
import com.itdou.ragknowledgesystem.chat.memory.ConversationMemory;
import com.itdou.ragknowledgesystem.chat.memory.ConversationTurn;
import com.itdou.ragknowledgesystem.chat.prompt.CitationExtractor;
import com.itdou.ragknowledgesystem.chat.prompt.RagPrompt;
import com.itdou.ragknowledgesystem.chat.prompt.RagPromptBuilder;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.service.ChunkSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * 知识库问答的编排：读取历史 -> 检索 -> 组装提示词 -> 调用模型 -> 映射引用 -> 写回历史。
 *
 * 这里没有数据库写入，所以不需要事务；调用模型是阻塞的外部请求，更不能包在事务里。
 * 会话记忆走 Redis，不可用时由 ConversationMemory 自行降级，不影响本次回答。
 */
@Service
@Slf4j
public class RagChatServiceImpl implements RagChatService {

    // 一条参考资料都没召回到时的固定回答。此时不调用模型：既省一次费用，也避免模型脱离上下文自由发挥
    private static final String NO_CONTEXT_ANSWER = "根据现有资料无法回答该问题。";

    private final ChunkSearchService chunkSearchService;
    private final RagPromptBuilder promptBuilder;
    private final AnswerGenerator answerGenerator;
    private final ConversationMemory conversationMemory;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public RagChatServiceImpl(
            ChunkSearchService chunkSearchService,
            RagPromptBuilder promptBuilder,
            AnswerGenerator answerGenerator,
            ConversationMemory conversationMemory,
            KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        this.chunkSearchService = chunkSearchService;
        this.promptBuilder = promptBuilder;
        this.answerGenerator = answerGenerator;
        this.conversationMemory = conversationMemory;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    @Override
    public ChatResponse ask(UUID knowledgeBaseId, ChatRequest request) {
        long startedAt = System.currentTimeMillis();
        // 空字符串等同于没传，统一成 null，后面只判断一次
        String conversationId = StringUtils.hasText(request.conversationId()) ? request.conversationId() : null;

        log.info("收到提问 knowledgeBaseId={} conversationId={} topK={} 问题长度={}",
                knowledgeBaseId, conversationId, request.topK(), request.question().length());
        log.debug("提问原文: {}", request.question());

        List<ConversationTurn> history = conversationId == null
                ? List.of()
                : conversationMemory.load(knowledgeBaseId, conversationId);
        if (!history.isEmpty()) {
            log.debug("本次带上 {} 条历史消息 conversationId={}", history.size(), conversationId);
        }

        List<SearchResultResponse> sources = chunkSearchService.search(
                knowledgeBaseId, new SearchRequest(request.question(), request.topK(), null));

        String answer;
        List<CitationResponse> citations = List.of();
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        int citedCount = 0;

        if (sources.isEmpty()) {
            log.warn("知识库中没有检索到相关内容，跳过模型调用 knowledgeBaseId={}", knowledgeBaseId);
            answer = NO_CONTEXT_ANSWER;
        } else {
            RagPrompt prompt = promptBuilder.build(request.question(), sources, history);
            GeneratedAnswer generated = answerGenerator.generate(prompt);

            answer = generated.content();
            List<Integer> citedIndices = CitationExtractor.extract(answer, sources.size());
            citations = toCitations(sources, citedIndices);
            citedCount = citedIndices.size();
            promptTokens = generated.promptTokens();
            completionTokens = generated.completionTokens();
            totalTokens = generated.totalTokens();
        }

        // 模型调用成功后才写历史：调用失败的一轮不该被记住，否则下一轮会看到一条没有回答的提问
        if (conversationId != null) {
            conversationMemory.append(knowledgeBaseId, conversationId,
                    List.of(ConversationTurn.user(request.question()), ConversationTurn.assistant(answer)));
        }

        log.info("提问处理完成 knowledgeBaseId={} conversationId={} 召回={} 被引用={} 耗时={}ms",
                knowledgeBaseId, conversationId, sources.size(), citedCount, System.currentTimeMillis() - startedAt);

        return new ChatResponse(conversationId, answer, citations, promptTokens, completionTokens, totalTokens);
    }

    @Override
    public List<ChatMessageResponse> history(UUID knowledgeBaseId, String conversationId) {
        log.debug("查询会话历史 knowledgeBaseId={} conversationId={}", knowledgeBaseId, conversationId);

        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }

        List<ChatMessageResponse> messages = conversationMemory.load(knowledgeBaseId, conversationId)
                .stream()
                .map(turn -> new ChatMessageResponse(turn.role(), turn.content()))
                .toList();

        log.info("查询会话历史完成 knowledgeBaseId={} conversationId={} 条数={}",
                knowledgeBaseId, conversationId, messages.size());
        return messages;
    }

    // 编号从 1 开始，与提示词里给模型的编号一致
    private static List<CitationResponse> toCitations(
            List<SearchResultResponse> sources,
            List<Integer> citedIndices
    ) {
        Set<Integer> cited = new HashSet<>(citedIndices);
        return IntStream.range(0, sources.size())
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
