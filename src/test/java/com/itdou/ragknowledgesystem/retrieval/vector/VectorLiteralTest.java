package com.itdou.ragknowledgesystem.retrieval.vector;

import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorLiteralTest {

    private static final int DIMENSION = 4;

    @Test
    void shouldFormatVectorAsPgVectorLiteral() {
        String literal = VectorLiteral.toLiteral(new float[]{0.5f, -0.25f, 1.0f, 0.0f}, DIMENSION);

        assertThat(literal).isEqualTo("[0.5,-0.25,1.0,0.0]");
    }

    // 维度对不上时数据库会抛一个很难懂的错，这里要提前拦下并说清原因
    @Test
    void shouldRejectVectorWithUnexpectedDimension() {
        assertThatThrownBy(() -> VectorLiteral.toLiteral(new float[]{0.1f, 0.2f}, DIMENSION))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("向量维度与配置不一致")
                .hasMessageContaining("2 维");
    }

    @Test
    void shouldRejectEmptyVector() {
        assertThatThrownBy(() -> VectorLiteral.toLiteral(new float[0], DIMENSION))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("空向量");

        assertThatThrownBy(() -> VectorLiteral.toLiteral(null, DIMENSION))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("空向量");
    }

    // pgvector 无法存储 NaN 和 Infinity，放过去会在写库时报错
    @Test
    void shouldRejectNonFiniteValues() {
        assertThatThrownBy(() -> VectorLiteral.toLiteral(new float[]{0.1f, Float.NaN, 0.3f, 0.4f}, DIMENSION))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("不是有效数字");

        assertThatThrownBy(() -> VectorLiteral.toLiteral(
                new float[]{0.1f, Float.POSITIVE_INFINITY, 0.3f, 0.4f}, DIMENSION))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("不是有效数字");
    }

    @Test
    void shouldSupportFullDimensionVector() {
        float[] embedding = new float[1024];
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] = index / 1024.0f;
        }

        String literal = VectorLiteral.toLiteral(embedding, 1024);

        assertThat(literal).startsWith("[").endsWith("]");
        // 1024 个分量之间应该有 1023 个逗号
        assertThat(literal.chars().filter(character -> character == ',').count()).isEqualTo(1023);
    }
}
