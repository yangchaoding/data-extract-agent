package com.cb.agent.capability.extract.engine;

import com.cb.agent.core.llm.Usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份文档的抽取结果。对应 Python 版 {@code agent.py} 的 {@code ExtractionResult}。
 *
 * @param document 文档名
 * @param schema   用的字段定义的名字
 * @param fields   每个字段的结果，按字段定义的顺序
 * @param chunks   文档被切成了几片
 * @param usage    这一次抽取用了多少次模型调用、多少 token
 */
public record ExtractionResult(String document, String schema, Map<String, FieldResult> fields,
                               int chunks, Usage usage) {

    /** 只有值的结果：{@code {字段名: 值}}。直接入库用。 */
    public Map<String, Object> data() {
        Map<String, Object> out = new LinkedHashMap<>();
        fields.forEach((name, result) -> out.put(name, result.value()));
        return out;
    }

    /** 需要人看一眼的字段：没通过核对的、没找到的必填字段、过程中出过状况的。 */
    public List<String> needsReview() {
        List<String> out = new ArrayList<>();
        fields.forEach((name, result) -> {
            if (result.needsReview()) {
                out.add(name);
            }
        });
        return out;
    }

    /** 状态是 {@code status} 的字段有几个。 */
    public int count(String status) {
        return (int) fields.values().stream().filter(r -> r.status().equals(status)).count();
    }

    /**
     * 导出成可以直接序列化的结构。对应 {@code ExtractionResult.to_dict}。
     * <pre>
     * {
     *   "document": …, "schema": …,
     *   "data":    {字段名: 值},                    直接入库用
     *   "fields":  {字段名: 状态、值、证据、出处},    回查出处用
     *   "summary": {各状态的个数, 要复核的字段, 片数, 用量}
     * }
     * </pre>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", document);
        out.put("schema", schema);
        out.put("data", data());
        Map<String, Object> fieldMaps = new LinkedHashMap<>();
        fields.forEach((name, result) -> fieldMaps.put(name, result.toMap()));
        out.put("fields", fieldMaps);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ok", count(FieldResult.OK));
        summary.put("unverified", count(FieldResult.UNVERIFIED));
        summary.put("missing", count(FieldResult.MISSING));
        summary.put("needs_review", needsReview());
        summary.put("chunks", chunks);
        summary.put("llm_calls", usage.calls());
        summary.put("prompt_tokens", usage.promptTokens());
        summary.put("completion_tokens", usage.completionTokens());
        out.put("summary", summary);
        return out;
    }
}
