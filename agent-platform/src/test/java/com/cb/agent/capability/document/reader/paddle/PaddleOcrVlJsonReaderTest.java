package com.cb.agent.capability.document.reader.paddle;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.BlockKind;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.model.PixelBox;
import com.cb.agent.capability.document.model.Table;
import com.cb.agent.core.trace.Granularity;
import com.cb.agent.core.trace.Region;
import com.cb.agent.core.trace.TextOrigin;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读取器里 Java 版特有的行为。和 Python 版相同的部分在 {@link PaddleReaderGoldenTest} 里测。
 *
 * <p>三处是在真实的 PaddleOCR-VL 1.6 输出上发现问题之后改的：字面的 {@code \n}、
 * 标题被判成图题、跨页拼接后的坐标。另外还有读文件和读目录时的各种情况。
 */
class PaddleOcrVlJsonReaderTest {

    private final PaddleOcrVlJsonReader reader = new PaddleOcrVlJsonReader();

    // ---- 拼识别结果的小工具 -----------------------------------------------------------

    private static Map<String, Object> block(String label, String content, int... bbox) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("block_label", label);
        b.put("block_content", content);
        if (bbox.length == 4) {
            b.put("block_bbox", List.of(bbox[0], bbox[1], bbox[2], bbox[3]));
        }
        return b;
    }

    /** 一页的识别结果。{@code index} 为 null 表示没有 page_index（逐页调用服务时就是这样）。 */
    @SafeVarargs
    private static Map<String, Object> page(Integer index, Integer width, Integer height, Map<String, Object>... blocks) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("page_index", index);
        p.put("page_count", null);
        p.put("width", width);
        p.put("height", height);
        p.put("parsing_res_list", List.of(blocks));
        return p;
    }

    private Document read(Object data) {
        return reader.fromResult(Golden.tree(data), "doc", "test");
    }

    private static String html(String... rows) {
        StringBuilder sb = new StringBuilder("<table>");
        for (String row : rows) {
            sb.append("<tr>");
            for (String cell : row.split("\\|", -1)) {
                sb.append("<td>").append(cell).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    // ---- 修正一：表格单元格里字面的 \n ---------------------------------------------------

    @Test
    void 表格单元格里字面的反斜杠n变成换行() {
        // PaddleOCR-VL 用反斜杠加 n 两个字符表示单元格里的换行。Java 字符串里写成 "\\n"
        Document doc = read(page(0, 1200, 1700,
                block("doc_title", "标题"),
                block("table", html("简历|2011.07-2015.07 学习\\n2015.07-2015.09 待业", "备注|无"))));

        Block table = doc.blocks().get(1);
        assertEquals("2011.07-2015.07 学习\n2015.07-2015.09 待业", table.table().rows().get(0).get(1),
                "单元格里应该是真正的换行");
        assertTrue(table.text().contains("学习\\n2015.07"), "给模型看的文字里换行写成两个字符的标记：" + table.text());
        assertTrue(doc.report().warnings().stream().anyMatch(w -> w.contains("已按换行处理")),
                "读取报告里要记一笔：" + doc.report().warnings());
    }

    @Test
    void 正文里字面的反斜杠n不动() {
        // 只处理表格。正文里的反斜杠加 n 可能是内容本身（比如一个 Windows 路径）
        Document doc = read(page(0, 1200, 1700,
                block("doc_title", "标题"),
                block("text", "安装目录是 C:\\new\\name")));
        assertEquals("安装目录是 C:\\new\\name", doc.blocks().get(1).text());
        assertTrue(doc.report().warnings().isEmpty());
    }

    // ---- 修正二：文档最前面的图题当作文档标题 ---------------------------------------------

    @Test
    void 文档最前面的图题当作文档标题() {
        // 真实的 1.6 输出里，"干部任免审批表"这个标题被版面模型判成了 figure_title
        Document doc = read(page(0, 1191, 1684,
                block("figure_title", "干部任免审批表", 364, 185, 805, 242),
                block("table", html("姓名|张三", "性别|男"), 104, 257, 1083, 1453)));

        assertEquals("干部任免审批表", doc.title());
        Block title = doc.blocks().get(0);
        assertEquals(BlockKind.HEADING, title.kind());
        assertEquals(0, title.level(), "文档标题的层级是 0");
        assertEquals("figure_title", title.label(), "原始的类别名保留着，排查问题时能看出它原来是什么");
        assertTrue(doc.blocks().get(1).lead().isEmpty(), "标题不再是后面那张表的题注");
        assertTrue(doc.report().warnings().stream().anyMatch(w -> w.contains("已当作文档标题")));
    }

    @Test
    void 带表号的题注不当标题() {
        Document doc = read(page(0, 1200, 1700,
                block("table_title", "表1 主要财务指标"),
                block("table", html("项目|金额", "收入|100"))));

        assertNull(doc.title());
        assertEquals(BlockKind.CAPTION, doc.blocks().get(0).kind());
        assertEquals(List.of("表1 主要财务指标"), doc.blocks().get(1).lead(), "它仍然是后面那张表的题注");
        assertTrue(doc.report().warnings().isEmpty());
    }

    @Test
    void 前面有页眉不影响把图题当标题() {
        Document doc = read(page(0, 1200, 1700,
                block("header", "某某单位"),
                block("figure_title", "干部任免审批表"),
                block("text", "正文。")));
        assertEquals("干部任免审批表", doc.title());
    }

    @Test
    void 不在最前面的图题还是题注() {
        Document doc = read(page(0, 1200, 1700,
                block("doc_title", "年度报告"),
                block("figure_title", "组织结构图"),
                block("text", "正文。")));
        assertEquals("年度报告", doc.title());
        assertEquals(BlockKind.CAPTION, doc.blocks().get(1).kind());
    }

    // ---- 修正三：跨页的表格记住每一行来自哪一页、哪个框 -------------------------------------

    @Test
    void 跨页的表格每一行记着自己的页码每一页有自己的位置() {
        // 仿照真实的 1.6 输出：每页单独调一次服务，没有 page_index；两页的表格列数相同，被拼成一张
        Document doc = read(List.of(
                page(null, 1191, 1684,
                        block("doc_title", "审批表"),
                        block("table", html("姓名|张三", "性别|男", "民族|汉族"), 104, 257, 1083, 1453)),
                page(null, 1191, 1684,
                        block("table", html("奖惩情况|无", "任免理由|工作需要"), 126, 189, 1105, 1454),
                        block("vision_footnote", "填表人：某某单位", 138, 1457, 509, 1490))));

        assertEquals(2, doc.pageCount());
        assertEquals(3, doc.blocks().size(), "标题、拼成一张的表、脚注");
        Block block = doc.blocks().get(1);
        Table table = block.table();
        assertEquals("1-2", block.pages());
        assertEquals(5, table.rows().size());

        // 每一行在哪一页
        List<Integer> rowPages = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            rowPages.add(table.rowSource(i).page());
        }
        assertEquals(List.of(1, 1, 1, 2, 2), rowPages);
        // 第二页的行带着第二页那一段的框，不是第一页的
        assertEquals(new PixelBox(126, 189, 1105, 1454), table.rowSource(3).box());

        // 块在两页上各有一个位置，都换算成了相对页面的比例
        assertEquals(2, block.regions().size());
        Region first = block.regions().get(0);
        Region second = block.regions().get(1);
        assertEquals(1, first.page());
        assertEquals(2, second.page());
        assertEquals(Granularity.BLOCK, second.granularity());
        assertEquals(104 / 1191.0, first.box().x1(), 1e-9);
        assertEquals(257 / 1684.0, first.box().y1(), 1e-9);
        assertEquals(126 / 1191.0, second.box().x1(), 1e-9);
        assertEquals(189 / 1684.0, second.box().y1(), 1e-9);
        assertEquals(1454 / 1684.0, second.box().y2(), 1e-9);

        // 原始的像素框保留第一页那一段的，和 Python 版一致
        assertEquals(new PixelBox(104, 257, 1083, 1453), block.bbox());
        assertEquals(TextOrigin.OCR, block.origin());
    }

    @Test
    void 没有页面尺寸时只给页码() {
        Document doc = read(page(0, null, null,
                block("doc_title", "标题", 100, 100, 500, 160)));
        Region region = doc.blocks().get(0).regions().get(0);
        assertEquals(1, region.page());
        assertNull(region.box(), "不知道页面多大，就换算不出相对比例");
        assertEquals(Granularity.PAGE, region.granularity());
    }

    @Test
    void 合并成一页的结果没有页码也就没有位置() {
        Map<String, Object> p = page(null, 1200, 1700, block("doc_title", "标题", 100, 100, 500, 160));
        p.put("page_count", 3);
        Document doc = read(p);
        assertEquals(3, doc.pageCount());
        assertNull(doc.blocks().get(0).page());
        assertTrue(doc.blocks().get(0).regions().isEmpty());
    }

    // ---- 读文件、读目录 ---------------------------------------------------------------

    @Test
    void 不是识别结果时报错并说明原因() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> read(Map.of("foo", 1)));
        assertTrue(e.getMessage().contains("parsing_res_list"), e.getMessage());
    }

    @Test
    void 找不到文件时报错(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> reader.read(dir.resolve("没有这个文件.json")));
        assertTrue(e.getMessage().contains("找不到输入"), e.getMessage());
    }

    @Test
    void 目录里没有识别结果时报错(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("别的用途.json"), "{\"x\": 1}", StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> reader.read(dir));
        assertTrue(e.getMessage().contains("没有找到"), e.getMessage());
    }

    @Test
    void 文件开头的BOM不影响读取(@TempDir Path dir) throws IOException {
        String json = Golden.JSON.writeValueAsString(page(0, 1200, 1700, block("doc_title", "带 BOM 的文件")));
        Path file = dir.resolve("bom_0_res.json");
        Files.writeString(file, "\uFEFF" + json, StandardCharsets.UTF_8);

        List<Document> docs = reader.read(file);
        assertEquals(1, docs.size());
        assertEquals("带 BOM 的文件", docs.get(0).title());
        assertEquals("bom_0", docs.get(0).name(), "文档名取自文件名，去掉末尾的 _res");
    }

    @Test
    void 目录里的文件按原始文件名归成文档并按页码排序(@TempDir Path dir) throws IOException {
        // 两份文档混在一个目录里。a.pdf 有 11 页里的 3 页：第 1、2、11 页，文件名是 a_0、a_1、a_10
        write(dir, "a_10_res.json", pageOf("a.pdf", 10, "第十一页"));
        write(dir, "a_1_res.json", pageOf("a.pdf", 1, "第二页"));
        write(dir, "a_0_res.json", pageOf("a.pdf", 0, "第一页"));
        write(dir, "b_0_res.json", pageOf("D:\\扫描件\\b.pdf", 0, "另一份文档"));
        Files.writeString(dir.resolve("notes.json"), "{\"说明\": \"这不是识别结果\"}", StandardCharsets.UTF_8);

        List<Document> docs = reader.read(dir);

        assertEquals(List.of("a", "b"), docs.stream().map(Document::name).toList());
        Document a = docs.get(0);
        assertEquals(List.of("第一页", "第二页", "第十一页"), a.blocks().stream().map(Block::text).toList());
        assertEquals(List.of(1, 2, 11), a.blocks().stream().map(Block::page).toList());
        assertEquals(11, a.pageCount());
        assertEquals("b", docs.get(1).name(), "input_path 是 Windows 的写法也能取出文件名");
    }

    @Test
    void 文件名里的数字按大小排序() {
        assertTrue(PaddleOcrVlJsonReader.naturalCompare("p_2_res.json", "p_10_res.json") < 0);
        assertTrue(PaddleOcrVlJsonReader.naturalCompare("p_10_res.json", "p_2_res.json") > 0);
        assertEquals(0, PaddleOcrVlJsonReader.naturalCompare("A_1.json", "a_1.json"), "不分大小写");
        assertTrue(PaddleOcrVlJsonReader.naturalCompare("a.json", "b.json") < 0);
    }

    @Test
    void 能读的路径才说能读(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("x.json");
        Files.writeString(json, "{}", StandardCharsets.UTF_8);
        Path txt = dir.resolve("x.txt");
        Files.writeString(txt, "文字", StandardCharsets.UTF_8);
        Path empty = Files.createDirectory(dir.resolve("empty"));

        assertTrue(reader.supports(json));
        assertTrue(reader.supports(dir), "目录里有 JSON 文件");
        assertFalse(reader.supports(txt));
        assertFalse(reader.supports(empty), "目录里没有 JSON 文件");
    }

    private static Map<String, Object> pageOf(String inputPath, int index, String text) {
        Map<String, Object> p = page(index, 1200, 1700, block("text", text));
        p.put("input_path", inputPath);
        return p;
    }

    private static void write(Path dir, String name, Object data) throws IOException {
        Files.writeString(dir.resolve(name), Golden.JSON.writeValueAsString(data), StandardCharsets.UTF_8);
    }
}
