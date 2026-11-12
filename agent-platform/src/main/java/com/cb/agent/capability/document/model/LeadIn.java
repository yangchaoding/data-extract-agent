package com.cb.agent.capability.document.model;

import java.util.regex.Pattern;

/**
 * 判断一个块是不是"引子"：题注，或者单独成行的单位说明、表号。
 * 对应 Python 版 {@code document.py} 的 {@code is_lead_in}。
 *
 * <p>引子说的是紧跟在它后面的那个块（"表3 主要财务指标""单位：万元"说的是下面那张表）。
 * 所以读取时要把它记到后面那个块上，切片时要让它和后面的内容待在同一片里。
 * 读取器和切片器都要用到这个判断，所以单独放在这里。
 */
public final class LeadIn {

    private static final int FLAGS =
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** 单独成行的单位说明："单位：万元"、"币种：人民币"。 */
    public static final Pattern UNIT_LINE = Pattern.compile("单位\\s*[:：为]|币种\\s*[:：]|\\bunit\\s*[:：]", FLAGS);

    /** 表号："表3"、"续表2"、"附表一"、"Table 1"。只在开头出现才算。 */
    public static final Pattern TABLE_NO = Pattern.compile("^(?:续?表|附表|table)\\s*[\\d一二三四五六七八九十]", FLAGS);

    /** 单位说明和表号都是很短的一行。超过这么多 token 的正文不算引子。 */
    private static final int MAX_TOKENS = 40;

    private LeadIn() {
    }

    /**
     * @param kind   块的类别
     * @param tokens 块估算的 token 数
     * @param text   块的文字
     */
    public static boolean is(BlockKind kind, int tokens, String text) {
        if (kind == BlockKind.CAPTION) {
            return true;
        }
        return kind == BlockKind.TEXT && tokens <= MAX_TOKENS
                && (UNIT_LINE.matcher(text).find() || TABLE_NO.matcher(text).find());
    }

    public static boolean is(Block block) {
        return is(block.kind(), block.tokens(), block.text());
    }
}
