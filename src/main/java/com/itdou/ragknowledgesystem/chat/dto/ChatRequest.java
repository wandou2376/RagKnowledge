package com.itdou.ragknowledgesystem.chat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 知识库问答请求。
 *
 * @param question       用户问题
 * @param topK           参与组装的参考资料条数，不传时用检索服务的默认值
 * @param conversationId 会话标识，由调用方生成。不传就是单轮问答，不读写历史记忆。
 *                       限制字符集是为了防止它携带特殊字符拼进 Redis key
 */
public record ChatRequest(
        @NotBlank(message = "提问内容不能为空")
        @Size(max = 2000, message = "提问内容长度不能超过 2000 个字符")
        String question,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 50, message = "topK 最大为 50")
        Integer topK,

        @Size(max = 64, message = "conversationId 长度不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "conversationId 只能包含字母、数字、下划线和连字符")
        String conversationId
) {
}
