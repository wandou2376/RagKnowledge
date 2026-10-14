package com.itdou.ragknowledgesystem.chat.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆的集成测试：真的连 Redis 读写。
 *
 * 单独测这一层的原因：裁剪边界、TTL、以及按知识库隔离 key 这些行为用 mock 验证不了——
 * mock 只能证明「调用了什么方法」，证明不了「Redis 里最终留下了什么」。
 *
 * 需要真实 Redis，用 RUN_EXTERNAL_TESTS=true 开启，默认跳过。
 * max-turns 覆盖成 2，最多保留 4 条消息，方便验证裁剪。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_TESTS", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.chat.memory.max-turns=2", "app.chat.memory.ttl=10m"}
)
class RedisConversationMemoryIT {

    private static final String KEY_PREFIX = "rag:chat:";

    @Autowired
    private ConversationMemory conversationMemory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<String> touchedKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!touchedKeys.isEmpty()) {
            redisTemplate.delete(touchedKeys);
        }
    }

    @Test
    void shouldAppendAndLoadTurnsInOrder() {
        Conversation ref = newConversation();

        conversationMemory.append(ref.knowledgeBaseId(), ref.conversationId(), List.of(
                ConversationTurn.user("第一个问题"),
                ConversationTurn.assistant("第一个回答")));

        assertThat(conversationMemory.load(ref.knowledgeBaseId(), ref.conversationId())).containsExactly(
                new ConversationTurn(ChatRole.USER, "第一个问题"),
                new ConversationTurn(ChatRole.ASSISTANT, "第一个回答"));
    }

    @Test
    void shouldReturnEmptyForUnknownConversation() {
        Conversation ref = newConversation();

        assertThat(conversationMemory.load(ref.knowledgeBaseId(), ref.conversationId())).isEmpty();
    }

    // 历史不能无限增长：超过上限时只保留最新的若干条
    @Test
    void shouldKeepOnlyTheMostRecentMessages() {
        Conversation ref = newConversation();

        conversationMemory.append(ref.knowledgeBaseId(), ref.conversationId(), List.of(
                ConversationTurn.user("问题一"), ConversationTurn.assistant("回答一")));
        conversationMemory.append(ref.knowledgeBaseId(), ref.conversationId(), List.of(
                ConversationTurn.user("问题二"), ConversationTurn.assistant("回答二")));
        conversationMemory.append(ref.knowledgeBaseId(), ref.conversationId(), List.of(
                ConversationTurn.user("问题三"), ConversationTurn.assistant("回答三")));

        assertThat(conversationMemory.load(ref.knowledgeBaseId(), ref.conversationId())).containsExactly(
                new ConversationTurn(ChatRole.USER, "问题二"),
                new ConversationTurn(ChatRole.ASSISTANT, "回答二"),
                new ConversationTurn(ChatRole.USER, "问题三"),
                new ConversationTurn(ChatRole.ASSISTANT, "回答三"));
    }

    @Test
    void shouldSetExpiryOnConversationKey() {
        Conversation ref = newConversation();

        conversationMemory.append(ref.knowledgeBaseId(), ref.conversationId(), List.of(
                ConversationTurn.user("问题"), ConversationTurn.assistant("回答")));

        Long ttlSeconds = redisTemplate.getExpire(ref.redisKey(), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull().isPositive()
                .isLessThanOrEqualTo(TimeUnit.MINUTES.toSeconds(10));
    }

    // 同一个 conversationId 在不同知识库里必须是两段独立的历史
    @Test
    void shouldIsolateConversationsByKnowledgeBase() {
        Conversation first = newConversation();
        UUID anotherKnowledgeBase = UUID.randomUUID();
        touchedKeys.add(key(anotherKnowledgeBase, first.conversationId()));

        conversationMemory.append(first.knowledgeBaseId(), first.conversationId(), List.of(
                ConversationTurn.user("库A的问题")));

        assertThat(conversationMemory.load(anotherKnowledgeBase, first.conversationId())).isEmpty();
        assertThat(conversationMemory.load(first.knowledgeBaseId(), first.conversationId())).hasSize(1);
    }

    @Test
    void shouldIsolateDifferentConversations() {
        Conversation first = newConversation();
        Conversation second = newConversation();

        conversationMemory.append(first.knowledgeBaseId(), first.conversationId(), List.of(
                ConversationTurn.user("会话一的问题")));

        assertThat(conversationMemory.load(second.knowledgeBaseId(), second.conversationId())).isEmpty();
    }

    // 存储格式变更留下的旧记录不该让整段历史读不出来
    @Test
    void shouldSkipUnparsableMessages() {
        Conversation ref = newConversation();

        redisTemplate.opsForList().rightPushAll(ref.redisKey(), List.of(
                "这不是 JSON",
                "{\"role\":\"USER\",\"content\":\"正常的一条\"}"));

        assertThat(conversationMemory.load(ref.knowledgeBaseId(), ref.conversationId())).containsExactly(
                new ConversationTurn(ChatRole.USER, "正常的一条"));
    }

    // 统一从这里拿会话标识，顺带登记 key 以便测试结束后清理
    private Conversation newConversation() {
        UUID knowledgeBaseId = UUID.randomUUID();
        String conversationId = "it-" + UUID.randomUUID().toString().substring(0, 8);
        touchedKeys.add(key(knowledgeBaseId, conversationId));
        return new Conversation(knowledgeBaseId, conversationId);
    }

    private static String key(UUID knowledgeBaseId, String conversationId) {
        return KEY_PREFIX + knowledgeBaseId + ":" + conversationId;
    }

    private record Conversation(UUID knowledgeBaseId, String conversationId) {

        String redisKey() {
            return key(knowledgeBaseId, conversationId);
        }
    }
}
