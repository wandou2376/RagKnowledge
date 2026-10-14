package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.dto.ChatMessageResponse;
import com.itdou.ragknowledgesystem.chat.dto.ChatRequest;
import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;
import com.itdou.ragknowledgesystem.chat.exception.AnswerGenerationException;
import com.itdou.ragknowledgesystem.chat.memory.ChatRole;
import com.itdou.ragknowledgesystem.chat.memory.ConversationMemory;
import com.itdou.ragknowledgesystem.chat.memory.ConversationTurn;
import com.itdou.ragknowledgesystem.chat.prompt.RagPrompt;
import com.itdou.ragknowledgesystem.chat.prompt.RagPromptBuilder;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.service.ChunkSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagChatServiceImplTest {

    private static final String CONVERSATION_ID = "conv-2026-01";

    @Mock
    private ChunkSearchService chunkSearchService;

    @Mock
    private AnswerGenerator answerGenerator;

    @Mock
    private ConversationMemory conversationMemory;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private RagChatServiceImpl ragChatService;
    private UUID knowledgeBaseId;

    @BeforeEach
    void setUp() {
        // 提示词组装用真实实现，它的行为本来就是断言的一部分
        ragChatService = new RagChatServiceImpl(
                chunkSearchService, new RagPromptBuilder(), answerGenerator,
                conversationMemory, knowledgeBaseRepository);
        knowledgeBaseId = UUID.randomUUID();
    }

    @Test
    void shouldAnswerAndMarkCitedSources() {
        givenSources(source("第一块内容"), source("第二块内容"));
        when(answerGenerator.generate(any())).thenReturn(
                new GeneratedAnswer("根据资料，共有两类[1]。", 120, 18, 138));

        ChatResponse response = ragChatService.ask(knowledgeBaseId, new ChatRequest("有几类？", null, null));

        assertThat(response.conversationId()).isNull();
        assertThat(response.answer()).isEqualTo("根据资料，共有两类[1]。");
        assertThat(response.sources()).hasSize(2);
        assertThat(response.sources().get(0).index()).isEqualTo(1);
        assertThat(response.sources().get(0).cited()).isTrue();
        // 召回了但模型没引用，标记为 false，由前端决定是否展示
        assertThat(response.sources().get(1).index()).isEqualTo(2);
        assertThat(response.sources().get(1).cited()).isFalse();
        assertThat(response.totalTokens()).isEqualTo(138);
    }

    // 一条都没召回到时不该调用模型：既省一次费用，也避免模型脱离上下文自由发挥
    @Test
    void shouldSkipModelWhenNothingRetrieved() {
        givenSources();

        ChatResponse response = ragChatService.ask(knowledgeBaseId, new ChatRequest("无关问题", null, null));

        assertThat(response.answer()).contains("无法回答");
        assertThat(response.sources()).isEmpty();
        assertThat(response.totalTokens()).isNull();
        verify(answerGenerator, never()).generate(any());
    }

    // 模型编造编号时不能把它当成有效引用，全部标记为未引用
    @Test
    void shouldDropFabricatedCitationFromAnswer() {
        givenSources(source("内容A"), source("内容B"));
        when(answerGenerator.generate(any())).thenReturn(
                new GeneratedAnswer("这个结论来自 [9]。", null, null, null));

        ChatResponse response = ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", null, null));

        assertThat(response.sources()).allSatisfy(citation -> assertThat(citation.cited()).isFalse());
    }

    @Test
    void shouldPassRetrievedSourcesAndQuestionIntoPrompt() {
        givenSources(source("参考资料原文"), source("第二段原文"));
        when(answerGenerator.generate(any())).thenReturn(new GeneratedAnswer("答案", null, null, null));

        ragChatService.ask(knowledgeBaseId, new ChatRequest("我的问题", null, null));

        ArgumentCaptor<RagPrompt> captor = ArgumentCaptor.forClass(RagPrompt.class);
        verify(answerGenerator).generate(captor.capture());
        assertThat(captor.getValue().userPrompt())
                .contains("参考资料原文")
                .contains("第二段原文")
                .contains("我的问题");
    }

    @Test
    void shouldForwardTopKToSearch() {
        givenSources(source("内容"));
        when(answerGenerator.generate(any())).thenReturn(new GeneratedAnswer("答案", null, null, null));

        ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", 3, null));

        // 编排层把提问转换成检索请求，topK 要原样透传
        verify(chunkSearchService).search(knowledgeBaseId, new SearchRequest("问题", 3, null));
    }

    @Test
    void shouldPropagateAnswerGenerationFailure() {
        givenSources(source("内容"));
        when(answerGenerator.generate(any()))
                .thenThrow(new AnswerGenerationException("调用聊天模型失败: timeout"));

        assertThatThrownBy(() -> ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", null, null)))
                .isInstanceOf(AnswerGenerationException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void shouldLoadHistoryAndPassItToModel() {
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("上一个问题"),
                ConversationTurn.assistant("上一个回答"));
        when(conversationMemory.load(knowledgeBaseId, CONVERSATION_ID)).thenReturn(history);
        givenSources(source("内容"));
        when(answerGenerator.generate(any())).thenReturn(new GeneratedAnswer("答案[1]", null, null, null));

        ChatResponse response = ragChatService.ask(
                knowledgeBaseId, new ChatRequest("追问的问题", null, CONVERSATION_ID));

        ArgumentCaptor<RagPrompt> captor = ArgumentCaptor.forClass(RagPrompt.class);
        verify(answerGenerator).generate(captor.capture());
        assertThat(captor.getValue().history()).containsExactlyElementsOf(history);
        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
    }

    @Test
    void shouldAppendBothTurnsAfterSuccessfulAnswer() {
        givenSources(source("内容"));
        when(answerGenerator.generate(any())).thenReturn(new GeneratedAnswer("回答内容", null, null, null));

        ragChatService.ask(knowledgeBaseId, new ChatRequest("提问内容", null, CONVERSATION_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationMemory).append(eq(knowledgeBaseId), eq(CONVERSATION_ID), captor.capture());
        assertThat(captor.getValue()).containsExactly(
                new ConversationTurn(ChatRole.USER, "提问内容"),
                new ConversationTurn(ChatRole.ASSISTANT, "回答内容"));
    }

    // 没带 conversationId 就是单轮问答，不该读写任何历史
    @Test
    void shouldNotTouchMemoryWhenConversationIdIsAbsent() {
        givenSources(source("内容"));
        when(answerGenerator.generate(any())).thenReturn(new GeneratedAnswer("答案", null, null, null));

        ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", null, null));
        // 空字符串等同于没传
        ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", null, "   "));

        verify(conversationMemory, never()).load(any(), any());
        verify(conversationMemory, never()).append(any(), any(), anyList());
    }

    // 召回到的分块为空时返回的是固定话术，这一轮同样要记进历史，
    // 否则下一轮追问时模型不知道上一轮已经被拒答过
    @Test
    void shouldRecordRefusalIntoHistory() {
        givenSources();

        ragChatService.ask(knowledgeBaseId, new ChatRequest("答不出的问题", null, CONVERSATION_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationMemory).append(eq(knowledgeBaseId), eq(CONVERSATION_ID), captor.capture());
        assertThat(captor.getValue().get(1).role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(captor.getValue().get(1).content()).contains("无法回答");
    }

    // 会话记忆是增强能力，Redis 不可用时降级为「没有历史」，问答本身仍要能正常返回
    @Test
    void shouldStillAnswerWhenMemoryReturnsEmpty() {
        when(conversationMemory.load(knowledgeBaseId, CONVERSATION_ID)).thenReturn(List.of());
        givenSources(source("内容"));
        when(answerGenerator.generate(any())).thenReturn(new GeneratedAnswer("答案", null, null, null));

        ChatResponse response = ragChatService.ask(
                knowledgeBaseId, new ChatRequest("问题", null, CONVERSATION_ID));

        assertThat(response.answer()).isEqualTo("答案");
    }

    @Test
    void shouldReturnHistoryForKnownKnowledgeBase() {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(conversationMemory.load(knowledgeBaseId, CONVERSATION_ID)).thenReturn(List.of(
                ConversationTurn.user("问题一"),
                ConversationTurn.assistant("回答一")));

        List<ChatMessageResponse> history = ragChatService.history(knowledgeBaseId, CONVERSATION_ID);

        assertThat(history).containsExactly(
                new ChatMessageResponse(ChatRole.USER, "问题一"),
                new ChatMessageResponse(ChatRole.ASSISTANT, "回答一"));
    }

    @Test
    void shouldRejectHistoryForUnknownKnowledgeBase() {
        UUID unknownId = UUID.randomUUID();
        when(knowledgeBaseRepository.existsById(unknownId)).thenReturn(false);

        assertThatThrownBy(() -> ragChatService.history(unknownId, CONVERSATION_ID))
                .isInstanceOf(KnowledgeBaseNotFoundException.class);

        verify(conversationMemory, never()).load(any(), any());
    }

    private void givenSources(SearchResultResponse... sources) {
        when(chunkSearchService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(sources));
    }

    private static SearchResultResponse source(String content) {
        return new SearchResultResponse(UUID.randomUUID(), UUID.randomUUID(), "guide.pdf", 0,
                content, 1, 2, 100, 0.88, 0.88, null);
    }
}
