package com.cb.agent.capability.extract.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次模型调用：从这些片段里抽这些字段。对应 Python 版 {@code agent.py} 的 {@code _Batch}。
 *
 * <p>成批的时候片段集合会被合并，所以 {@link #chunks()} 是可变的。
 */
final class Batch {

    private final List<String> fields;
    private final Set<Integer> chunks;

    Batch(List<String> fields, Set<Integer> chunks) {
        this.fields = new ArrayList<>(fields);
        this.chunks = new LinkedHashSet<>(chunks);
    }

    /** 这次调用要抽的字段名，按加入的先后排列。 */
    List<String> fields() {
        return fields;
    }

    /** 这次调用给模型看的片段序号。 */
    Set<Integer> chunks() {
        return chunks;
    }

    void addChunks(Set<Integer> more) {
        chunks.addAll(more);
    }

    void addField(String name) {
        fields.add(name);
    }

    boolean hasField(String name) {
        return fields.contains(name);
    }
}
