package com.itdou.ragknowledgesystem.chat.dto;

import com.itdou.ragknowledgesystem.chat.memory.ChatRole;

/**
 * 会话历史里的一条消息。
 */
public record ChatMessageResponse(ChatRole role, String content) {
}
