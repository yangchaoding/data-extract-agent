package com.cb.agent.capability.document.table;

import com.cb.agent.capability.document.model.Table;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格解析的测试。
 *
 * <p>前半部分是差分测试：和 Python 版对同一批内容解析出的结果逐项比较。
 * 后半部分测 Java 版特有的行为：单元格里的换行，以及每一行的来源。
 */
class TableParserTest {

    // ---- 差分测试 -------------------------------------------------------------------

    @Test
    void 和Python版解析出的表格逐项相同() {
        JsonNode cases = Golden.read(Golden.GOLDEN.resolve("tables.json"));
        assertTrue(cases.size() > 20, "基准数据太少，可能没有生成完整");
        for (JsonNode c : cases.values()) {
            String content = c.get("content").stringValue();
            String label = "内容：[" + content + "]";
            List<Table> tables = TableParser.fromContent(content);
            JsonNode expected = c.get("tables");
            assertEquals(expected.size(), tables.size(), "解析出的表格张数不一致。" + label);
            for (int i = 0; i < tables.size(); i++) {
                Table table = tables.get(i);
                JsonNode e = expected.get(i);
                assertEquals(e.get("rows"), Golden.tree(table.rows()), "网格不一致。" + label);
                assertEquals(e.get("header_rows").intValue(), table.headerRows(), "表头行数不一致。" + label);
                assertEquals(e.get("explicit_header").booleanValue(), table.explicitHeader(), "表头是否明确标出不一致。" + label);
                assertEquals(e.get("n_cols").intValue(), table.nCols(), "列数不一致。" + label);
                assertEquals(e.get("render").stringValue(), table.render(), "渲染结果不一致。" + label);
                List<List<Integer>> ranges = new ArrayList<>();
                for (int[] r : table.splitRows(40)) {
                    ranges.add(List.of(r[0], r[1]));
                }
                assertEquals(e.get("split_40"), Golden.tree(ranges), "按行拆分的结果不一致。" + label);
            }
        }
    }

    // ---- 单元格里的换行（Java 版和 Python 版故意不同的地方）-----------------------------

    @Test
    void 单元格里的br保留成换行() {
        Table table = TableParser.parseHtml(
                "<table><tr><td>简历</td><td>2011.07-2015.07 学习<br>2015.07-2015.09 待业<br/>2015.09-今 工作</td></tr>"
                        + "<tr><td>备注</td><td>无</td></tr></table>").get(0);
        assertEquals("2011.07-2015.07 学习\n2015.07-2015.09 待业\n2015.09-今 工作", table.rows().get(0).get(1));
    }

    @Test
    void 渲染时单元格里的换行写成两个字符的标记() {
        Table table = TableParser.parseHtml("<table><tr><td>a</td><td>第一行<br>第二行</td></tr><tr><td>b</td><td>c</td></tr></table>").get(0);
        String rendered = table.render();
        // 管道表的一行里不能有真正的换行，否则一行会断成两行
        assertEquals(3, rendered.split("\n").length, "应该是表头、分隔线、一行数据，共三行");
        assertTrue(rendered.contains("第一行\\n第二行"), "换行应该渲染成反斜杠加 n：" + rendered);
    }

    @Test
    void HTML源码里为了排版加的换行不算内容() {
        Table table = TableParser.parseHtml("<table>\n  <tr>\n    <td>姓\n名</td>\n    <td>张三</td>\n  </tr>\n  <tr><td>a</td><td>b</td></tr>\n</table>").get(0);
        assertEquals("姓 名", table.rows().get(0).get(0));
    }

    @Test
    void 长短按渲染出来的样子算() {
        // 这个单元格渲染出来是 25 个字（两处换行各占两个字符），超过 24，所以展开时用"同上"代替。
        // 真实的 PaddleOCR-VL 输出里"年度考核结果"一栏就是这个情况。
        String cell = "2020：称职<br>2021：优秀<br>2022：称职";
        Table table = TableParser.parseHtml("<table><tr><td>结果</td><td colspan=\"3\">" + cell + "</td></tr>"
                + "<tr><td>a</td><td>b</td><td>c</td><td>d</td></tr></table>").get(0);
        assertEquals(List.of("结果", "2020：称职\n2021：优秀\n2022：称职", Table.DITTO, Table.DITTO), table.rows().get(0));
    }

    // ---- 每一行的来源 -----------------------------------------------------------------

    @Test
    void 接上续表之后每一行还记着自己来自哪一页() {
        Table first = TableParser.parseHtml("<table><tr><th>项目</th><th>金额</th></tr><tr><td>收入</td><td>100</td></tr></table>").get(0);
        first.markSource(new Table.RowSource(1, null));
        Table next = TableParser.parseHtml("<table><tr><th>项目</th><th>金额</th></tr><tr><td>成本</td><td>80</td></tr><tr><td>利润</td><td>20</td></tr></table>").get(0);
        next.markSource(new Table.RowSource(2, null));

        assertTrue(first.absorb(next));

        assertEquals(4, first.rows().size(), "续表开头重复的表头应该去掉：表头 1 行加数据 3 行");
        assertEquals(List.of(1, 2), first.pages());
        assertEquals(1, first.rowSource(0).page());
        assertEquals(1, first.rowSource(1).page());
        assertEquals(2, first.rowSource(2).page());
        assertEquals(2, first.rowSource(3).page());
    }

    @Test
    void 列数不同不接() {
        Table a = TableParser.parseHtml("<table><tr><td>1</td><td>2</td></tr><tr><td>3</td><td>4</td></tr></table>").get(0);
        Table b = TableParser.parseHtml("<table><tr><td>1</td><td>2</td><td>3</td></tr><tr><td>4</td><td>5</td><td>6</td></tr></table>").get(0);
        assertFalse(a.absorb(b));
        assertEquals(2, a.rows().size(), "没接上时原来的表不能被改动");
    }

    @Test
    void 下一页的表有自己不同的表头时是另一张表() {
        Table a = TableParser.parseHtml("<table><tr><th>甲</th><th>乙</th></tr><tr><td>1</td><td>2</td></tr></table>").get(0);
        Table b = TableParser.parseHtml("<table><tr><th>丙</th><th>丁</th></tr><tr><td>3</td><td>4</td></tr></table>").get(0);
        assertFalse(a.absorb(b));
    }

    // ---- 三种写法的入口 ---------------------------------------------------------------

    @Test
    void 不是表格的内容返回空列表() {
        assertTrue(TableParser.fromContent("不是表格的普通文字").isEmpty());
        assertTrue(TableParser.fromContent("").isEmpty());
        assertNull(TableParser.parseOtsl("没有任何标记"));
        assertNull(TableParser.parsePipe("只有一行 | 不算表格"));
    }

    @Test
    void OTSL标记能解析成表格() {
        Table table = TableParser.parseOtsl("<fcel>姓名<fcel>年龄<nl><fcel>张三<fcel>30<nl>");
        assertNotNull(table);
        assertEquals(List.of(List.of("姓名", "年龄"), List.of("张三", "30")), table.rows());
        assertEquals(1, table.headerRows());
    }
}
