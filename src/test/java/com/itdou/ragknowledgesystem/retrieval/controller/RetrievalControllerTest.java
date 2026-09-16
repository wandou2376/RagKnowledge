package com.itdou.ragknowledgesystem.retrieval.controller;

import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import com.itdou.ragknowledgesystem.retrieval.service.VectorSearchService;
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
 * 检索接口的响应契约测试，只加载 web 层，不依赖数据库，也不会真的调用 Embedding 服务。
 */
@WebMvcTest(
        controllers = RetrievalController.class,
        properties = {"RAG_POSTGRES_PASSWORD=test-password", "DEEPSEEK_API_KEY=test-api-key"}
)
class RetrievalControllerTest {

    private static final String SEARCH_URL = "/api/knowledge-bases/{id}/search";
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VectorSearchService vectorSearchService;

    @Test
    void searchShouldWrapHitsInEnvelope() throws Exception {
        given(vectorSearchService.search(eq(KNOWLEDGE_BASE_ID), any())).willReturn(List.of(hit()));

        mockMvc.perform(post(SEARCH_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"错误码 E-4012 是什么\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].documentId")
                        .value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.data[0].originalFileName").value("guide.pdf"))
                .andExpect(jsonPath("$.data[0].content").value("E-4012 表示数据库连接超时"))
                .andExpect(jsonPath("$.data[0].pageNumberStart").value(2))
                .andExpect(jsonPath("$.data[0].pageNumberEnd").value(3))
                .andExpect(jsonPath("$.data[0].score").value(0.87));
    }

    @Test
    void searchShouldReturnEmptyArrayInsideEnvelope() throws Exception {
        given(vectorSearchService.search(eq(KNOWLEDGE_BASE_ID), any())).willReturn(List.of());

        mockMvc.perform(post(SEARCH_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"没有命中\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void searchShouldReturnValidationErrorWhenQueryIsBlank() throws Exception {
        mockMvc.perform(post(SEARCH_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("query")));
    }

    @Test
    void searchShouldReturnValidationErrorWhenTopKIsOutOfRange() throws Exception {
        mockMvc.perform(post(SEARCH_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"问题\",\"topK\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("topK")));
    }

    @Test
    void searchShouldReturnNotFoundEnvelopeForUnknownKnowledgeBase() throws Exception {
        given(vectorSearchService.search(eq(KNOWLEDGE_BASE_ID), any()))
                .willThrow(new KnowledgeBaseNotFoundException(KNOWLEDGE_BASE_ID));

        mockMvc.perform(post(SEARCH_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"))
                .andExpect(content().string(containsString("\"data\":null")));
    }

    // Embedding 是外部依赖，它出问题用 503 而不是 500，调用方能区分「依赖不可用」和「服务端 bug」
    @Test
    void searchShouldReturnServiceUnavailableWhenEmbeddingFails() throws Exception {
        given(vectorSearchService.search(eq(KNOWLEDGE_BASE_ID), any()))
                .willThrow(new EmbeddingFailedException("未配置 Embedding 服务，请先设置环境变量 SILICONFLOW_API_KEY"));

        mockMvc.perform(post(SEARCH_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"问题\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("SILICONFLOW_API_KEY")));
    }

    @Test
    void searchShouldReturnEnvelopeForMalformedJson() throws Exception {
        mockMvc.perform(post(SEARCH_URL, KNOWLEDGE_BASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{不是合法 JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    void searchShouldReturnEnvelopeForInvalidUuidPath() throws Exception {
        mockMvc.perform(post("/api/knowledge-bases/not-a-uuid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"问题\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_TYPE"));
    }

    private static SearchResultResponse hit() {
        return new SearchResultResponse(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "guide.pdf",
                3,
                "E-4012 表示数据库连接超时",
                2,
                3,
                608,
                0.87
        );
    }
}
