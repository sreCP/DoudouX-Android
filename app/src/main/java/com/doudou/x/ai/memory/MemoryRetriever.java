package com.doudou.x.ai.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆检索：从记忆库里挑出该带进上下文的那几条。
 *
 * <p>为什么不用向量检索：端侧调 embedding 接口要多一次网络往返，
 * 且依赖服务端是否提供该能力。这里用 <b>字符 bigram 的 Jaccard 相似度</b>，
 * 对中文表现不错、零依赖、且可解释——召回错了能立刻看出是哪里匹配上的。
 * 接口保持不变，后续要换成 embedding 只需替换 {@link #similarity}。
 *
 * <p>关键设计：<b>程序记忆不参与 query 匹配，总是带上</b>。
 * 它描述的是"怎么做事"（先给结论、代码要可运行），
 * 和当前提问的字面相关度很低，但影响的是整轮回答的风格。
 */
public final class MemoryRetriever {

    /** 程序记忆固定带上的条数。 */
    private static final int PROCEDURAL_ALWAYS = 2;
    /** 相关度低于这个阈值就不召回，宁缺毋滥——无关记忆比没记忆更干扰模型。 */
    private static final float MIN_RELEVANCE = 0.12f;

    private MemoryRetriever() {
    }

    /**
     * 召回本次该用的记忆。
     *
     * @param all   完整记忆库
     * @param query 当前用户提问
     * @param limit 最多返回条数（含固定带上的程序记忆）
     */
    public static List<MemoryItem> recall(List<MemoryItem> all, String query, int limit) {
        List<MemoryItem> result = new ArrayList<>();
        if (all == null || all.isEmpty() || limit <= 0) {
            return result;
        }
        final long now = System.currentTimeMillis();
        Set<String> queryGrams = bigrams(query);

        List<Scored> procedural = new ArrayList<>();
        List<Scored> relevant = new ArrayList<>();
        for (MemoryItem item : all) {
            float base = item.score(now);
            if (item.getType() == MemoryType.PROCEDURAL) {
                procedural.add(new Scored(item, base));
                continue;
            }
            float rel = similarity(queryGrams,
                    bigrams(item.getKey() + " " + item.getContent()));
            if (rel >= MIN_RELEVANCE) {
                relevant.add(new Scored(item, rel * base));
            }
        }

        // 程序记忆先按遗忘分取前几条，剩下的名额给语义/情景记忆
        Collections.sort(procedural, DESC);
        int taken = 0;
        for (Scored scored : procedural) {
            if (taken >= Math.min(PROCEDURAL_ALWAYS, limit)) {
                break;
            }
            result.add(scored.item);
            taken++;
        }
        Collections.sort(relevant, DESC);
        for (Scored scored : relevant) {
            if (result.size() >= limit) {
                break;
            }
            result.add(scored.item);
        }
        return result;
    }

    /**
     * 相关度：字符 bigram 的 Jaccard 相似度。
     *
     * <p>用 bigram 而不是单词，是因为中文没有空格分词，
     * 而双字组合（"记忆"、"工程"）已经能提供足够的区分度。
     */
    public static float similarity(String query, String text) {
        return similarity(bigrams(query), bigrams(text));
    }

    private static float similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0f;
        }
        int intersection = 0;
        for (String gram : a) {
            if (b.contains(gram)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0f : (float) intersection / union;
    }

    /** 切出全部相邻两字组合；单字文本退化成只有一个元素。 */
    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null) {
            return grams;
        }
        String s = normalize(text);
        for (int i = 0; i + 1 < s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        if (s.length() == 1) {
            grams.add(s);
        }
        return grams;
    }

    /** 去掉空白、统一小写，让匹配不受空格与大小写影响。 */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 召回结果的排序载体：分数高的优先。 */
    private static final class Scored {
        final MemoryItem item;
        final float score;

        Scored(MemoryItem item, float score) {
            this.item = item;
            this.score = score;
        }
    }

    private static final Comparator<Scored> DESC = new Comparator<Scored>() {
        @Override
        public int compare(Scored a, Scored b) {
            return Float.compare(b.score, a.score);
        }
    };
}
