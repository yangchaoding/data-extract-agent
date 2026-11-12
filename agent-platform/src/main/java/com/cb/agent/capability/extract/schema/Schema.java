package com.cb.agent.capability.extract.schema;

import java.util.List;

/**
 * 抽取 schema：要抽哪些字段，各是什么类型。对应 Python 版 {@code schema.py} 的 {@code Schema}。
 *
 * @param name        schema 的名字，写进结果里
 * @param fields      要抽取的字段，按定义的顺序
 * @param description 这是一类什么文档。会告诉模型，帮它理解上下文
 */
public record Schema(String name, List<FieldSpec> fields, String description) {

    public Schema {
        fields = List.copyOf(fields);
    }

    /**
     * 按名字找字段。
     *
     * @throws IllegalArgumentException 没有这个字段
     */
    public FieldSpec field(String name) {
        for (FieldSpec f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        throw new IllegalArgumentException("schema 里没有字段 " + name);
    }
}
