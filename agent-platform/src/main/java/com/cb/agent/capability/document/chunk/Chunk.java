package com.cb.agent.capability.document.chunk;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.BlockKind;
import com.cb.agent.capability.document.text.SourceText;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个切片：文档里连续的一段，大小适合放进一次模型调用。
 *
 * <p>对应 Python 版 {@code chunker.py} 的 {@code Chunk}。模型看到的原文就是一个个切片；
 * 模型回答时要说明证据出自哪个切片（用 {@link #id()}），程序再回到这个切片里核对。
 */
public final class Chunk {

    private final int index;
    private final String text;
    private final List<String> path;
    private final List<Block> blocks;
    private final Integer pageStart;
    private final Integer pageEnd;
    private final int tokens;
    private volatile SourceText source;

    /**
     * @param index     第几片，从 0 开始
     * @param text      这一片的文字
     * @param path      所在的章节路径：片里各个块的章节路径的公共部分
     * @param blocks    片里的版面块。一张大表拆成几段时，这几片共用同一个块
     * @param pageStart 起始页；没有页码时为 null
     * @param pageEnd   结束页；没有页码时为 null
     * @param tokens    估算的 token 数
     */
    public Chunk(int index, String text, List<String> path, List<Block> blocks,
                 Integer pageStart, Integer pageEnd, int tokens) {
        this.index = index;
        this.text = text;
        this.path = List.copyOf(path);
        this.blocks = List.copyOf(blocks);
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.tokens = tokens;
    }

    public int index() {
        return index;
    }

    /** 给模型看的编号：c1、c2、c3… */
    public String id() {
        return "c" + (index + 1);
    }

    public String text() {
        return text;
    }

    public List<String> path() {
        return path;
    }

    public List<Block> blocks() {
        return blocks;
    }

    public int tokens() {
        return tokens;
    }

    /** 片里的各个标题。 */
    public List<String> headings() {
        List<String> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b.kind() == BlockKind.HEADING) {
                out.add(b.text());
            }
        }
        return out;
    }

    public boolean hasTable() {
        for (Block b : blocks) {
            if (b.table() != null) {
                return true;
            }
        }
        return false;
    }

    /** 页码的文字写法：没有页码是空串，单页是 {@code "2"}，跨页是 {@code "2-3"}。 */
    public String pages() {
        if (pageStart == null) {
            return "";
        }
        if (pageEnd == null || pageEnd.equals(pageStart)) {
            return String.valueOf(pageStart);
        }
        return pageStart + "-" + pageEnd;
    }

    /** 章节路径的文字写法：{@code 第一章 > 第一节}。 */
    public String section() {
        return String.join(" > ", path);
    }

    /** 这一片的原文，带着比对用的缓存。核对证据时用。 */
    public SourceText source() {
        SourceText s = source;
        if (s == null) {
            s = new SourceText(text);
            source = s;
        }
        return s;
    }

    /**
     * 给模型看的形式：带着编号、章节、页码。对应 {@code Chunk.render}。
     * <pre>
     * &lt;chunk id="c3" section="第三条 合同价款" pages="2"&gt;
     * …这一片的文字…
     * &lt;/chunk&gt;
     * </pre>
     */
    public String render() {
        StringBuilder attrs = new StringBuilder("id=\"").append(id()).append('"');
        if (!path.isEmpty()) {
            attrs.append(" section=\"").append(section()).append('"');
        }
        if (!pages().isEmpty()) {
            attrs.append(" pages=\"").append(pages()).append('"');
        }
        return "<chunk " + attrs + ">\n" + text + "\n</chunk>";
    }
}
