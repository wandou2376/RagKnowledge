package com.itdou.ragknowledgesystem.chat.controller;

import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;
import com.itdou.ragknowledgesystem.chat.dto.CitationResponse;
import com.itdou.ragknowledgesystem.chat.exception.AnswerGenerationException;
import com.itdou.ragknowledgesystem.chat.service.RagChatService;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答接口的响应契约测试，只加载 web 层，不依赖数据库，也不会真的调用模型。
 */
@WebMvcTest(
        controllers = RagChatController.class,
        properties = {"RAG_POSTGRES_PASSWORD=test-password", "DEEPSEEK_API_KEY=test-api-key"}
)
class RagChatControllerTest {

    private static final String CHAT_URL = "/api/knowledge-bases/{id}/chat";
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagChatService ragChatService;

    @Test
    void chatShouldWrapAnswerAndCitationsInEnvelope() throws Exception {
        given(ragChatService.ask(eq(KNOWLEDGE_BASE_ID), any())).willReturn(new ChatResponse(
                "共有两类期刊[1]。", List.of(cited(), uncited()), 120, 18, 138));

        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"有哪些期刊？\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.answer").value("共有两类期刊[1]。"))
                .andExpect(jsonPath("$.data.sources.length()").value(2))
                .andExpect(jsonPath("$.data.sources[0].index").value(1))
                .andExpect(jsonPath("$.data.sources[0].cited").value(true))
                .andExpect(jsonPath("$.data.sources[0].originalFileName").value("guide.pdf"))
                .andExpect(jsonPath("$.data.sources[0].pageNumberStart").value(1))
                .andExpect(jsonPath("$.data.sources[0].pageNumberEnd").value(3))
                .andExpect(jsonPath("$.data.sources[1].index").value(2))
                .andExpect(jsonPath("$.data.sources[1].cited").value(false))
                .andExpect(jsonPath("$.data.totalTokens").value(138));
    }

    @Test
    void chatShouldReturnEmptySourcesWhenNothingRetrieved() throws Exception {
        given(ragChatService.ask(eq(KNOWLEDGE_BASE_ID), any())).willReturn(
                new ChatResponse("根据现有资料无法回答该问题。", List.of(), null, null, null));

        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"无关问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void chatShouldReturnValidationErrorWhenQuestionIsBlank() throws Exception {
        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("question")));
    }

    @Test
    void chatShouldReturnValidationErrorWhenTopKIsOutOfRange() throws Exception {
        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\",\"topK\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("topK")));
    }

    @Test
    void chatShouldReturnNotFoundEnvelopeForUnknownKnowledgeBase() throws Exception {
        given(ragChatService.ask(eq(KNOWLEDGE_BASE_ID), any()))
                .willThrow(new KnowledgeBaseNotFoundException(KNOWLEDGE_BASE_ID));

        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"))
                .andExpect(content().string(containsString("\"data\":null")));
    }

    // 聊天模型不可用属于外部依赖问题，用 503 与 500 服务端故障区分开
    @Test
    void chatShouldReturnServiceUnavailableWhenModelFails() throws Exception {
        given(ragChatService.ask(eq(KNOWLEDGE_BASE_ID), any()))
                .willThrow(new AnswerGenerationException("调用聊天模型失败: timeout"));

        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANSWER_GENERATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("timeout")));
    }

    // 检索时要把问题向量化，Embedding 不可用同样应该以 503 暴露
    @Test
    void chatShouldReturnServiceUnavailableWhenEmbeddingFails() throws Exception {
        given(ragChatService.ask(eq(KNOWLEDGE_BASE_ID), any()))
                .willThrow(new EmbeddingFailedException("未配置 Embedding 服务，请先设置环境变量 SILICONFLOW_API_KEY"));

        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_FAILED"));
    }

    @Test
    void chatShouldReturnEnvelopeForMalformedJson() throws Exception {
        mockMvc.perform(post(CHAT_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{不是合法 JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    private static CitationResponse cited() {
        return new CitationResponse(1, true,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "guide.pdf", "第一块内容", 1, 3, 0.87);
    }

    private static CitationResponse uncited() {
        return new CitationResponse(2, false,
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "guide.pdf", "第二块内容", 4, 5, 0.72);
    }
}
