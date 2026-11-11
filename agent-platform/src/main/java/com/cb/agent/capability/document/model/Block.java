package com.cb.agent.capability.document.model;

import com.cb.agent.core.trace.Region;
import com.cb.agent.core.trace.TextOrigin;

import java.util.List;

/**
 * 一个版面块：文档按阅读顺序排列的最小单位，例如一个标题、一段正文、一张表。
 *
 * <p>对应 Python 版 {@code document.py} 的 {@code Block}。读取结束之后内容不再变化，所以是不可变的。
 *
 * @param id      块的序号，在一份文档里唯一，从 0 开始
 * @param kind    归一化之后的类别
 * @param label   来源里原始的类别名（PaddleOCR-VL 的 {@code block_label}）
 * @param text    块的文字。表格块是渲染好的管道表
 * @param page    所在的页，从 1 开始；无法确定时为 null
 * @param pageEnd 跨页拼接的表格的结束页；没有跨页时为 null
 * @param bbox    识别结果给出的框（像素）。跨页的表格这里是第一页那一段的框，其余各段见 {@link #regions}
 * @param table   表格的网格；不是表格时为 null
 * @param level   标题的层级：0 是文档标题，1 是一级标题；不是标题时为 null
 * @param path    所在的章节路径，从外到内。标题块的路径包含它自己
 * @param lead    紧挨在这个块前面的题注和单位说明，例如"表3 …"、"单位：万元"
 * @param tokens  估算的 token 数
 * @param regions 这个块在原件上的位置，相对比例。跨页的表格每页一个
 * @param origin  文字是怎么来的
 */
public record Block(int id, BlockKind kind, String label, String text,
                    Integer page, Integer pageEnd, PixelBox bbox,
                    Table table, Integer level,
                    List<String> path, List<String> lead,
                    int tokens, List<Region> regions, TextOrigin origin) {

    public Block {
        path = path == null ? List.of() : List.copyOf(path);
        lead = lead == null ? List.of() : List.copyOf(lead);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /**
     * 页码的文字写法，和 Python 版的 {@code Block.pages} 相同：
     * 没有页码是空串，单页是 {@code "2"}，跨页是 {@code "2-3"}。
     */
    public String pages() {
        if (page == null) {
            return "";
        }
        if (pageEnd == null || pageEnd.equals(page)) {
            return String.valueOf(page);
        }
        return page + "-" + pageEnd;
    }
}
