package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.extract.schema.FieldSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个字段的抽取结果。对应 Python 版 {@code agent.py} 的 {@code FieldResult}。
 *
 * <p>三种状态：
 * <ul>
 *   <li>{@code ok}：值通过了程序的核对，确实出自引用的那段原文；</li>
 *   <li>{@code unverified}：有值，但没通过核对。值仍然保留，{@link #issues()} 里写着原因，需要人看；</li>
 *   <li>{@code missing}：没有找到。</li>
 * </ul>
 * {@code ok} 的意思是"值确实写在引用的原文里"，不是"保证取对了"。同一行里有本期和上期两个数时，
 * 程序能确认值在这一行里，确认不了取的是哪一个。
 *
 * <p>{@link #issues()} 不为空说明这个字段需要人看一眼，即使状态是 {@code ok}（比如在别的片段里读出了不同的值）。
 */
public final class FieldResult {

    public static final String OK = "ok";
    public static final String UNVERIFIED = "unverified";
    public static final String MISSING = "missing";

    private final FieldSpec field;
    private final String status;
    private final List<Item> items;
    private final List<String> issues;
    private final List<Item> alternatives;
    private final int rounds;
    private final int searched;
    private final int total;

    /**
     * @param items        值。标量字段最多一个；列表字段每一项一个
     * @param issues       字段层面的问题：必填却没找到、读出了不同的值、列表可能不完整
     * @param alternatives 标量字段：同样通过了核对、但和选中的值不同的其他值
     * @param rounds       找了几轮
     * @param searched     看过几个片段
     * @param total        一共几个片段
     */
    FieldResult(FieldSpec field, String status, List<Item> items, List<String> issues, List<Item> alternatives,
                int rounds, int searched, int total) {
        this.field = field;
        this.status = status;
        this.items = List.copyOf(items);
        this.issues = List.copyOf(issues);
        this.alternatives = List.copyOf(alternatives);
        this.rounds = rounds;
        this.searched = searched;
        this.total = total;
    }

    public FieldSpec field() {
        return field;
    }

    public String status() {
        return status;
    }

    public List<Item> items() {
        return items;
    }

    public List<String> issues() {
        return issues;
    }

    public List<Item> alternatives() {
        return alternatives;
    }

    public int rounds() {
        return rounds;
    }

    /** 字段的值：列表字段是各项的值组成的列表；标量字段是那个值，没找到时为 null。 */
    public Object value() {
        if (field.isList()) {
            List<Object> values = new ArrayList<>();
            for (Item item : items) {
                values.add(item.value());
            }
            return values;
        }
        return items.isEmpty() ? null : items.get(0).value();
    }

    /** 需不需要人看：没通过核对，或者过程中出过状况。 */
    public boolean needsReview() {
        return status.equals(UNVERIFIED) || !issues.isEmpty();
    }

    /** 导出成可以直接序列化的结构。对应 {@code FieldResult.to_dict}。 */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        List<String> all = new ArrayList<>();
        if (field.isList()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Item item : items) {
                list.add(item.toMap());
            }
            out.put("items", list);
        } else if (!items.isEmpty()) {
            // 标量字段只有一个值，把它的出处直接摊开放在字段下面；值自己的问题和字段的问题合在一起
            Map<String, Object> item = items.get(0).toMap();
            @SuppressWarnings("unchecked")
            List<String> itemIssues = (List<String>) item.remove("issues");
            out.putAll(item);
            if (itemIssues != null) {
                all.addAll(itemIssues);
            }
        } else {
            out.put("value", null);
        }
        all.addAll(issues);
        if (!alternatives.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Item item : alternatives) {
                list.add(item.toMap());
            }
            out.put("alternatives", list);
        }
        if (status.equals(MISSING)) {
            // 没看全的时候，"没找到"不代表文档里没有
            out.put("searched", searched + "/" + total);
        }
        if (!all.isEmpty()) {
            out.put("issues", all);
        }
        return out;
    }
}
