package com.cb.agent.capability.document.reader;

import com.cb.agent.capability.document.text.TextNorm;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 去掉块内容里附带的排版标记，只留正文。对应 Python 版 {@code loader.py} 的 {@code plain_text}。
 *
 * <p>识别结果开了 {@code format_block_content} 或者导出成 Markdown 时，块内容里会带着
 * {@code <div style=…>} 这类标签和图片的 Markdown 写法。这些不是文档的内容，留着会干扰检索和核对。
 */
public final class PlainText {

    /** 内容里出现这些片段才当成带标记的来处理。纯文字不走 HTML 解析，免得把"a &lt; b"这种写法弄坏。 */
    private static final String[] MARKUP_HINTS = {"<div", "<img", "<span", "<p>", "<p ", "<br", "<sup", "<sub", "</"};
    /** 这些标签的前后要换行，否则两段文字会粘在一起。 */
    private static final Set<String> BREAK_TAGS = Set.of("br", "p", "div", "tr", "li");
    /** Markdown 的图片写法：{@code ![说明](地址)}。 */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    /** 连续的空行压成一个换行。 */
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+", Pattern.UNICODE_CHARACTER_CLASS);

    private PlainText() {
    }

    /**
     * @param content 块的原始内容
     * @return 去掉标签和图片之后的文字，首尾没有空白
     */
    public static String of(String content) {
        String lowered = content.toLowerCase(Locale.ROOT);
        boolean hasMarkup = false;
        for (String hint : MARKUP_HINTS) {
            if (lowered.contains(hint)) {
                hasMarkup = true;
                break;
            }
        }
        if (hasMarkup) {
            content = stripTags(content);
        }
        content = MD_IMAGE.matcher(content).replaceAll("");
        return TextNorm.strip(BLANK_LINES.matcher(content).replaceAll("\n"));
    }

    /** 丢掉标签只留文字，块级标签的位置换行。 */
    private static String stripTags(String html) {
        StringBuilder out = new StringBuilder(html.length());
        Element body = Jsoup.parseBodyFragment(html).body();
        body.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(text.getWholeText());
                } else if (node instanceof Element el && BREAK_TAGS.contains(el.normalName())) {
                    out.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el && BREAK_TAGS.contains(el.normalName())) {
                    out.append('\n');
                }
            }
        });
        return out.toString();
    }
}
