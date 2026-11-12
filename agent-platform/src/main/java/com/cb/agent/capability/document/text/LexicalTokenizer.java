package com.cb.agent.capability.document.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索用的分词。对应 Python 版 {@code textutil.py} 的 {@code lexical_tokens}。
 *
 * <p>不用分词库。中文取相邻的两个字作为一个词（"合同总价"→"合同""同总""总价"），
 * 这样不需要词典也能匹配到大多数词；字母和数字取整个词（"HT-2024-0318"是一个词）。
 */
public final class LexicalTokenizer {

    /** 中日韩文字的范围，和 {@link TextNorm#isCjk} 相同。 */
    private static final String CJK = "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF\\uAC00-\\uD7AF";
    /** 一个词：一串连续的中日韩文字，或者一串字母数字（中间可以有点、下划线、横线连着）。 */
    private static final Pattern TOKEN = Pattern.compile("[" + CJK + "]+|[a-z0-9]+(?:[._-][a-z0-9]+)*");

    private LexicalTokenizer() {
    }

    /**
     * @param text 任意文字
     * @return 分出来的词，按出现的顺序，重复的保留（检索要数词频）
     */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            String tok = m.group();
            if (TextNorm.isCjk(tok.codePointAt(0))) {
                if (tok.length() == 1) {
                    out.add(tok); // 单独一个字，没法取两个字，就用它自己
                } else {
                    for (int i = 0; i + 2 <= tok.length(); i++) {
                        out.add(tok.substring(i, i + 2));
                    }
                }
            } else {
                out.add(tok);
            }
        }
        return out;
    }
}
