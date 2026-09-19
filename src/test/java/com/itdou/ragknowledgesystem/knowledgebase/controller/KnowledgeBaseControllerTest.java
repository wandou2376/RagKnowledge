package com.itdou.ragknowledgesystem.knowledgebase.controller;

import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBaseStatus;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库接口的响应契约测试。
 *
 * 用 @WebMvcTest 切片，只加载 web 层和 @RestControllerAdvice，不需要数据库。
 * 断言的重点是「信封格式」和「HTTP 状态码」这两件事，不是业务逻辑。
 */
@WebMvcTest(
        controllers = KnowledgeBaseController.class,
        properties = {"RAG_POSTGRES_PASSWORD=test-password", "DEEPSEEK_API_KEY=test-api-key"}
)
class KnowledgeBaseControllerTest {

    private static final String BASE_URL = "/api/knowledge-bases";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    void createShouldReturn201WithLocationAndWrappedBody() throws Exception {
        UUID id = UUID.randomUUID();
        given(knowledgeBaseService.create(any())).willReturn(response(id, "Java 知识库"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Java 知识库\",\"description\":\"Spring Boot 文档\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(BASE_URL + "/" + id)))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.name").value("Java 知识库"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void createShouldReturnValidationErrorEnvelopeWhenNameIsBlank() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("name")))
                .andExpect(content().string(containsString("\"data\":null")));
    }

    @Test
    void listShouldWrapItemsInEnvelope() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(knowledgeBaseService.list()).willReturn(List.of(response(first, "A"), response(second, "B")));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("A"))
                .andExpect(jsonPath("$.data[1].name").value("B"));
    }

    // 列表为空时 data 必须是 []，不能是 null，否则前端遍历会炸
    @Test
    void listShouldReturnEmptyArrayInsideEnvelope() throws Exception {
        given(knowledgeBaseService.list()).willReturn(List.of());

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getByIdShouldWrapInEnvelope() throws Exception {
        UUID id = UUID.randomUUID();
        given(knowledgeBaseService.getById(id)).willReturn(response(id, "Java 知识库"));

        mockMvc.perform(get(BASE_URL + "/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    @Test
    void getByIdShouldReturnNotFoundEnvelopeWhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(knowledgeBaseService.getById(id)).willThrow(new KnowledgeBaseNotFoundException(id));

        mockMvc.perform(get(BASE_URL + "/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString(id.toString())))
                .andExpect(content().string(containsString("\"data\":null")));
    }

    @Test
    void updateShouldWrapInEnvelope() throws Exception {
        UUID id = UUID.randomUUID();
        given(knowledgeBaseService.update(any(), any())).willReturn(response(id, "改名后"));

        mockMvc.perform(put(BASE_URL + "/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名后\",\"description\":\"新描述\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("改名后"));
    }

    // 删除是刻意的例外：204 且不带 body，前端拦截器需要处理这种情况
    @Test
    void deleteShouldReturn204WithoutBody() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete(BASE_URL + "/{id}", id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(knowledgeBaseService).delete(id);
    }

    @Test
    void malformedJsonShouldReturnEnvelope() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ 这不是合法 JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    void invalidUuidPathVariableShouldReturnEnvelope() throws Exception {
        mockMvc.perform(get(BASE_URL + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_TYPE"))
                .andExpect(jsonPath("$.message").value(containsString("id")));
    }

    @Test
    void unsupportedMethodShouldReturnEnvelope() throws Exception {
        mockMvc.perform(patch(BASE_URL + "/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // 不存在的路径不能落到 Spring 默认错误体，必须是信封
    @Test
    void unknownPathShouldReturnEnvelope() throws Exception {
        mockMvc.perform(get("/api/not-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
    }

    // 未预期异常的响应里不能泄漏内部异常信息
    @Test
    void unexpectedFailureShouldReturnInternalErrorEnvelopeWithoutLeakingDetail() throws Exception {
        UUID id = UUID.randomUUID();
        given(knowledgeBaseService.getById(id)).willThrow(new IllegalStateException("数据库连接串泄露风险"));

        mockMvc.perform(get(BASE_URL + "/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"))
                .andExpect(content().string(not(containsString("数据库连接串泄露风险"))));
    }

    private static KnowledgeBaseResponse response(UUID id, String name) {
        Instant now = Instant.now();
        return new KnowledgeBaseResponse(id, name, "描述", KnowledgeBaseStatus.ACTIVE, now, now);
    }
}
