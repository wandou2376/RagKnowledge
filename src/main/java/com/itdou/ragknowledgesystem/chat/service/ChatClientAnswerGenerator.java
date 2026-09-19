package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.exception.AnswerGenerationException;
import com.itdou.ragknowledgesystem.chat.prompt.RagPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring AI ChatClient 的实现。模型与连接配置沿用 spring.ai.openai.*（DeepSeek）。
 */
@Component
@Slf4j
public class ChatClientAnswerGenerator implements AnswerGenerator {

    private final ChatClient chatClient;

    public ChatClientAnswerGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public GeneratedAnswer generate(RagPrompt prompt) {
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(prompt.systemPrompt())
                    .user(prompt.userPrompt())
                    .call()
                    .chatResponse();
        } catch (RuntimeException exception) {
            log.error("调用聊天模型失败", exception);
            throw new AnswerGenerationException("调用聊天模型失败: " + exception.getMessage(), exception);
        }

        if (response == null || response.getResult() == null) {
            log.error("聊天模型返回了空结果");
            throw new AnswerGenerationException("聊天模型没有返回任何内容");
        }

        String content = response.getResult().getOutput().getText();
        log.debug("聊天模型返回 长度={} 内容预览={}", content == null ? 0 : content.length(),
                content == null ? null : abbreviate(content));

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new GeneratedAnswer(
                content,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens());
    }

    // 日志里不要把整段回答铺开，模型输出可能很长
    private static String abbreviate(String content) {
        String flattened = content.replace('\n', ' ');
        return flattened.length() <= 120 ? flattened : flattened.substring(0, 120) + "...";
    }
}
