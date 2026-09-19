package com.itdou.ragknowledgesystem.chat.prompt;

/**
 * 组装好的提示词。
 *
 * @param systemPrompt 系统提示词，约束模型只依据上下文作答、按编号引用
 * @param userPrompt   用户提示词，包含编号后的参考资料和原始问题
 */
public record RagPrompt(String systemPrompt, String userPrompt) {
}
