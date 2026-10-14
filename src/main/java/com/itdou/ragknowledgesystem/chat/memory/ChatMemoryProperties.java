package com.itdou.ragknowledgesystem.chat.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 会话记忆配置，对应 application-dev.yml 里的 app.chat.memory。
 */
@ConfigurationProperties(prefix = "app.chat.memory")
public record ChatMemoryProperties(
        // 最多保留多少轮（一轮 = 提问 + 回答）
        @DefaultValue("5") int maxTurns,
        // 会话过期时间，每次追加消息都会滑动续期
        @DefaultValue("7d") Duration ttl
) {

    // 存进 Redis 的是消息条数，不是轮数
    public int maxMessages() {
        return maxTurns * 2;
    }
}
