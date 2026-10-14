package com.itdou.ragknowledgesystem.chat.memory;

import java.util.List;
import java.util.UUID;

public interface ConversationMemory {

    // 读取会话历史，按时间正序返回（最早的在前面）
    List<ConversationTurn> load(UUID knowledgeBaseId, String conversationId);

    // 追加若干条消息，并把会话续期
    void append(UUID knowledgeBaseId, String conversationId, List<ConversationTurn> turns);
}
