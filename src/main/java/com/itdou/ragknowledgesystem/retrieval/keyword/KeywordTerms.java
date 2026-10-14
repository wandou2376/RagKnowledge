package com.itdou.ragknowledgesystem.retrieval.keyword;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从检索问题里抽关键词元。
 *
 * 为什么需要它：PostgreSQL 没有可用的中文分词器，整句拿去做包含匹配几乎没有区分度。
 * 实测「T3 类里有哪些中文期刊」这类问句和每个分块的相似度都差不多，
 * 而拆成词元后「智能系统学报的主办单位」能命中 4 个词元、其他分块只命中 1 个。
 *
 * 抽取规则：
 * - 按非中英文数字的字符切分（空白、标点、括号等都算分隔符）
 * - 纯英文数字的片段（如 T3、E-4012 拆出来的 E 和 4012）直接保留
 * - 含中文的片段，除了整段保留，还要产出所有 2 字的滑动窗口
 *   ——中文里 2 字词最常见，这样不用分词器也能匹配到「期刊」「机器人」这类词
 *
 * 词元在 SQL 里用 | 拼接传递，所以抽取结果里不可能包含 |；
 * % 和 _ 是 LIKE 的通配符，也一并排除，避免用户输入变成通配注入。
 */
public final class KeywordTerms {

    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{IsHan}a-zA-Z0-9]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final Pattern UNSUPPORTED_IN_LIKE = Pattern.compile("[%_|'\\\\]");

    // 单个词元最短 2 个字符：单字太泛，命中率极低还会引入噪音
    private static final int MIN_TERM_LENGTH = 2;
    private static final int CHINESE_WINDOW = 2;

    private KeywordTerms() {
    }

    public static Set<String> extract(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }

        for (String fragment : SEPARATOR.split(query.strip())) {
            String token = fragment.strip();
            if (token.length() < MIN_TERM_LENGTH) {
                continue;
            }

            addIfUsable(terms, token);

            if (HAN.matcher(token).find()) {
                for (int start = 0; start + CHINESE_WINDOW <= token.length(); start++) {
                    addIfUsable(terms, token.substring(start, start + CHINESE_WINDOW));
                }
            }
        }
        return terms;
    }

    private static void addIfUsable(Set<String> terms, String candidate) {
        if (candidate.length() >= MIN_TERM_LENGTH && !UNSUPPORTED_IN_LIKE.matcher(candidate).find()) {
            terms.add(candidate);
        }
    }
}
