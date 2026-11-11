package com.cb.agent.capability.document.reader;

import com.cb.agent.capability.document.text.TextNorm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据标题的编号样式推断它是第几级标题。对应 Python 版 {@code loader.py} 的
 * {@code heading_style} 和 {@code _Leveler}。
 *
 * <p>识别结果的 JSON 里只说了"这是个标题"，没说是几级。但标题的编号是有规律的：
 * "第一章"下面是"第一节"，"一、"下面是"（一）"，"1"下面是"1.1"。
 *
 * <p>做法是按样式第一次出现的先后定层级：先出现的样式层级高。遇到已经见过的样式，
 * 就回到它原来的层级，并且忘掉比它低的那些（下一章里的"一、"要重新算）。
 *
 * <p>编号不规范的文档推出来的层级可能不准。层级只影响章节路径（切片归属、检索加权、结果里的章节名），
 * 不影响抽取出来的值。
 */
public final class HeadingLeveler {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final String CN_NUM = "一二三四五六七八九十百零〇";

    /** 一种编号样式：名字和识别它的正则。 */
    private record Style(String name, Pattern pattern) {
    }

    /** 各种编号样式，按这个顺序匹配，先匹配上的算数。 */
    private static final List<Style> STYLES = List.of(
            new Style("part", Pattern.compile("^第[" + CN_NUM + "\\d]+[编篇部卷]", FLAGS)),
            new Style("chapter", Pattern.compile("^第[" + CN_NUM + "\\d]+章", FLAGS)),
            new Style("section", Pattern.compile("^第[" + CN_NUM + "\\d]+节", FLAGS)),
            new Style("article", Pattern.compile("^第[" + CN_NUM + "\\d]+条", FLAGS)),
            new Style("cn", Pattern.compile("^[" + CN_NUM + "]+[、．.]", FLAGS)),
            new Style("cn_paren", Pattern.compile("^[（(][" + CN_NUM + "]+[）)]", FLAGS)),
            new Style("num_paren", Pattern.compile("^(?:[（(]\\d{1,2}[）)]|\\d{1,2}[）)])", FLAGS)),
            new Style("word", Pattern.compile("^(?:chapter|section|part|article|appendix)\\s+\\w+",
                    FLAGS | Pattern.CASE_INSENSITIVE)),
            new Style("roman", Pattern.compile("^[IVX]+[、.．\\s]", FLAGS)));

    /**
     * 点分编号：1、1.2、1.2.3。后面紧跟着数字、年月日、百分号的不算（"2024年"、"3.5%"不是标题编号）。
     */
    private static final Pattern DOTTED =
            Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2})*)[、.．]?(?![\\d年月日%.．])", FLAGS);

    /** 已经出现过的样式，下标就是层级减一。 */
    private final List<String> stack = new ArrayList<>();

    /**
     * 标题的编号样式。
     *
     * @return 例如"第一章"是 {@code chapter}，"1.2"是 {@code num2}；没有编号时返回 null
     */
    public static String styleOf(String title) {
        String t = TextNorm.strip(title);
        for (Style style : STYLES) {
            if (style.pattern().matcher(t).find()) {
                return style.name();
            }
        }
        Matcher m = DOTTED.matcher(t);
        if (m.find()) {
            // 点的个数加一就是这个编号的深度：1 → num1，1.2 → num2
            long dots = m.group(1).chars().filter(ch -> ch == '.').count();
            return "num" + (dots + 1);
        }
        return null;
    }

    /**
     * 推断一个标题的层级，并记住它的样式。要按标题在文档里出现的顺序依次调用。
     *
     * @return 层级，1 到 6
     */
    public int level(String title) {
        String style = styleOf(title);
        if (style == null) {
            // 没有编号的标题：当成当前最深的那一级再往下一级
            return Math.min(stack.size() + 1, 6);
        }
        int at = stack.indexOf(style);
        if (at >= 0) {
            // 见过的样式：回到它的层级，比它低的样式作废
            while (stack.size() > at + 1) {
                stack.remove(stack.size() - 1);
            }
        } else {
            stack.add(style);
        }
        return Math.min(stack.size(), 6);
    }
}
