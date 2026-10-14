package com.itdou.ragknowledgesystem.retrieval.vector;

import com.itdou.ragknowledgesystem.retrieval.vector.ReciprocalRankFusion.FusedItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    @Test
    void shouldRankItemPresentInBothChannelsFirst() {
        // c 在两个通道都排第二，a 只在一个通道排第一：RRF 认为前者更可信
        List<FusedItem<String>> fused = ReciprocalRankFusion.fuse(List.of(
                List.of("a", "c", "b"),
                List.of("d", "c")), 10);

        // a 和 d 分数相同，不指定它们之间的顺序，只断言首尾
        assertThat(fused.get(0).id()).isEqualTo("c");
        assertThat(fused.get(fused.size() - 1).id()).isEqualTo("b");
        assertThat(fused).extracting(FusedItem::id).containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    @Test
    void shouldPreserveOrderWhenOnlyOneChannelHasResults() {
        List<FusedItem<String>> fused = ReciprocalRankFusion.fuse(List.of(
                List.of("x", "y", "z"),
                List.of()), 10);

        assertThat(fused).extracting(FusedItem::id).containsExactly("x", "y", "z");
    }

    @Test
    void shouldDeduplicateWithinAChannel() {
        // 去重发生在排名之前，重复出现不应给同一个标识额外加分
        List<FusedItem<String>> withDuplicate = ReciprocalRankFusion.fuse(List.of(List.of("a", "a", "b")), 10);
        List<FusedItem<String>> withoutDuplicate = ReciprocalRankFusion.fuse(List.of(List.of("a", "b")), 10);

        assertThat(withDuplicate).extracting(FusedItem::id).containsExactly("a", "b");
        assertThat(withDuplicate.get(0).score()).isEqualTo(withoutDuplicate.get(0).score());
        assertThat(withDuplicate.get(1).score()).isEqualTo(withoutDuplicate.get(1).score());
    }

    @Test
    void shouldLimitResults() {
        List<FusedItem<String>> fused = ReciprocalRankFusion.fuse(List.of(
                List.of("a", "b", "c", "d")), 2);

        assertThat(fused).extracting(FusedItem::id).containsExactly("a", "b");
    }

    // 融合分数只反映排名，不同名次的差距应该随名次靠后而变小
    @Test
    void scoreShouldDecreaseWithRank() {
        List<FusedItem<String>> fused = ReciprocalRankFusion.fuse(List.of(List.of("a", "b", "c")), 10);

        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        assertThat(fused.get(1).score()).isGreaterThan(fused.get(2).score());
    }

    @Test
    void shouldReturnEmptyWhenAllChannelsAreEmpty() {
        assertThat(ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), 5)).isEmpty();
    }
}
