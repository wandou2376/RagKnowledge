package com.itdou.ragknowledgesystem.retrieval.vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：把多个召回通道的结果按排名融合成一个排序。
 *
 * 公式是每个通道贡献 1 / (k + 该文档在本通道的排名)，k 取常数 60。
 * 选它而不是「加权分数求和」的原因：不同通道的分数根本不可比——
 * 余弦相似度在 0~1 之间，关键词分数是按词元长度累加的整数，直接相加没有意义。
 * RRF 只用排名，天然避开了量纲问题，也不需要调权重。
 *
 * @param <T> 结果标识类型
 */
public final class ReciprocalRankFusion {

    // 原论文推荐的常数。取值越大，靠前名次的相对优势越平缓
    private static final int RANK_CONSTANT = 60;

    private ReciprocalRankFusion() {
    }

    /**
     * @param channels 各通道的排序结果，每个列表内部按相关性从高到低
     * @param limit    最多返回多少条
     * @return 融合后的结果，按融合分数从高到低，且已去重
     */
    public static <T> List<FusedItem<T>> fuse(List<List<T>> channels, int limit) {
        Map<T, Double> fusedScores = new LinkedHashMap<>();

        for (List<T> ranking : channels) {
            // 同一个标识在同一个通道里只应出现一次，重复出现时只按最好的名次计一次分
            List<T> distinct = new ArrayList<>(new LinkedHashSet<>(ranking));
            for (int position = 0; position < distinct.size(); position++) {
                int rank = position + 1;
                fusedScores.merge(distinct.get(position), 1.0 / (RANK_CONSTANT + rank), Double::sum);
            }
        }

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<T, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> new FusedItem<>(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 融合结果。score 只反映排名，绝对值没有直观含义，不要当成相似度。
     */
    public record FusedItem<T>(T id, double score) {
    }
}
