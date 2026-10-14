package com.itdou.ragknowledgesystem.chat.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis List 的会话记忆。
 *
 * 存储结构：key 是 rag:chat:{知识库ID}:{会话ID}，value 是消息 JSON 的列表，
 * 用 RPUSH 追加、LTRIM 只留最新的若干条，所以天然是一个有上限的滑动窗口。
 *
 * Redis 不可用时**降级为「没有历史」而不是抛错**：会话记忆是增强能力，
 * 不该因为它挂掉就让整个问答失败。降级会记 warn，便于发现。
 */
@Component
@Slf4j
public class RedisConversationMemory implements ConversationMemory {

    private static final String KEY_PREFIX = "rag:chat:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMemoryProperties properties;

    public RedisConversationMemory(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ChatMemoryProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<ConversationTurn> load(UUID knowledgeBaseId, String conversationId) {
        String key = key(knowledgeBaseId, conversationId);
        try {
            List<String> rawTurns = redisTemplate.opsForList().range(key, 0, -1);
            if (rawTurns == null || rawTurns.isEmpty()) {
                return List.of();
            }

            List<ConversationTurn> turns = new ArrayList<>(rawTurns.size());
            for (String rawTurn : rawTurns) {
                ConversationTurn turn = deserialize(rawTurn);
                if (turn != null) {
                    turns.add(turn);
                }
            }
            log.debug("读取会话历史 conversationId={} 条数={}", conversationId, turns.size());
            return turns;
        } catch (RuntimeException exception) {
            log.warn("读取会话历史失败，本次按无历史处理 conversationId={}", conversationId, exception);
            return List.of();
        }
    }

    @Override
    public void append(UUID knowledgeBaseId, String conversationId, List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return;
        }

        String key = key(knowledgeBaseId, conversationId);
        int maxMessages = properties.maxMessages();

        try {
            List<String> payload = new ArrayList<>(turns.size());
            for (ConversationTurn turn : turns) {
                payload.add(objectMapper.writeValueAsString(turn));
            }

            redisTemplate.opsForList().rightPushAll(key, payload);
            // 只保留最新的若干条，新的在尾部，所以从尾部往前留
            redisTemplate.opsForList().trim(key, -maxMessages, -1);
            redisTemplate.expire(key, properties.ttl());

            log.debug("写入会话历史 conversationId={} 新增={} 上限={} ttl={}",
                    conversationId, turns.size(), maxMessages, properties.ttl());
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("写入会话历史失败，本轮对话不会被记住 conversationId={}", conversationId, exception);
        }
    }

    // 会话按知识库隔离，同一个 conversationId 在不同知识库里互不干扰
    private static String key(UUID knowledgeBaseId, String conversationId) {
        return KEY_PREFIX + knowledgeBaseId + ":" + conversationId;
    }

    private ConversationTurn deserialize(String rawTurn) {
        try {
            return objectMapper.readValue(rawTurn, ConversationTurn.class);
        } catch (JsonProcessingException exception) {
            // 单条坏数据（例如改过存储格式留下的旧记录）不应影响整段历史
            log.warn("会话历史中存在无法解析的记录，已跳过", exception);
            return null;
        }
    }
}
