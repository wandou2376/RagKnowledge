package com.itdou.ragknowledgesystem.chat.prompt;

import com.itdou.ragknowledgesystem.chat.memory.ConversationTurn;

import java.util.List;

/**
 * 组装好的提示词。
 *
 * @param systemPrompt 系统提示词，约束模型只依据上下文作答、按编号引用
 * @param history      历史对话，按时间正序。会以真实的 user / assistant 角色发出去，
 *                     而不是拼进 userPrompt 里——模型对角色消息的处理比一大段文本更可靠
 * @param userPrompt   用户提示词，包含编号后的参考资料和本次问题
 */
public record RagPrompt(String systemPrompt, List<ConversationTurn> history, String userPrompt) {

    public RagPrompt {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
