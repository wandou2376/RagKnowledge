package com.itdou.ragknowledgesystem.chat.prompt;

import com.itdou.ragknowledgesystem.chat.memory.ConversationTurn;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把检索到的分块、历史对话和用户问题组装成提示词。
 *
 * 分块按顺序编号成 [1]、[2]……，模型在回答里引用这些编号，
 * 之后再把编号映射回具体分块，这是引用溯源的关键约定。
 */
@Component
public class RagPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            你是一个企业知识库问答助手。请严格遵守以下规则：
            1. 只依据【参考资料】中的内容回答问题，不要使用你自己的知识，也不要推测或补充。
            2. 如果参考资料不足以回答问题，直接回答「根据现有资料无法回答该问题」，不要编造。
            3. 引用参考资料时，在相应句子的末尾用方括号标注编号，例如 [1] 或 [1][3]。
            4. 只允许使用【参考资料】中出现过的编号，不要编造编号。
            5. 用中文回答，简洁准确，不要大段复述参考资料原文。
            6. 可以参考对话历史理解用户的追问（例如「它」「上面提到的」指什么），
               但事实依据仍然只能来自本次的【参考资料】，不要沿用历史里无法在参考资料中核实的内容。
            """;

    public RagPrompt build(String question, List<SearchResultResponse> sources, List<ConversationTurn> history) {
        StringBuilder userPrompt = new StringBuilder("【参考资料】\n");

        for (int index = 0; index < sources.size(); index++) {
            SearchResultResponse source = sources.get(index);
            userPrompt.append('[').append(index + 1).append("]（来源：")
                    .append(source.originalFileName())
                    .append('，').append(pageDescription(source)).append("）\n")
                    .append(source.content())
                    .append("\n\n");
        }

        userPrompt.append("【问题】\n").append(question);
        return new RagPrompt(SYSTEM_PROMPT, history, userPrompt.toString());
    }

    // 分块可能没有页码信息（例如以后接入的其他文档格式），这里要能优雅降级而不是打印 null
    private String pageDescription(SearchResultResponse source) {
        Integer start = source.pageNumberStart();
        Integer end = source.pageNumberEnd();

        if (start == null) {
            return "页码未知";
        }
        if (end == null || end.equals(start)) {
            return "第 " + start + " 页";
        }
        return "第 " + start + "-" + end + " 页";
    }
}
