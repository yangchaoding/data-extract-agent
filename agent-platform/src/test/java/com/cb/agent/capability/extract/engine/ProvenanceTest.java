package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.capability.extract.schema.SchemaLoader;
import com.cb.agent.core.trace.Box;
import com.cb.agent.core.trace.Granularity;
import com.cb.agent.core.trace.Locator;
import com.cb.agent.core.trace.Region;
import com.cb.agent.core.trace.TextOrigin;
import com.cb.agent.testsupport.Golden;
import com.cb.agent.testsupport.RuleLlm;
import com.cb.agent.testsupport.ScriptedLlm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出处（locator）的测试。这是 Java 版在 Python 版结果之上加的东西，没有基准可比，所以单独验证。
 *
 * <p>用的是仓库自带的合同样例：三页，付款计划表从第 2 页跨到第 3 页（第一、二期在第 2 页，第三、四期在第 3 页），
 * 读取时两段被拼成了一张表。Python 版对这张表里的每个值给的出处都是"第 2-3 页 + 第 2 页那一段的框"；
 * Java 版要给出这一行真正所在的那一页和那一段的框。
 */
class ProvenanceTest {

    /** 付款计划表在第 2 页那一段的框（像素）。 */
    private static final List<Integer> PAGE_2_BOX = List.of(120, 1430, 1120, 1620);
    /** 付款计划表在第 3 页那一段的框（像素）。 */
    private static final List<Integer> PAGE_3_BOX = List.of(120, 180, 1120, 370);
    /** 样例的页面大小（像素）。 */
    private static final int WIDTH = 1240;
    private static final int HEIGHT = 1754;

    private static final Schema PAYMENTS = schema("""
            {"fields": [{"name": "payments", "type": "list", "fields": [
                {"name": "stage"}, {"name": "condition"},
                {"name": "ratio", "type": "number"}, {"name": "amount", "type": "number"}]}]}
            """);
    private static final Schema AMOUNT = schema("""
            {"fields": [{"name": "total_amount", "type": "number"}]}
            """);

    @Test
    void 跨页表格里的值给出它所在的那一页和那一段的框() {
        RuleLlm llm = new RuleLlm(Map.of("payments", RuleLlm.Rule.items(
                "\\| (第.期) \\| (.+?) \\| (\\d+)% \\| ([\\d,.]+) \\|", "stage", "condition", "ratio", "amount")));
        FieldResult result = run(llm, PAYMENTS).fields().get("payments");

        assertEquals(FieldResult.OK, result.status());
        assertEquals(4, result.items().size());
        List<Integer> pages = List.of(2, 2, 3, 3);
        for (int i = 0; i < 4; i++) {
            Item item = result.items().get(i);
            int page = pages.get(i);
            List<Integer> box = page == 2 ? PAGE_2_BOX : PAGE_3_BOX;
            String which = "第 " + (i + 1) + " 期";

            // 整张表跨两页，块本身的页码范围是 2-3
            assertEquals("2-3", item.block().pages(), which);
            // 简略的出处：这一行所在的那一页，和那一页上那一段表格的框
            Map<String, Object> map = item.toMap();
            assertEquals(String.valueOf(page), map.get("pages"), which + " 的页码");
            assertEquals(box, map.get("bbox"), which + " 的框");

            // 完整的出处
            Locator locator = item.locator();
            assertNotNull(locator, which + " 应该有出处");
            assertEquals(TextOrigin.OCR, locator.origin(), "文字是识别出来的");
            assertEquals(1, locator.evidenceRegions().size(), which + " 的证据只落在一页上");
            Region region = locator.evidenceRegions().get(0);
            assertEquals(page, region.page(), which + " 的证据所在的页");
            assertEquals(Granularity.BLOCK, region.granularity(), "PaddleOCR-VL 只给到版面块的框");
            assertEquals(Box.fromPixels(box.get(0), box.get(1), box.get(2), box.get(3), WIDTH, HEIGHT), region.box(),
                    which + " 的框换算成相对比例");
            assertTrue(locator.valueRegions().isEmpty(), "值本身的位置现在给不出来");
        }
    }

    @Test
    void 导出的结果里带着出处() {
        RuleLlm llm = new RuleLlm(Map.of("total_amount", RuleLlm.Rule.value("含税总价为人民币([\\d,.]+)元")));
        Map<String, Object> field = fieldMap(run(llm, AMOUNT), "total_amount");

        assertEquals("2", field.get("pages"));
        assertEquals(List.of(120, 210, 1120, 290), field.get("bbox"));
        @SuppressWarnings("unchecked")
        Map<String, Object> locator = (Map<String, Object>) field.get("locator");
        assertEquals("OCR", locator.get("origin"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regions = (List<Map<String, Object>>) locator.get("evidence_regions");
        assertEquals(1, regions.size());
        assertEquals(2, regions.get(0).get("page"));
        assertEquals("BLOCK", regions.get(0).get("granularity"));
        Box expected = Box.fromPixels(120, 210, 1120, 290, WIDTH, HEIGHT);
        assertEquals(List.of(expected.x1(), expected.y1(), expected.x2(), expected.y2()), regions.get(0).get("box"));
        assertEquals(List.of(), locator.get("value_regions"));
    }

    @Test
    void 证据引了表头加一行时以数据行所在的页为准() {
        // 表头在每一页的续表上都重复了一遍，单看表头分不清是哪一页；数据行只在第 3 页
        String header = "| 期次 | 付款节点 | 比例 | 金额（元） |";
        String row = "| 第三期 | 系统上线并经甲方验收合格后10个工作日内 | 40% | 512,000.00 |";
        ScriptedLlm llm = new ScriptedLlm(List.of(Map.of("payments", List.of(Map.of(
                "value", Map.of("stage", "第三期", "amount", "512,000.00"),
                "evidence", header + "\n" + row, "chunk", "c1")))));
        Item item = run(llm, PAYMENTS).fields().get("payments").items().get(0);

        assertTrue(item.verified(), String.valueOf(item.issues()));
        assertEquals("3", item.toMap().get("pages"));
        assertEquals(PAGE_3_BOX, item.toMap().get("bbox"));
        assertEquals(List.of(3), item.locator().evidenceRegions().stream().map(Region::page).toList());
    }

    @Test
    void 证据不是原文时没有出处() {
        ScriptedLlm llm = new ScriptedLlm(List.of(Map.of("total_amount", Map.of(
                "value", 2000000, "evidence", "乙方应于每年三月前提交审计报告", "chunk", "c9"))));
        ExtractionResult result = run(llm, AMOUNT);
        Item item = result.fields().get("total_amount").items().get(0);

        assertFalse(item.verified());
        assertNull(item.block(), "编出来的证据落不到任何版面块上");
        assertNull(item.locator());
        assertFalse(fieldMap(result, "total_amount").containsKey("locator"));
    }

    private static ExtractionResult run(com.cb.agent.core.llm.LlmClient llm, Schema schema) {
        Document doc = new PaddleOcrVlJsonReader()
                .read(Golden.FIXTURES.resolve("paddle").resolve("sample_contract")).get(0);
        return new ExtractionEngine(llm, EngineOptions.DEFAULTS).run(doc, schema);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldMap(ExtractionResult result, String name) {
        return (Map<String, Object>) ((Map<String, Object>) result.toMap().get("fields")).get(name);
    }

    private static Schema schema(String json) {
        return SchemaLoader.fromMap(Golden.plain(Golden.JSON.readTree(json)));
    }
}
