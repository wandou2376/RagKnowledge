package com.itdou.ragknowledgesystem.retrieval.dto;

import com.itdou.ragknowledgesystem.retrieval.service.RetrievalMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 检索请求。
 *
 * @param query 检索问题
 * @param topK  返回条数，不传时由服务端取默认值
 * @param mode  检索模式，不传时为 HYBRID（向量 + 关键词融合）
 */
public record SearchRequest(
        @NotBlank(message = "检索问题不能为空")
        @Size(max = 2000, message = "检索问题长度不能超过 2000 个字符")
        String query,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 50, message = "topK 最大为 50")
        Integer topK,

        RetrievalMode mode
) {
}
