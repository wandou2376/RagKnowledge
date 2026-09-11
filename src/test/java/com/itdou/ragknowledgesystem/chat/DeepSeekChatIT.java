package com.itdou.ragknowledgesystem.chat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeepSeekChatIT {

    private final ChatClient chatClient;
    private final String model;

    @Autowired
    DeepSeekChatIT(
            ChatClient.Builder chatClientBuilder,
            @Value("${spring.ai.openai.chat.options.model}") String model
    ) {
        this.chatClient = chatClientBuilder.build();
        this.model = model;
    }

    @Test
    void shouldReturnResponseFromConfiguredModel() {
        String content = chatClient.prompt()
                .user("Reply with exactly: connection successful")
                .call()
                .content();

        assertThat(model).isEqualTo("deepseek-v4-flash-vision-exp");
        assertThat(content).containsIgnoringCase("connection successful");
    }
}
