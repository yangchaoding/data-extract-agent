package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.text.TextMatch;
import com.cb.agent.capability.extract.format.PyFormat;
import com.cb.agent.capability.extract.schema.FieldSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一个字段在抽取过程中的状态。对应 Python 版 {@code agent.py} 的 {@code _State}。
 *
 * <p>一个字段有自己的片段队列：先看最相关的几片，没找到就往后看；列表字段找到条目之后，
 * 还会去看条目所在片段的左右邻片，因为列表项往往连着写。
 *
 * <p>列表字段在这里去重：同一片可能出现在几次调用里，同一件事也可能在文档里写了几处。
 * 但一次回复里模型列了两遍的相同条目算两条（发票上内容相同的两行），所以去重键是
 * "值 + 这个值在该次回复里第几次出现"。键已经存在时不重复收；如果新条目通过了核对而旧条目没通过，用新的替换旧的。
 */
final class FieldState {

    /** 位置不明的条目排在最后。 */
    private static final int LAST = 1_000_000_000;

    private final FieldSpec field;
    private final boolean list;
    private final Deque<Integer> queue;
    private final int topK;
    private final Set<Integer> seen = new LinkedHashSet<>();
    /** 列表字段的去重键 → 条目在 {@link #items} 里的下标。 */
    private final Map<String, Integer> slots = new HashMap<>();
    private final List<Item> items = new ArrayList<>();
    /** 过程中出的状况：回复不成形、输出被截断、列表没有收尾。只要有一条，字段就要人看一眼。 */
    private final List<String> notes = new ArrayList<>();
    /** 这一轮新收下的条目。下一轮会去看它们的左右邻片。 */
    private List<Item> fresh = new ArrayList<>();
    /** 列表字段：下一轮接着看的片段。 */
    private List<Integer> follow = new ArrayList<>();
    private boolean done;
    private int rounds;

    /**
     * @param queue       还没看过的片段，按相关度排好序（通读全文的字段按文档顺序）
     * @param defaultTopK 每一轮看几片；字段定义里写了自己的就用它自己的
     */
    FieldState(FieldSpec field, List<Integer> queue, int defaultTopK) {
        this.field = field;
        this.list = field.isList();
        this.queue = new ArrayDeque<>(queue);
        // 通读全文的字段一次把整个队列给模型
        this.topK = field.exhaustive() ? queue.size()
                : (field.topK() != null && field.topK() > 0 ? field.topK() : defaultTopK);
    }

    FieldSpec field() {
        return field;
    }

    boolean isList() {
        return list;
    }

    List<Item> items() {
        return items;
    }

    boolean done() {
        return done;
    }

    void markDone() {
        done = true;
    }

    void setRounds(int round) {
        this.rounds = round;
    }

    void addNote(String note) {
        notes.add(note);
    }

    /**
     * 这一轮要看的片段。
     *
     * @param siblings 共用同一个版面块的片段（大表、长段落拆出来的各段）；看一张大表的某一段时，
     *                 其他段也一起带上
     */
    List<Integer> take(Map<Integer, List<Integer>> siblings) {
        List<Integer> picked;
        if (!follow.isEmpty()) {
            picked = follow;
            follow = new ArrayList<>();
        } else {
            picked = new ArrayList<>();
            while (!queue.isEmpty() && picked.size() < topK) {
                int i = queue.pollFirst();
                if (!seen.contains(i) && !picked.contains(i)) {
                    picked.add(i);
                }
            }
        }
        if (list) {
            for (int i : new ArrayList<>(picked)) { // 先取一份快照：这一轮新加进来的不再展开
                for (int j : siblings.getOrDefault(i, List.of())) {
                    if (!seen.contains(j) && !picked.contains(j)) {
                        picked.add(j);
                    }
                }
            }
        }
        return picked;
    }

    /**
     * 收下一次调用的结果。
     *
     * @param shown 这一次调用给模型看过的片段
     */
    void add(List<Item> newItems, Set<Integer> shown) {
        seen.addAll(shown);
        if (!list) {
            items.addAll(newItems);
            return;
        }
        Map<String, Integer> times = new HashMap<>();
        for (Item item : newItems) {
            String key = Item.valueKey(item.value());
            int nth = times.merge(key, 1, Integer::sum) - 1;
            String slot = key + "\u0000" + nth;
            Integer at = slots.get(slot);
            if (at == null) {
                slots.put(slot, items.size());
                items.add(item);
                fresh.add(item);
            } else if (item.verified() && !items.get(at).verified()) {
                items.set(at, item); // 同一个值，这次给出的证据通过了核对，用这次的
            }
        }
    }

    /** 一轮结束后决定是否继续。 */
    void settle(int chunkCount) {
        List<Item> freshNow = fresh;
        fresh = new ArrayList<>();
        if (!list) {
            // 标量字段：只要有一个通过核对的值就停，剩下的片段不用再看了
            done = items.stream().anyMatch(Item::verified);
            return;
        }
        if (freshNow.isEmpty()) {
            done = !items.isEmpty(); // 这一轮没有新条目：已经有结果就收工，还没有就继续往后找
            return;
        }
        Set<Integer> near = new TreeSet<>();
        for (Item item : freshNow) {
            if (item.chunk() == null) {
                continue;
            }
            near.add(item.chunk().index() - 1);
            near.add(item.chunk().index() + 1);
        }
        follow = new ArrayList<>(near.stream().filter(j -> j >= 0 && j < chunkCount && !seen.contains(j)).toList());
        done = follow.isEmpty();
    }

    /** 这个字段的最终结果。 */
    FieldResult result(int chunkCount) {
        int searched = seen.size();
        if (items.isEmpty()) {
            List<String> issues = new ArrayList<>();
            if (field.required()) {
                issues.add("必填字段没有找到");
            }
            issues.addAll(notes);
            return new FieldResult(field, FieldResult.MISSING, List.of(), issues, List.of(), rounds, searched, chunkCount);
        }
        if (list) {
            List<Item> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparingInt((Item it) -> position(it)[0]).thenComparingInt(it -> position(it)[1]));
            // 过程中出过状况就可能缺项，条目都对也不能算通过
            String status = sorted.stream().allMatch(Item::verified) && notes.isEmpty()
                    ? FieldResult.OK : FieldResult.UNVERIFIED;
            return new FieldResult(field, status, sorted, List.copyOf(notes), List.of(), rounds, searched, chunkCount);
        }
        List<Item> verified = items.stream().filter(Item::verified).toList();
        if (verified.isEmpty()) {
            return new FieldResult(field, FieldResult.UNVERIFIED, List.of(items.get(0)), List.of(), List.of(),
                    rounds, searched, chunkCount);
        }
        Item best = verified.get(0);
        Set<String> keys = new HashSet<>();
        keys.add(Item.valueKey(best.value()));
        List<Item> others = new ArrayList<>();
        for (Item item : verified.subList(1, verified.size())) {
            if (keys.add(Item.valueKey(item.value()))) {
                others.add(item);
            }
        }
        List<String> issues = new ArrayList<>();
        if (!others.isEmpty()) {
            // 值还是第一个通过核对的那个，但别的片段里读出了不同的值，要人看一眼
            List<String> values = new ArrayList<>();
            for (Item item : others) {
                values.add(PyFormat.json(item.value()));
            }
            issues.add("别的片段里还读出了不同的值：" + String.join("、", values) + "，见 alternatives");
        }
        return new FieldResult(field, FieldResult.OK, List.of(best), issues, others, rounds, searched, chunkCount);
    }

    /** 条目在文档里的位置：片段序号，加上证据在这一片里的位置。位置不明的排在所属片段的最后。 */
    private static int[] position(Item item) {
        if (item.chunk() == null) {
            return new int[]{LAST, LAST};
        }
        int at = -1;
        for (String part : TextMatch.quoteParts(item.evidence())) {
            int found = item.chunk().source().position(part);
            if (found > at) {
                at = found; // 引了表头加一行时，以靠后的那一行为准
            }
        }
        return new int[]{item.chunk().index(), at < 0 ? LAST : at};
    }
}
