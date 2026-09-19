package com.itdou.ragknowledgesystem.chat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库问答请求。
 *
 * @param question 用户问题
 * @param topK     参与组装的参考资料条数，不传时用检索服务的默认值
 */
public record ChatRequest(
        @NotBlank(message = "提问内容不能为空")
        @Size(max = 2000, message = "提问内容长度不能超过 2000 个字符")
        String question,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 50, message = "topK 最大为 50")
        Integer topK
) {
}
