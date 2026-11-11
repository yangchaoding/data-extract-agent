package com.cb.agent.api.cli;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.BlockKind;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.model.PageInfo;
import com.cb.agent.capability.document.model.Table;
import com.cb.agent.capability.document.text.TextNorm;
import com.cb.agent.core.trace.Region;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把一份读进来的文档打印成人能看懂的样子。{@code inspect} 命令用它。
 *
 * <p>目的是让人一眼看出文档被读成了什么：有几页、几个块，每个块是什么类别、在第几页、框在哪，
 * 表格有几行几列、每一页各有几行，读取时有没有做什么特别的处理。
 */
final class DocumentPrinter {

    /** 类别的中文名。 */
    private static final Map<BlockKind, String> KIND_NAMES = Map.of(
            BlockKind.HEADING, "标题",
            BlockKind.TEXT, "正文",
            BlockKind.TABLE, "表格",
            BlockKind.CAPTION, "题注",
            BlockKind.FOOTNOTE, "脚注",
            BlockKind.SEAL, "印章",
            BlockKind.FORMULA, "公式",
            BlockKind.TOC, "目录");

    /** 不显示全文时，每个块最多显示这么多字。 */
    private static final int PREVIEW_CHARS = 80;

    private DocumentPrinter() {
    }

    /**
     * @param out      输出到哪里
     * @param doc      要打印的文档
     * @param fullText true 打印每个块的全文；false 只打印开头一小段
     */
    static void print(PrintStream out, Document doc, boolean fullText) {
        String pages = doc.pageCount() == null ? "" : doc.pageCount() + " 页，";
        out.printf("# %s：%s%d 个版面块，约 %d token%n", doc.name(), pages, doc.blocks().size(), doc.tokens());
        out.println("  来源：" + doc.source());
        out.println("  读取器：" + doc.report().reader());
        out.println("  标题：" + (doc.title() == null ? "（没有识别出标题）" : doc.title()));
        if (!doc.pages().isEmpty()) {
            List<String> sizes = new ArrayList<>();
            for (PageInfo p : doc.pages()) {
                String size = p.width() == null || p.height() == null ? "尺寸未知" : p.width() + "×" + p.height();
                sizes.add("第" + p.number() + "页 " + size);
            }
            out.println("  页面：" + String.join("，", sizes));
        }
        if (!doc.report().warnings().isEmpty()) {
            out.println("  注意：");
            for (String warning : doc.report().warnings()) {
                out.println("    - " + warning);
            }
        }
        out.println();
        for (Block block : doc.blocks()) {
            printBlock(out, block, fullText);
        }
    }

    private static void printBlock(PrintStream out, Block b, boolean fullText) {
        String kind = KIND_NAMES.get(b.kind());
        if (b.level() != null) {
            kind += b.level() == 0 ? "（文档标题）" : "（" + b.level() + " 级）";
        }
        String where = b.pages().isEmpty() ? "页码未知" : "第" + b.pages() + "页";
        String box = b.bbox() == null ? "没有坐标"
                : "框 [" + b.bbox().x1() + ", " + b.bbox().y1() + ", " + b.bbox().x2() + ", " + b.bbox().y2() + "]";
        out.printf("  [%d] %s | 类别 %s | %s | %d token | %s%n", b.id(), kind, b.label(), where, b.tokens(), box);

        if (!b.path().isEmpty()) {
            out.println("      章节：" + String.join(" > ", b.path()));
        }
        if (!b.lead().isEmpty()) {
            out.println("      题注：" + String.join("；", b.lead()));
        }
        if (b.table() != null) {
            out.println("      表格：" + describeTable(b.table()));
        }
        if (b.regions().size() > 1) {
            // 跨页的块每页一个位置。这是和只记一个框相比最要紧的区别，所以单独打印出来
            List<String> parts = new ArrayList<>();
            for (Region r : b.regions()) {
                parts.add("第" + r.page() + "页 " + (r.box() == null ? "没有框" : String.format(Locale.ROOT,
                        "(%.3f, %.3f, %.3f, %.3f)", r.box().x1(), r.box().y1(), r.box().x2(), r.box().y2())));
            }
            out.println("      各页的位置（相对页面的比例）：" + String.join("；", parts));
        }
        if (fullText) {
            for (String line : b.text().split("\n", -1)) {
                out.println("      " + line);
            }
        } else {
            out.println("      " + TextNorm.oneLine(b.text(), PREVIEW_CHARS));
        }
        out.println();
    }

    /** 表格的概况：几行几列，表头几行，每一页各有几行。 */
    private static String describeTable(Table t) {
        Map<String, Integer> perPage = new LinkedHashMap<>();
        for (int i = 0; i < t.rows().size(); i++) {
            Table.RowSource s = t.rowSource(i);
            String page = s == null || s.page() == null ? "页码未知" : "第" + s.page() + "页";
            perPage.merge(page, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        perPage.forEach((page, n) -> parts.add(page + " " + n + " 行"));
        return t.rows().size() + " 行 × " + t.nCols() + " 列，表头 " + t.headerRows() + " 行；" + String.join("，", parts);
    }
}
