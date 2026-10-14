package com.itdou.ragknowledgesystem.chat.service;

import com.itdou.ragknowledgesystem.chat.dto.ChatMessageResponse;
import com.itdou.ragknowledgesystem.chat.dto.ChatRequest;
import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;

import java.util.List;
import java.util.UUID;

public interface RagChatService {

    // 在指定知识库内回答问题，返回答案和来源引用
    ChatResponse ask(UUID knowledgeBaseId, ChatRequest request);

    // 查看某个会话的历史消息，按时间正序
    List<ChatMessageResponse> history(UUID knowledgeBaseId, String conversationId);
}
