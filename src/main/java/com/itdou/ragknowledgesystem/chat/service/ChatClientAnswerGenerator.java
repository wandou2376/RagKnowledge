package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.exception.AnswerGenerationException;
import com.itdou.ragknowledgesystem.chat.memory.ChatRole;
import com.itdou.ragknowledgesystem.chat.memory.ConversationTurn;
import com.itdou.ragknowledgesystem.chat.prompt.RagPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
                    .messages(toMessages(prompt))
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
        log.debug("聊天模型返回 长度={} 历史轮数={} 内容预览={}",
                content == null ? 0 : content.length(), prompt.history().size(), abbreviate(content));

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new GeneratedAnswer(
                content,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens());
    }

    /*
     * 自己拼完整的消息列表，而不是用 system() / user() 那些链式方法，
     * 这样 system -> 历史 -> 本次提问 的顺序是确定的，不依赖框架的拼装细节。
     */
    private static List<Message> toMessages(RagPrompt prompt) {
        List<Message> messages = new ArrayList<>(prompt.history().size() + 2);
        messages.add(new SystemMessage(prompt.systemPrompt()));
        for (ConversationTurn turn : prompt.history()) {
            messages.add(turn.role() == ChatRole.USER
                    ? new UserMessage(turn.content())
                    : new AssistantMessage(turn.content()));
        }
        messages.add(new UserMessage(prompt.userPrompt()));
        return messages;
    }

    // 日志里不要把整段回答铺开，模型输出可能很长
    private static String abbreviate(String content) {
        if (content == null) {
            return null;
        }
        String flattened = content.replace('\n', ' ');
        return flattened.length() <= 120 ? flattened : flattened.substring(0, 120) + "...";
    }
}
