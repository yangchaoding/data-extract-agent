package com.cb.agent.capability.extract.value;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.BlockKind;
import com.cb.agent.capability.extract.schema.FieldSpec;
import com.cb.agent.capability.extract.schema.SchemaLoader;
import com.cb.agent.core.trace.TextOrigin;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数字、单位、日期的读取，以及值和证据的核对：和 Python 版对同一批输入的结果逐项比较。
 *
 * <p>这是方案里标出的最容易出行为差异的一块。用例是有针对性的：万元换算、表头里的单位、
 * 题注里的"单位：万元"、模型自己做了换算、单位写错、括号表示的负数、中文大写金额、
 * 从两个日期里各取一部分凑出来的日期，等等。
 */
class ValuesGoldenTest {

    private static final JsonNode GOLDEN = Golden.read(Golden.GOLDEN.resolve("values.json"));

    @Test
    void 读出的数和它带的单位和Python版相同() {
        JsonNode cases = GOLDEN.get("numbers");
        assertTrue(cases.size() >= 30, "基准数据太少");
        for (JsonNode c : cases.values()) {
            Object raw = Golden.plain(c.get("raw"));
            String label = "输入：" + c.get("raw");
            Numbers.Num num = Numbers.readNumber(raw);
            if (!c.get("readable").booleanValue()) {
                assertNull(num, "应该读不出数。" + label);
                continue;
            }
            assertNotNull(num, "应该读得出数。" + label);
            assertEquals(0, new BigDecimal(c.get("value").stringValue()).compareTo(num.value()),
                    "数值不一致：期望 " + c.get("value").stringValue() + "，实际 " + num.value() + "。" + label);
            assertEquals(Golden.canon(Golden.plain(c.get("scale"))), Golden.canon(num.scale()), "单位的数量级不一致。" + label);
        }
    }

    @Test
    void 单位的数量级和Python版相同() {
        for (JsonNode c : GOLDEN.get("units").values()) {
            String unit = c.get("unit").stringValue();
            assertEquals(Golden.canon(Golden.plain(c.get("scale"))), Golden.canon(Numbers.unitScale(unit)), "单位：[" + unit + "]");
        }
    }

    @Test
    void 中文数字的换算和Python版相同() {
        for (JsonNode c : GOLDEN.get("cn").values()) {
            String text = c.get("text").stringValue();
            assertEquals(c.get("arabic").stringValue(), Numbers.arabicNumerals(text), "逐位写的中文数字：[" + text + "]");
        }
        for (JsonNode c : GOLDEN.get("cn_amounts").values()) {
            String text = c.get("text").stringValue();
            BigInteger expected = c.get("parsed").isNull() ? null : c.get("parsed").bigIntegerValue();
            assertEquals(expected, Numbers.parseCnNumber(text), "带单位的中文数字：[" + text + "]");
        }
    }

    @Test
    void 日期的归一化和Python版相同() {
        for (JsonNode c : GOLDEN.get("dates").values()) {
            Object raw = Golden.plain(c.get("raw"));
            assertEquals(Golden.plain(c.get("parsed")), Dates.parse(raw), "输入：" + c.get("raw"));
        }
    }

    @Test
    void 日期是不是写在证据里的判断和Python版相同() {
        for (JsonNode c : GOLDEN.get("date_in").values()) {
            String value = c.get("value").stringValue();
            String evidence = c.get("evidence").stringValue();
            assertEquals(c.get("result").booleanValue(), Dates.in(value, evidence),
                    "日期 " + value + "，证据：[" + evidence + "]");
        }
    }

    @Test
    void 值的整理和核对的结果和Python版相同() {
        JsonNode cases = GOLDEN.get("read");
        assertTrue(cases.size() >= 50, "基准数据太少");
        int i = 0;
        for (JsonNode c : cases.values()) {
            FieldSpec field = SchemaLoader.fromMap(Map.of("fields", List.of(Golden.plain(c.get("field"))))).fields().get(0);
            Object raw = Golden.plain(c.get("raw"));
            String evidence = c.get("evidence").stringValue();
            Chunk chunk = c.get("chunk").isNull() ? null : chunk(c.get("chunk").stringValue(), 0);
            Block block = c.get("block").isNull() ? null : block(c.get("block").stringValue(), c.get("lead"));
            String label = "第 " + (i++) + " 个用例：字段 " + c.get("field") + "，值 " + c.get("raw") + "，证据 [" + evidence + "]";

            ValueReader.Read read = ValueReader.readValue(field, raw, evidence, chunk, block);

            assertEquals(Golden.canon(Golden.plain(c.get("value"))), Golden.canon(read.value()), "整理出的值不一致。" + label);
            assertEquals(c.get("issues"), Golden.tree(read.issues()), "发现的问题不一致。" + label);
        }
    }

    @Test
    void 证据的定位和Python版相同() {
        List<Chunk> chunks = new ArrayList<>();
        int n = 0;
        for (JsonNode t : GOLDEN.get("locate_chunks").values()) {
            chunks.add(chunk(t.stringValue(), n++));
        }
        for (JsonNode c : GOLDEN.get("locate").values()) {
            String evidence = c.get("evidence").stringValue();
            Chunk claimed = c.get("claimed").isNull() ? null : chunks.get(c.get("claimed").intValue());
            String label = "证据：[" + evidence + "]，声称在 " + c.get("claimed");

            ValueReader.Located located = ValueReader.locateEvidence(evidence, claimed, chunks);

            Integer home = located.chunk() == null ? null : located.chunk().index();
            assertEquals(Golden.plain(c.get("home")), home, "定位到的片段不一致。" + label);
            assertEquals(c.get("issues"), Golden.tree(located.issues()), "发现的问题不一致。" + label);
        }
    }

    private static Chunk chunk(String text, int index) {
        return new Chunk(index, text, List.of(), List.of(), null, null, 0);
    }

    private static Block block(String text, JsonNode lead) {
        List<String> leads = new ArrayList<>();
        for (JsonNode l : lead.values()) {
            leads.add(l.stringValue());
        }
        return new Block(0, BlockKind.TABLE, "table", text, null, null, null, null, null,
                List.of(), leads, 0, List.of(), TextOrigin.OCR);
    }
}
