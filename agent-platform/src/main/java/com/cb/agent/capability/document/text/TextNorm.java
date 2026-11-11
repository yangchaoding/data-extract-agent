package com.cb.agent.capability.document.text;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 文本工具：估算 token 数、归一化、压成一行。
 *
 * <p>对应 Python 版 {@code textutil.py} 里的 {@code estimate_tokens}、{@code normalize_chars}、
 * {@code squash}、{@code one_line}。这几个函数的结果会影响切片的边界和"两段文字是否相同"的判断，
 * 所以要和 Python 版逐字符一致，改动之前先跑差分测试。
 *
 * <p>逐字比对原文用的 {@code match_key} 不在这里，它属于抽取引擎，到移植引擎的阶段再加。
 */
public final class TextNorm {

    private TextNorm() {
    }

    /**
     * 粗略估算一段文字有多少 token（偏保守）。对应 {@code estimate_tokens}。
     *
     * <p>各家模型的分词器差别很大，这个数只用来控制切片大小和一次调用放多少原文，不追求准确：
     * 中日韩文字一个字算 1，数字算 0.5，拉丁字母算 0.3，空白算 0.1，其余符号算 0.6，最后加 1。
     *
     * @param text 任意文字，可以为 null
     * @return 估算的 token 数；空串和 null 返回 0
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        int cjk = 0;
        int digits = 0;
        int latin = 0;
        int spaces = 0;
        // 按码位数，不按 char 数：Python 的 len() 数的是码位，一个生僻字在 Java 里占两个 char
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            total++;
            if (isCjk(cp)) {
                cjk++;
            } else if (cp >= '0' && cp <= '9') {
                digits++;
            } else if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) {
                latin++;
            } else if (isSpace(cp)) {
                spaces++;
            }
        }
        int other = total - cjk - digits - latin - spaces;
        // 加法的顺序和 Python 版保持一致，浮点误差才会完全相同
        double sum = cjk + digits * 0.5 + latin * 0.3 + other * 0.6 + spaces * 0.1;
        return (int) sum + 1;
    }

    /**
     * 全半角统一，引号和横线统一；不动空白和大小写。对应 {@code normalize_chars}。
     *
     * <p>识别结果和模型的摘抄经常在这些地方不一样（全角括号变半角，弯引号变直引号），
     * 它们不影响内容，比较之前先统一掉。
     */
    public static String normalizeChars(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char ch = nfkc.charAt(i);
            out.append(switch (ch) {
                // 用码位写，不直接写字符：这几种引号和横线长得几乎一样，写在源码里看不出对不对。
                // 码位和 Python 版 textutil.py 的 _TRANS 一一对应。
                // 弯引号、低位引号、直角引号 → 直引号
                case '\u201C', '\u201D', '\u201E', '\u300C', '\u300D', '\u300E', '\u300F' -> '"';
                case '\u2018', '\u2019' -> '\'';
                // 破折号、连接号、减号、连字符、不换行连字符、横线 → "-"
                case '\u2014', '\u2013', '\u2212', '\u2010', '\u2011', '\u2015' -> '-';
                default -> ch;
            });
        }
        return out.toString();
    }

    /**
     * 判断两段文字是否相同时用的归一化。对应 {@code squash}。
     *
     * <p>在 {@link #normalizeChars} 的基础上，去掉所有空白、表格竖线和零宽字符，再转成小写。
     * 用在页眉页脚去重、判断续表开头是否重复了表头这些地方。
     */
    public static String squash(String text) {
        String s = normalizeChars(text);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            boolean drop = isSpace(cp) || cp == '|'
                    // 零宽空格、零宽不连字、零宽连字、字节序标记、软连字符：看不见，但会让比较失败
                    || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF || cp == 0x00AD;
            if (!drop) {
                out.appendCodePoint(cp);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 压成一行：连续的空白换成一个空格，去掉首尾空白。对应 {@code one_line}。
     *
     * @param text  任意文字
     * @param limit 最多保留多少个字；超过时截断并在末尾加省略号。小于等于 0 表示不限
     */
    public static String oneLine(String text, int limit) {
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isSpace(cp)) {
                // 开头的空白直接丢掉；中间的攒着，遇到下一个非空白字符时才补一个空格，这样结尾的空白也自然去掉了
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.appendCodePoint(cp);
            }
        }
        String line = out.toString();
        if (limit > 0 && line.codePointCount(0, line.length()) > limit) {
            int keep = Math.max(limit - 1, 1);
            line = line.substring(0, line.offsetByCodePoints(0, keep)) + "…";
        }
        return line;
    }

    /** 压成一行，不限长度。 */
    public static String oneLine(String text) {
        return oneLine(text, 0);
    }

    /**
     * 去掉首尾的空白，判断标准和 Python 的 {@code str.strip()} 相同。
     *
     * <p>不用 {@link String#strip()}：它不会去掉不换行空格，而识别结果里常有这种空格。
     */
    public static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            int cp = text.codePointAt(start);
            if (!isSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = text.codePointBefore(end);
            if (!isSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return text.substring(start, end);
    }

    /** 一段文字有多少个字（按码位数，和 Python 的 len() 一致）。 */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * 是不是中日韩文字：CJK 扩展 A、CJK 基本区、兼容表意文字、平假名和片假名、谚文。
     * 范围和 Python 版的 {@code _CJK_RANGES} 相同。
     */
    public static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF);
    }

    /**
     * 是不是空白字符，判断标准和 Python 正则里的 {@code \s} 相同。
     *
     * <p>不能直接用 {@link Character#isWhitespace}：它不把不换行空格（U+00A0）、窄不换行空格（U+202F）
     * 算作空白，而 Python 算。两边不一致会让 token 估算和归一化的结果出现差异。
     */
    public static boolean isSpace(int cp) {
        return (cp >= 0x09 && cp <= 0x0D) || (cp >= 0x1C && cp <= 0x20)
                || cp == 0x85 || cp == 0xA0 || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A)
                || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }
}
