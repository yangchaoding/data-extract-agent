package com.cb.agent.capability.document;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.model.PageInfo;
import com.cb.agent.capability.document.model.Table;
import com.cb.agent.core.trace.Region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link Document} 导出成普通的 Map 和 List，方便存成 JSON。
 *
 * <p>两个用处：
 * <ul>
 *   <li>给人看：命令行加 {@code --json} 时输出的就是它；</li>
 *   <li>差分测试：Python 版也导出同样的结构，两边逐项比对。所以字段名用的是 Python 版的写法
 *       （{@code page_end}、{@code header_rows}），其中 {@code regions}、{@code row_pages}、
 *       {@code pages}、{@code reader}、{@code warnings} 是 Java 版新增的，Python 版没有。</li>
 * </ul>
 *
 * <p>这里不依赖任何 JSON 库，只产出数据结构，由调用方决定怎么序列化。
 */
public final class DocumentDump {

    private DocumentDump() {
    }

    /** 导出一份文档。字段的顺序是固定的，输出才稳定、好比对。 */
    public static Map<String, Object> toMap(Document doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", doc.name());
        out.put("title", doc.title());
        out.put("page_count", doc.pageCount());
        out.put("source", doc.source());
        out.put("reader", doc.report().reader());
        out.put("warnings", doc.report().warnings());
        List<Map<String, Object>> pages = new ArrayList<>();
        for (PageInfo page : doc.pages()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("number", page.number());
            p.put("width", page.width());
            p.put("height", page.height());
            pages.add(p);
        }
        out.put("pages", pages);
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Block block : doc.blocks()) {
            blocks.add(blockToMap(block));
        }
        out.put("blocks", blocks);
        return out;
    }

    private static Map<String, Object> blockToMap(Block b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("kind", b.kind().code());
        m.put("label", b.label());
        m.put("text", b.text());
        m.put("page", b.page());
        m.put("page_end", b.pageEnd());
        m.put("bbox", b.bbox() == null ? null : b.bbox().toList());
        m.put("level", b.level());
        m.put("path", b.path());
        m.put("lead", b.lead());
        m.put("tokens", b.tokens());
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Region r : b.regions()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("page", r.page());
            rm.put("box", r.box() == null ? null
                    : List.of(r.box().x1(), r.box().y1(), r.box().x2(), r.box().y2()));
            rm.put("granularity", r.granularity().name());
            regions.add(rm);
        }
        m.put("regions", regions);
        m.put("table", b.table() == null ? null : tableToMap(b.table()));
        return m;
    }

    private static Map<String, Object> tableToMap(Table t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", t.rows());
        m.put("header_rows", t.headerRows());
        m.put("explicit_header", t.explicitHeader());
        // 每一行在第几页。跨页的表格靠它知道哪一行在哪一页
        List<Integer> rowPages = new ArrayList<>();
        for (int i = 0; i < t.rows().size(); i++) {
            Table.RowSource s = t.rowSource(i);
            rowPages.add(s == null ? null : s.page());
        }
        m.put("row_pages", rowPages);
        return m;
    }
}
