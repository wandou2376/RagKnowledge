package com.itdou.ragknowledgesystem.chat.service;

/**
 * 模型返回的回答及其 token 消耗。
 *
 * token 数用于统计成本，供应商没返回时为 null。
 */
public record GeneratedAnswer(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
