package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.chunk.Chunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调用计划：把"哪个字段要看哪些片段"合并成尽量少的模型调用。
 * 对应 Python 版 {@code agent.py} 的 {@code _fit} 和 {@code _plan}。
 *
 * <p>几个字段的候选片段重合时，放进同一次调用最省：片段只发一遍，模型一次答完。
 * 合并的条件是片段合在一起不超过一次调用的上限，并且字段数没到上限。
 */
final class BatchPlanner {

    private BatchPlanner() {
    }

    /**
     * 把一个字段要看的片段按上下文预算分组。
     *
     * <p>按顺序往一组里放，放不下了就另起一组。单独一片就超过预算时它自己成一组，不会被丢掉。
     *
     * @param wanted        这个字段这一轮要看的片段序号
     * @param contextTokens 一次调用里片段总量的上限
     */
    static List<List<Integer>> fit(List<Integer> wanted, List<Chunk> chunks, int contextTokens) {
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        int used = 0;
        for (int i : wanted) {
            if (!cur.isEmpty() && used + chunks.get(i).tokens() > contextTokens) {
                groups.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            cur.add(i);
            used += chunks.get(i).tokens();
        }
        if (!cur.isEmpty()) {
            groups.add(cur);
        }
        return groups;
    }

    /**
     * 把（字段, 片段）合并成尽量少的调用。
     *
     * @param wants 字段名 → 这一轮要看的片段序号。按字段定义的顺序
     * @return 调用的列表。一个字段的片段太多时会分到几次调用里
     */
    static List<Batch> plan(Map<String, List<Integer>> wants, List<Chunk> chunks, EngineOptions options) {
        List<Batch> batches = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> want : wants.entrySet()) {
            String name = want.getKey();
            for (List<Integer> group : fit(want.getValue(), chunks, options.contextTokens())) {
                boolean placed = false;
                for (Batch batch : batches) {
                    if (!batch.hasField(name) && batch.fields().size() >= options.maxFieldsPerCall()) {
                        continue; // 这一批的字段已经满了
                    }
                    Set<Integer> union = new LinkedHashSet<>(batch.chunks());
                    union.addAll(group);
                    int tokens = 0;
                    for (int i : union) {
                        tokens += chunks.get(i).tokens();
                    }
                    if (tokens <= options.contextTokens()) {
                        batch.addChunks(union);
                        if (!batch.hasField(name)) {
                            batch.addField(name);
                        }
                        placed = true;
                        break;
                    }
                }
                if (!placed) {
                    batches.add(new Batch(List.of(name), new LinkedHashSet<>(group)));
                }
            }
        }
        return batches;
    }
}
