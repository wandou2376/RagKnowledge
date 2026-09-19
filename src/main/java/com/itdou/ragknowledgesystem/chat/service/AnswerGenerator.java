package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.prompt.RagPrompt;

/**
 * 调用聊天模型生成回答。
 *
 * 单独抽一层是为了让编排逻辑可测：编排服务依赖这个接口，
 * 单元测试里不需要去 mock ChatClient 那串流式调用链。
 */
public interface AnswerGenerator {

    GeneratedAnswer generate(RagPrompt prompt);
}
