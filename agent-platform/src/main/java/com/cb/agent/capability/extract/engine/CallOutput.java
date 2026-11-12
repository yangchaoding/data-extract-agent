package com.cb.agent.capability.extract.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次调用（或者截断后拆开的几次调用合起来）读到的结果。对应 Python 版 {@code agent.py} 的 {@code _Output}。
 */
final class CallOutput {

    /** 字段名 → 读到的条目。 */
    private final Map<String, List<Item>> items = new LinkedHashMap<>();
    /** 模型的回复始终不成形、没能读出答案的字段。 */
    private List<String> unparsed = new ArrayList<>();
    /** 输出被截断、拆到最小仍然放不下的字段。 */
    private final List<String> truncated = new ArrayList<>();

    /** 拆到只剩一个字段、一个片段，输出还是被截断。 */
    static CallOutput truncated(String field) {
        CallOutput out = new CallOutput();
        out.truncated.add(field);
        return out;
    }

    Map<String, List<Item>> items() {
        return items;
    }

    List<Item> items(String field) {
        return items.getOrDefault(field, List.of());
    }

    List<String> unparsed() {
        return unparsed;
    }

    void setUnparsed(List<String> fields) {
        this.unparsed = new ArrayList<>(fields);
    }

    List<String> truncated() {
        return truncated;
    }

    /** 把另一部分的结果并进来。截断后拆成两半，各自的结果在这里合起来。 */
    void merge(CallOutput other) {
        other.items.forEach((name, list) -> items.computeIfAbsent(name, k -> new ArrayList<>()).addAll(list));
        unparsed.addAll(other.unparsed);
        truncated.addAll(other.truncated);
    }
}
