package com.cb.agent.capability.document.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逐字比对用的工具：判断一段摘抄是不是原文里的文字。
 *
 * <p>对应 Python 版 {@code textutil.py} 里的 {@code match_key}、{@code strip_thousands}、
 * {@code _find}、{@code quote_parts}。整个抽取的可信度建立在这上面：模型给出的每个值都要附一段原文摘抄，
 * 程序用这里的方法判断摘抄是不是真的出自原文。
 *
 * <p>比对的原则是：<b>文字和数字一个都不能动，其余的差别都忽略</b>。模型摘抄原文时经常改动空白、换行、
 * 全半角、标点和千分位，这些不影响内容；但改了一个字、一个数，就不再是原文。
 */
public final class TextMatch {

    /** 两个数字之间紧挨着的这些字符是数字的一部分，要保留：小数点、千分位、时间、日期、范围。 */
    private static final String NUMBER_SEPARATORS = ".,:/-";

    /** 千分位的逗号：前面是数字，后面恰好是三位数字。 */
    private static final Pattern THOUSANDS =
            Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))", Pattern.UNICODE_CHARACTER_CLASS);

    /** 摘抄可以分成几段：换行、省略号（三个以上的点，或者省略号字符）都是分隔。 */
    private static final Pattern BREAK = Pattern.compile("\\n|\\.{3,}|\\u2026+");

    private TextMatch() {
    }

    /** 去掉数字里的千分位逗号：1,234,567.89 → 1234567.89。对应 {@code strip_thousands}。 */
    public static String stripThousands(String text) {
        return THOUSANDS.matcher(text).replaceAll("");
    }

    /** 比对用的形式，分组书写的号码不连起来。见 {@link #matchKey(String, boolean)}。 */
    public static String matchKey(String text) {
        return matchKey(text, false);
    }

    /**
     * 把一段文字变成比对用的形式。对应 {@code match_key}。
     *
     * <p>做法是只留下字母、数字、汉字，其余的（空白、标点、表格竖线）全部去掉，再统一全半角和大小写。
     * 但数字要保持完整，所以有两条例外：
     * <ul>
     *   <li>两个数字之间<b>紧挨着</b>的一个分隔符（小数点、冒号、斜杠、横线）保留，这样 1.5 不会变成 15；</li>
     *   <li>两个数字之间隔着别的东西（空格、表格竖线、别的标点）时留一个空格当边界，
     *       这样相邻单元格里的 30 和 384 不会粘成 30384。</li>
     * </ul>
     *
     * @param glueDigitGroups true 时，只隔着空格的数字连在一起。用来认出分组书写的号码：
     *                        原文是 6222 0212 3456，值写成 622202123456 也算对
     */
    public static String matchKey(String text, boolean glueDigitGroups) {
        int[] s = TextNorm.normalizeChars(text).toLowerCase(Locale.ROOT).codePoints().toArray();
        StringBuilder out = new StringBuilder(s.length);
        int last = -1; // 最后放进 out 的那个字符；还没放过是 -1
        int i = 0;
        int n = s.length;
        while (i < n) {
            if (isAlnum(s[i])) {
                out.appendCodePoint(s[i]);
                last = s[i];
                i++;
                continue;
            }
            // 从 i 到 j 是一段连续的非字母数字字符
            int j = i;
            while (j < n && !isAlnum(s[j])) {
                j++;
            }
            if (last >= 0 && j < n && isDigit(last) && isDigit(s[j])) {
                // 这一段夹在两个数字之间
                if (j - i == 1 && NUMBER_SEPARATORS.indexOf(s[i]) >= 0) {
                    out.appendCodePoint(s[i]);
                    last = s[i];
                } else if (!(glueDigitGroups && onlySpaces(s, i, j))) {
                    out.append(' ');
                    last = ' ';
                }
            }
            i = j;
        }
        return stripThousands(out.toString());
    }

    private static boolean onlySpaces(int[] s, int from, int to) {
        for (int k = from; k < to; k++) {
            if (s[k] != ' ' && s[k] != '\t') {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code piece} 在 {@code key} 里的位置，两者都已经是比对用的形式。对应 {@code _find}。
     *
     * <p>数字必须完整：不能从一个数的中间开始，也不能在一个数的中间结束。
     * 否则"23"会被认为出自"1234"，"1.5"会被认为出自"21.56"。
     *
     * @return 位置（按字数算，从 0 开始）；找不到返回 -1
     */
    public static int find(String piece, String key) {
        if (piece.isEmpty() || !key.contains(piece)) {
            return -1;
        }
        String pattern = Pattern.quote(piece);
        if (isDigit(piece.codePointAt(0))) {
            pattern = "(?<![\\d.,])" + pattern; // key 里的点和逗号只出现在两个数字之间
        }
        if (isDigit(piece.codePointBefore(piece.length()))) {
            pattern = pattern + "(?![\\d.,])";
        }
        Matcher m = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS).matcher(key);
        return m.find() ? key.codePointCount(0, m.start()) : -1;
    }

    /**
     * 把摘抄按换行和省略号分成几段。对应 {@code quote_parts}。
     *
     * <p>模型摘抄表格时常常引"表头那一行加上数据那一行"，中间换行；摘抄太长时中间用省略号略过。
     * 每一段都要各自是原文。没有实际内容的段（比如表格的分隔线 {@code | --- |}）去掉。
     */
    public static List<String> quoteParts(String quote) {
        List<String> out = new ArrayList<>();
        for (String part : BREAK.split(quote, -1)) {
            if (!matchKey(part).isEmpty()) {
                out.add(TextNorm.strip(part));
            }
        }
        return out;
    }

    /**
     * 是不是字母或数字，判断标准和 Python 的 {@code str.isalnum()} 相同。
     *
     * <p>不能直接用 {@link Character#isLetterOrDigit}：它不把"①""½""Ⅻ"这类字符算作数字，而 Python 算。
     */
    static boolean isAlnum(int cp) {
        if (Character.isLetter(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /** 是不是数字，判断标准和 Python 的 {@code str.isdigit()} 基本相同：十进制数字，以及"①""²"这类。 */
    static boolean isDigit(int cp) {
        int type = Character.getType(cp);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.OTHER_NUMBER;
    }
}
