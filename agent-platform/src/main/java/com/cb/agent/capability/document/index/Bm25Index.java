package com.cb.agent.capability.document.index;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.text.LexicalTokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 切片的关键词检索（BM25）。对应 Python 版 {@code index.py} 的 {@code ChunkIndex}。
 *
 * <p>用来决定一个字段先去哪几片里找。不依赖向量模型：字段的中文名、别名、说明拼成检索词，
 * 和每一片的文字比，词出现得多、而且这个词在别的片里少见，得分就高。
 *
 * <p>检索只决定先后，不做筛选。{@link #rank} 返回的是全部切片的一个排序，没有命中的排在后面，
 * 所以多找几轮总能把全文看完。
 */
public final class Bm25Index {

    /** 词频饱和的快慢。BM25 的常用取值。 */
    private static final double K1 = 1.5;
    /** 片的长短对得分的影响程度。BM25 的常用取值。 */
    private static final double B = 0.75;

    private final List<Map<String, Integer>> docs = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final double avgLength;
    private final Map<String, Integer> df = new HashMap<>();

    public Bm25Index(List<Chunk> chunks) {
        for (Chunk c : chunks) {
            Map<String, Integer> tf = new HashMap<>();
            for (String token : LexicalTokenizer.tokens(c.text())) {
                tf.merge(token, 1, Integer::sum);
            }
            // 章节路径里的词算两次：标题里出现这个词，比正文里出现更能说明这一片讲的是它
            for (String token : LexicalTokenizer.tokens(String.join(" ", c.path()))) {
                tf.merge(token, 2, Integer::sum);
            }
            docs.add(tf);
            lengths.add(tf.values().stream().mapToInt(Integer::intValue).sum());
            for (String token : tf.keySet()) {
                df.merge(token, 1, Integer::sum);
            }
        }
        avgLength = lengths.isEmpty() ? 1.0
                : lengths.stream().mapToInt(Integer::intValue).sum() / (double) lengths.size();
    }

    /**
     * 每一片对这个检索词的得分。
     *
     * @return 和切片一一对应的得分；没有命中任何词的是 0
     */
    public double[] scores(String query) {
        int n = docs.size();
        // 排好序再累加：浮点数相加的结果和顺序有关，固定顺序才能每次算出同样的分数
        TreeSet<String> terms = new TreeSet<>(LexicalTokenizer.tokens(query));
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Integer> doc = docs.get(i);
            int length = lengths.get(i);
            double score = 0.0;
            for (String term : terms) {
                Integer tf = doc.get(term);
                if (tf == null) {
                    continue;
                }
                int d = df.get(term);
                double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / avgLength));
            }
            out[i] = score;
        }
        return out;
    }

    /**
     * 全部切片按相关的程度排序：得分高的在前，得分相同的（包括没有命中的）按在文档里的先后。
     *
     * @return 切片的序号
     */
    public List<Integer> rank(String query) {
        double[] scores = scores(query);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingDouble(i -> -scores[i]).thenComparingInt(i -> i));
        return order;
    }
}
