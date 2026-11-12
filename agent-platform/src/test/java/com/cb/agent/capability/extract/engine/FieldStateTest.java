package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.extract.schema.FieldSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个字段的抽取过程：每一轮看哪些片段、收到结果之后怎么去重、什么时候停。
 *
 * <p>这几条规则在整体流程里被切片和提示词挡住了，看不清，所以单独测。
 */
class FieldStateTest {

    private static final FieldSpec AMOUNT = field("total_amount", "number", false, null, false, List.of());
    private static final FieldSpec PAYMENTS = field("payments", "list", false, null, false, List.of(
            field("stage", "string", false, null, false, List.of()),
            field("amount", "number", false, null, false, List.of())));

    @Test
    void 标量字段每一轮取排在最前面的几个片段且跳过看过的() {
        FieldState state = new FieldState(AMOUNT, List.of(0, 1, 2, 3, 4), 2);

        assertEquals(List.of(0, 1), state.take(Map.of()));
        state.add(List.of(), Set.of(0, 1));
        state.settle(5);
        assertFalse(state.done(), "还没找到，继续往后看");

        assertEquals(List.of(2, 3), state.take(Map.of()));
        state.add(List.of(item(1280000L, "含税总价为人民币1,280,000.00元", chunk(2))), Set.of(2, 3));
        state.settle(5);
        assertTrue(state.done(), "有一个通过核对的值就停，剩下的片段不用再看");
    }

    @Test
    void 通读全文的字段一次把整个队列给模型() {
        FieldSpec all = field("deadlines", "list", false, null, true, List.of(field("date", "date", false, null, false, List.of())));
        FieldState state = new FieldState(all, List.of(0, 1, 2, 3), 1);

        assertEquals(List.of(0, 1, 2, 3), state.take(Map.of()));
    }

    @Test
    void 看一张大表的某一段时把其他分段也带上() {
        FieldState state = new FieldState(PAYMENTS, List.of(2), 1);

        assertEquals(List.of(2, 1, 3), state.take(Map.of(2, List.of(1, 3))), "同属一张表的分段一起看");
    }

    @Test
    void 一次回复里相同的值算两条条目() {
        FieldState state = new FieldState(PAYMENTS, List.of(0), 1);

        // 发票上内容相同的两行是两条，不能合并
        state.add(List.of(row("第一期", 100), row("第一期", 100)), Set.of(0));

        assertEquals(2, state.items().size());
    }

    @Test
    void 跨调用遇到相同的值不重复收而用通过核对的那条替换() {
        FieldState state = new FieldState(PAYMENTS, List.of(0, 1), 1);
        state.add(List.of(row("第一期", 100, "证据不是原文", chunk(0))), Set.of(0));
        assertEquals(1, state.items().size());

        state.add(List.of(row("第一期", 100, "", chunk(1))), Set.of(1));

        assertEquals(1, state.items().size(), "同一个值不重复收");
        assertTrue(state.items().get(0).verified(), "这次给出的证据通过了核对，用这次的");
        assertEquals(chunk(1).id(), state.items().get(0).chunk().id());
    }

    @Test
    void 列表顺着新条目向左右邻片延伸看过的不再延伸() {
        FieldState state = new FieldState(PAYMENTS, List.of(2), 1);
        state.add(List.of(row("第二期", 200, "", chunk(2))), Set.of(2));

        state.settle(5);
        assertFalse(state.done());
        assertEquals(List.of(1, 3), state.take(Map.of()), "下一轮看两侧的邻片");

        state.add(List.of(), Set.of(1, 3));
        state.settle(5);
        assertTrue(state.done(), "邻片都没有新条目就停");
    }

    @Test
    void 标量字段读出两个不同的值时第一个作为值另一个进备选并提示复核() {
        FieldState state = new FieldState(AMOUNT, List.of(0, 1), 2);
        state.add(List.of(item(1280000L, "含税总价为人民币1,280,000.00元", chunk(0)),
                item(1300000L, "含税总价为人民币1,300,000.00元", chunk(1))), Set.of(0, 1));
        state.settle(2);

        FieldResult result = state.result(2);

        assertEquals(FieldResult.OK, result.status());
        assertEquals(1280000L, result.value());
        assertEquals(1, result.alternatives().size());
        assertEquals(1300000L, result.alternatives().get(0).value());
        assertTrue(result.needsReview(), "读出了不同的值就要人看一眼");
        assertEquals("别的片段里还读出了不同的值：1300000，见 alternatives", result.issues().get(0));
    }

    @Test
    void 必填字段没找到时说明看过几片() {
        FieldSpec required = field("guarantor", "string", true, null, false, List.of());
        FieldState state = new FieldState(required, List.of(0, 1, 2), 2);
        state.add(List.of(), Set.of(0, 1));

        FieldResult result = state.result(3);

        assertEquals(FieldResult.MISSING, result.status());
        assertEquals(List.of("必填字段没有找到"), result.issues());
        assertEquals("2/3", result.toMap().get("searched"), "没看全时不代表文档里没有");
    }

    @Test
    void 列表过程中出过状况时即使条目都对也不能算通过() {
        FieldState state = new FieldState(PAYMENTS, List.of(0), 1);
        state.add(List.of(row("第一期", 100, "", chunk(0))), Set.of(0));
        state.addNote("第 1 轮：模型的输出被截断，结果可能不全（调大 EXTRACT_LLM_MAX_TOKENS）");

        FieldResult result = state.result(1);

        assertEquals(FieldResult.UNVERIFIED, result.status(), "可能缺项，不能当成完整的列表");
        assertTrue(result.needsReview());
        assertEquals(1, result.items().size());
    }

    private static FieldSpec field(String name, String type, boolean required, Integer topK, boolean exhaustive,
                                   List<FieldSpec> fields) {
        return new FieldSpec(name, type, "", "", required, List.of(), List.of(), "", fields, true, topK, exhaustive);
    }

    /** 一片只有编号，文字里带上证据能出现的字，供排序用。 */
    private static Chunk chunk(int index) {
        return new Chunk(index, "第" + (index + 1) + "片 第一期款项为人民币100元。", List.of(), List.of(), 1, null, 10);
    }

    private static Item item(Object value, String evidence, Chunk chunk) {
        return new Item(value, evidence, chunk, null, new ArrayList<>());
    }

    private static Item row(String stage, long amount) {
        return row(stage, amount, "", chunk(0));
    }

    private static Item row(String stage, long amount, String issue, Chunk chunk) {
        List<String> issues = new ArrayList<>();
        if (!issue.isEmpty()) {
            issues.add(issue);
        }
        return new Item(Map.of("stage", stage, "amount", amount), "| " + stage + " |", chunk, null, issues);
    }
}
