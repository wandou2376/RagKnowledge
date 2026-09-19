package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.dto.ChatRequest;
import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;
import com.itdou.ragknowledgesystem.chat.dto.CitationResponse;
import com.itdou.ragknowledgesystem.chat.exception.AnswerGenerationException;
import com.itdou.ragknowledgesystem.chat.prompt.RagPrompt;
import com.itdou.ragknowledgesystem.chat.prompt.RagPromptBuilder;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.service.VectorSearchService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagChatServiceImplTest {

    @Mock
    private VectorSearchService vectorSearchService;

    @Mock
    private AnswerGenerator answerGenerator;

    private RagChatServiceImpl ragChatService;
    private UUID knowledgeBaseId;

    @BeforeEach
    void setUp() {
        // 提示词组装用真实实现，它的行为本来就是断言的一部分
        ragChatService = new RagChatServiceImpl(vectorSearchService, new RagPromptBuilder(), answerGenerator);
        knowledgeBaseId = UUID.randomUUID();
    }

    @Test
    void shouldAnswerAndMarkCitedSources() {
        givenSources(source("第一块内容"), source("第二块内容"));
        when(answerGenerator.generate(any())).thenReturn(
                new GeneratedAnswer("根据资料，共有两类[1]。", 120, 18, 138));

        ChatResponse response = ragChatService.ask(knowledgeBaseId, new ChatRequest("有几类？", null));

        assertThat(response.answer()).isEqualTo("根据资料，共有两类[1]。");
        assertThat(response.sources()).hasSize(2);
        assertThat(response.sources().get(0).index()).isEqualTo(1);
        assertThat(response.sources().get(0).cited()).isTrue();
        assertThat(response.sources().get(0).content()).isEqualTo("第一块内容");
        // 召回了但模型没引用，标记为 false，由前端决定是否展示
        assertThat(response.sources().get(1).index()).isEqualTo(2);
        assertThat(response.sources().get(1).cited()).isFalse();
        assertThat(response.totalTokens()).isEqualTo(138);
    }

    // 一条都没召回到时不该调用模型：既省一次费用，也避免模型脱离上下文自由发挥
    @Test
    void shouldSkipModelWhenNothingRetrieved() {
        givenSources();

        ChatResponse response = ragChatService.ask(knowledgeBaseId, new ChatRequest("无关问题", null));

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

        ChatResponse response = ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", null));

        assertThat(response.sources()).allSatisfy(citation -> assertThat(citation.cited()).isFalse());
    }

    @Test
    void shouldPassRetrievedSourcesAndQuestionIntoPrompt() {
        givenSources(source("参考资料原文"), source("第二段原文"));
        when(answerGenerator.generate(any())).thenReturn(new GeneratedAnswer("答案", null, null, null));

        ragChatService.ask(knowledgeBaseId, new ChatRequest("我的问题", null));

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

        ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", 3));

        // 编排层把提问转换成检索请求，topK 要原样透传
        verify(vectorSearchService).search(knowledgeBaseId, new SearchRequest("问题", 3));
    }

    @Test
    void shouldPropagateAnswerGenerationFailure() {
        givenSources(source("内容"));
        when(answerGenerator.generate(any()))
                .thenThrow(new AnswerGenerationException("调用聊天模型失败: timeout"));

        assertThatThrownBy(() -> ragChatService.ask(knowledgeBaseId, new ChatRequest("问题", null)))
                .isInstanceOf(AnswerGenerationException.class)
                .hasMessageContaining("timeout");
    }

    private void givenSources(SearchResultResponse... sources) {
        when(vectorSearchService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(sources));
    }

    private static SearchResultResponse source(String content) {
        return new SearchResultResponse(UUID.randomUUID(), UUID.randomUUID(), "guide.pdf", 0,
                content, 1, 2, 100, 0.88);
    }
}
