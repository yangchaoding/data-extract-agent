package com.cb.agent.capability.document.model;

import java.util.List;

/**
 * 一份文档的统一表示：按阅读顺序排列的版面块。
 *
 * <p>对应 Python 版 {@code document.py} 的 {@code Document}。不管文件原来是什么格式、
 * 用哪个引擎识别的，读取之后都变成这个结构。后面的切片、抽取、问答只认它，不关心来源。
 *
 * @param name      文档名，一般取自原始文件名
 * @param source    是从哪个文件或目录读进来的
 * @param blocks    版面块，按阅读顺序
 * @param title     文档标题；没有识别出来时为 null
 * @param pageCount 总页数；无法确定时为 null
 * @param pages     每一页的信息，按页码排列。来源没有给出页面尺寸时列表可能为空
 * @param report    读取报告
 */
public record Document(String name, String source, List<Block> blocks, String title,
                       Integer pageCount, List<PageInfo> pages, IngestReport report) {

    public Document {
        blocks = List.copyOf(blocks);
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** 全文估算的 token 数。 */
    public int tokens() {
        int sum = 0;
        for (Block block : blocks) {
            sum += block.tokens();
        }
        return sum;
    }

    /**
     * 按页码找页面信息。
     *
     * @return 这一页的信息；页码为 null 或者没有这一页的记录时返回 null
     */
    public PageInfo page(Integer number) {
        if (number == null) {
            return null;
        }
        for (PageInfo page : pages) {
            if (page.number() == number) {
                return page;
            }
        }
        return null;
    }
}
