package com.itdou.ragknowledgesystem.chat.memory;

/**
 * 一轮会话里的一条消息。
 *
 * 这个类型会被序列化成 JSON 存进 Redis，改字段名等于改存储格式，
 * 老数据会解析不出来（解析失败时按跳过处理，不会让整个问答挂掉）。
 */
public record ConversationTurn(ChatRole role, String content) {

    public static ConversationTurn user(String content) {
        return new ConversationTurn(ChatRole.USER, content);
    }

    public static ConversationTurn assistant(String content) {
        return new ConversationTurn(ChatRole.ASSISTANT, content);
    }
}
