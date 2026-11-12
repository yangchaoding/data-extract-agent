package com.cb.agent.capability.document.text;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一段原文，以及比对时要用的几种形式。对应 Python 版 {@code textutil.py} 的 {@code SourceText}。
 *
 * <p>比对用的形式算起来不便宜，而同一段原文会被比对很多次（每个字段的每个值都要拿来比），
 * 所以第一次用到时才算，算好了就留着。
 *
 * <p>可以被多个线程同时使用：几个线程同时第一次用到时可能各算一遍，但算出来的结果相同，不会出错。
 */
public final class SourceText {

    private final String text;
    private volatile String key;
    private volatile String gluedKey;
    private volatile Set<String> trigrams;

    public SourceText(String text) {
        this.text = text;
    }

    /** 原文。 */
    public String text() {
        return text;
    }

    /** 原文的比对用形式。 */
    public String key() {
        String k = key;
        if (k == null) {
            k = TextMatch.matchKey(text);
            key = k;
        }
        return k;
    }

    private String gluedKey() {
        String k = gluedKey;
        if (k == null) {
            k = TextMatch.matchKey(text, true);
            gluedKey = k;
        }
        return k;
    }

    private Set<String> trigrams() {
        Set<String> t = trigrams;
        if (t == null) {
            t = trigramsOf(key());
            trigrams = t;
        }
        return t;
    }

    /**
     * {@code quote} 是不是这段原文里的文字。对应 {@code SourceText.supports}。
     *
     * <p>忽略空白、标点、全半角、大小写和千分位的差别，除此之外必须逐字相同。
     * {@code quote} 可以是摘抄的几段（用换行或省略号隔开），每一段都必须是原文。
     */
    public boolean supports(String quote) {
        List<String> parts = TextMatch.quoteParts(quote);
        if (parts.isEmpty()) {
            return false;
        }
        for (String part : parts) {
            if (TextMatch.find(TextMatch.matchKey(part), key()) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code value} 是不是原文里的文字。对应 {@code SourceText.contains_value}。
     *
     * <p>比 {@link #supports} 多容忍一点：原文里分组书写的号码，值可以连着写。
     */
    public boolean containsValue(String value) {
        if (supports(value)) {
            return true;
        }
        return TextMatch.find(TextMatch.matchKey(value, true), gluedKey()) >= 0;
    }

    /**
     * {@code piece} 在原文里的位置（按比对用的形式算）。对应 {@code SourceText.position}。
     *
     * @return 位置；找不到返回 -1
     */
    public int position(String piece) {
        return TextMatch.find(TextMatch.matchKey(piece), key());
    }

    /**
     * {@code quote} 和原文有多像，0 到 1：它的每三个相邻的字，有多大比例出现在原文里。
     * 对应 {@code SourceText.similarity}。
     *
     * <p>只用来定位和给提示（"你的摘抄大概是想引这一段"），<b>不用来判断真伪</b>。判断真伪用 {@link #supports}。
     */
    public double similarity(String quote) {
        String q = TextMatch.matchKey(quote);
        if (q.isEmpty()) {
            return 0.0;
        }
        int[] cps = q.codePoints().toArray();
        if (cps.length < 3) {
            return key().contains(q) ? 1.0 : 0.0;
        }
        Set<String> source = trigrams();
        int hit = 0;
        int total = cps.length - 2;
        for (int i = 0; i < total; i++) {
            if (source.contains(new String(cps, i, 3))) {
                hit++;
            }
        }
        return (double) hit / total;
    }

    private static Set<String> trigramsOf(String s) {
        int[] cps = s.codePoints().toArray();
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 3 <= cps.length; i++) {
            out.add(new String(cps, i, 3));
        }
        return out;
    }
}
