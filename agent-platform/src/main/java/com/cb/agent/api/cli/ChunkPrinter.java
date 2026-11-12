package com.cb.agent.api.cli;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.text.TextNorm;

import java.io.PrintStream;
import java.util.List;

/**
 * 把切片结果打印出来。{@code chunks} 命令用它。
 *
 * <p>格式和 Python 版的 {@code extract-agent chunks} 相同：每片一行概况（编号、页码、token 数、章节），
 * 下面是开头一小段，或者全文。
 */
final class ChunkPrinter {

    /** 不显示全文时，每片最多显示这么多字。 */
    private static final int PREVIEW_CHARS = 70;

    private ChunkPrinter() {
    }

    /**
     * @param fullText true 打印每片的全文，片与片之间用一条横线隔开；false 只打印开头一小段
     */
    static void print(PrintStream out, Document doc, List<Chunk> chunks, boolean fullText) {
        String pages = doc.pageCount() == null ? "" : doc.pageCount() + " 页，";
        out.printf("# %s：%s%d 个版面块，约 %d token，切成 %d 片%n",
                doc.name(), pages, doc.blocks().size(), doc.tokens(), chunks.size());
        for (Chunk c : chunks) {
            String where = c.pages().isEmpty() ? "-" : "第" + c.pages() + "页";
            String section = c.section().isEmpty() ? "-" : c.section();
            String mark = c.hasTable() ? " [含表格]" : "";
            out.printf("%5s | %8s | %5d token | %s%s%n", c.id(), where, c.tokens(), section, mark);
            if (fullText) {
                out.println(c.text());
                out.println("-".repeat(60));
            } else {
                out.println("        " + TextNorm.oneLine(c.text(), PREVIEW_CHARS));
            }
        }
    }
}
