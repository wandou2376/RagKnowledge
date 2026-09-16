package com.itdou.ragknowledgesystem.retrieval.vector;

import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import lombok.extern.slf4j.Slf4j;

/**
 * Java 浮点数组与 pgvector 字面量之间的转换。
 *
 * 为什么需要它：pgvector 的 JDBC 驱动没有内置支持，向量要以字符串字面量（形如 [0.1,0.2]）
 * 传进去再在 SQL 里 CAST 成 vector，所以写入前必须先拼字符串。
 */
@Slf4j
public final class VectorLiteral {

    // float 的十进制展开最长约 15 个字符，预分配容量避免频繁扩容
    private static final int CHARS_PER_FLOAT = 16;

    private VectorLiteral() {
    }

    /**
     * 把向量转成 pgvector 能接受的字面量。
     *
     * 顺便校验维度和数值：维度和列定义不一致时数据库会抛一个难懂的错，
     * NaN / Infinity 则根本无法存储，在这里提前拦下能给出更明确的提示。
     */
    public static String toLiteral(float[] embedding, int expectedDimension) {
        if (embedding == null || embedding.length == 0) {
            throw new EmbeddingFailedException("Embedding 服务返回了空向量");
        }
        if (embedding.length != expectedDimension) {
            throw new EmbeddingFailedException(
                    "向量维度与配置不一致：模型返回 " + embedding.length + " 维，配置为 " + expectedDimension + " 维，"
                            + "请确认 app.embedding.dimension 与迁移里 embedding vector(N) 的 N 相同");
        }

        StringBuilder literal = new StringBuilder(embedding.length * CHARS_PER_FLOAT).append('[');
        for (int index = 0; index < embedding.length; index++) {
            float value = embedding[index];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new EmbeddingFailedException("向量第 " + index + " 维不是有效数字: " + value);
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(value);
        }
        return literal.append(']').toString();
    }
}
