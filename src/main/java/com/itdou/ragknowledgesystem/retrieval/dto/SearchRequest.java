package com.itdou.ragknowledgesystem.retrieval.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 语义检索请求。
 *
 * @param query 检索问题
 * @param topK  返回条数，不传时由服务端取默认值
 */
public record SearchRequest(
        @NotBlank(message = "检索问题不能为空")
        @Size(max = 2000, message = "检索问题长度不能超过 2000 个字符")
        String query,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 50, message = "topK 最大为 50")
        Integer topK
) {
}
