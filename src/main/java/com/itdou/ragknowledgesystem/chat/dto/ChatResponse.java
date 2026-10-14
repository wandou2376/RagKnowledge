package com.itdou.ragknowledgesystem.chat.dto;

import java.util.List;

/**
 * 知识库问答的响应。
 *
 * @param conversationId 本次使用的会话标识。请求里没带时为 null，表示这是一次单轮问答
 * @param answer         模型生成的回答，引用处带 [n] 标记
 * @param sources        本次使用的参考资料，带 cited 标记标明哪几条被真正引用
 * @param promptTokens / completionTokens / totalTokens token 消耗，供应商未返回时为 null
 */
public record ChatResponse(
        String conversationId,
        String answer,
        List<CitationResponse> sources,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
