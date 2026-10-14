package com.itdou.ragknowledgesystem.chat.prompt;

import com.itdou.ragknowledgesystem.chat.memory.ConversationTurn;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagPromptBuilderTest {

    private final RagPromptBuilder promptBuilder = new RagPromptBuilder();

    @Test
    void shouldNumberReferencesFromOneAndIncludePageRange() {
        RagPrompt prompt = promptBuilder.build("有哪些期刊？", List.of(
                source("第一块内容", 1, 1),
                source("第二块内容", 3, 5)), List.of());

        assertThat(prompt.userPrompt())
                .contains("[1]（来源：guide.pdf，第 1 页）")
                .contains("第一块内容")
                .contains("[2]（来源：guide.pdf，第 3-5 页）")
                .contains("第二块内容");
    }

    @Test
    void shouldIncludeQuestionAtTheEnd() {
        RagPrompt prompt = promptBuilder.build("机器人相关的期刊有哪些？", List.of(source("内容", 1, 1)), List.of());

        assertThat(prompt.userPrompt()).contains("【问题】").contains("机器人相关的期刊有哪些？");
        assertThat(prompt.userPrompt().indexOf("【参考资料】")).isLessThan(prompt.userPrompt().indexOf("【问题】"));
    }

    // 系统提示词是「不编造」这条要求的第一道防线，关键约束不能丢
    @Test
    void systemPromptShouldConstrainTheModel() {
        RagPrompt prompt = promptBuilder.build("问题", List.of(source("内容", 1, 1)), List.of());

        assertThat(prompt.systemPrompt())
                .contains("只依据【参考资料】")
                .contains("无法回答")
                .contains("不要编造")
                .contains("编号");
    }

    // 历史要以真实角色消息发出，不能掺进 userPrompt 里
    @Test
    void shouldCarryHistorySeparatelyFromUserPrompt() {
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("第一个问题"),
                ConversationTurn.assistant("第一个回答"));

        RagPrompt prompt = promptBuilder.build("第二个问题", List.of(source("内容", 1, 1)), history);

        assertThat(prompt.history()).containsExactlyElementsOf(history);
        assertThat(prompt.userPrompt()).doesNotContain("第一个问题").doesNotContain("第一个回答");
    }

    @Test
    void shouldTreatNullHistoryAsEmpty() {
        RagPrompt prompt = promptBuilder.build("问题", List.of(source("内容", 1, 1)), null);

        assertThat(prompt.history()).isEmpty();
    }

    @Test
    void shouldFallBackWhenPageNumbersAreMissing() {
        RagPrompt prompt = promptBuilder.build("问题", List.of(
                new SearchResultResponse(UUID.randomUUID(), UUID.randomUUID(), "a.pdf", 0,
                        "没有页码的内容", null, null, 100, 0.9, 0.9, null)), List.of());

        assertThat(prompt.userPrompt()).contains("页码未知").doesNotContain("null");
    }

    @Test
    void shouldCollapseSinglePageRange() {
        RagPrompt prompt = promptBuilder.build("问题", List.of(source("内容", 4, 4)), List.of());

        assertThat(prompt.userPrompt()).contains("第 4 页").doesNotContain("第 4-4 页");
    }

    private static SearchResultResponse source(String content, Integer pageStart, Integer pageEnd) {
        return new SearchResultResponse(UUID.randomUUID(), UUID.randomUUID(), "guide.pdf", 0,
                content, pageStart, pageEnd, 100, 0.9, 0.9, null);
    }
}
