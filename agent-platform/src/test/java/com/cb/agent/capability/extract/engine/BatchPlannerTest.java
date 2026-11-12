package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.chunk.Chunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 调用计划：哪些字段合进同一次调用，什么时候要分开。
 */
class BatchPlannerTest {

    private static final EngineOptions OPTIONS = EngineOptions.DEFAULTS.contextTokens(300).maxFieldsPerCall(2);

    @Test
    void 一个字段的片段按上下文预算分组() {
        List<Chunk> chunks = chunks(100, 150, 120, 400, 50);

        // 100+150 放得下，再加 120 就超了；400 自己就超预算，也要单独成一组，不能丢
        assertEquals(List.of(List.of(0, 1), List.of(2), List.of(3), List.of(4)),
                BatchPlanner.fit(List.of(0, 1, 2, 3, 4), chunks, 300));
    }

    @Test
    void 候选片段相同的字段放进同一次调用() {
        List<Batch> batches = BatchPlanner.plan(wants("a", List.of(0, 1), "b", List.of(1, 0)), chunks(100, 150), OPTIONS);

        assertEquals(1, batches.size());
        assertEquals(List.of("a", "b"), batches.get(0).fields());
        assertEquals(Set.of(0, 1), batches.get(0).chunks());
    }

    @Test
    void 片段合在一起超过预算的字段分开调用() {
        List<Batch> batches = BatchPlanner.plan(wants("a", List.of(0), "b", List.of(1)), chunks(200, 200), OPTIONS);

        assertEquals(2, batches.size(), "200+200 超过了 300");
        assertEquals(List.of("a"), batches.get(0).fields());
        assertEquals(List.of("b"), batches.get(1).fields());
    }

    @Test
    void 一次调用的字段数有上限() {
        List<Batch> batches = BatchPlanner.plan(
                wants("a", List.of(0), "b", List.of(0), "c", List.of(0)), chunks(100), OPTIONS);

        assertEquals(2, batches.size(), "一次最多 2 个字段，第 3 个另起一次");
        assertEquals(List.of("a", "b"), batches.get(0).fields());
        assertEquals(List.of("c"), batches.get(1).fields());
    }

    @Test
    void 一个字段的片段太多时分到几次调用里() {
        List<Batch> batches = BatchPlanner.plan(wants("a", List.of(0, 1, 2)), chunks(200, 200, 200), OPTIONS);

        assertEquals(3, batches.size());
        for (Batch batch : batches) {
            assertEquals(List.of("a"), batch.fields(), "同一个字段不会在一次调用里出现两遍");
            assertEquals(1, batch.chunks().size());
        }
    }

    private static List<Chunk> chunks(int... tokens) {
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            out.add(new Chunk(i, "第" + (i + 1) + "片", List.of(), List.of(), 1, null, tokens[i]));
        }
        return out;
    }

    /** 字段名和它要看的片段，交替给出。按给出的顺序，和字段定义的顺序一样。 */
    @SuppressWarnings("unchecked")
    private static Map<String, List<Integer>> wants(Object... pairs) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (List<Integer>) pairs[i + 1]);
        }
        return out;
    }
}
