package com.cb.agent.capability.extract.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个要抽取的字段的定义。对应 Python 版 {@code schema.py} 的 {@code Field}。
 *
 * @param name        字段名，也是输出 JSON 里的键
 * @param type        类型：string、number、integer、date、boolean、enum、list
 * @param title       中文名
 * @param description 给模型看的说明：口径、怎么判断
 * @param required    必填。找不到时进入复核清单
 * @param hints       原文里可能出现的叫法，用来定位
 * @param options     enum 的可选项
 * @param unit        number / integer 的单位，如 元、万元、%。原文用别的单位时由程序换算成它
 * @param fields      list 每一项的子字段；为空表示每一项是一个字符串
 * @param verbatim    string 的值是否必须是原文里的文字。需要概括的字段设为 false
 * @param topK        定位时每一轮看几个片段；null 表示用引擎的默认值
 * @param exhaustive  通读全文找这个字段（散落在各处的列表用），而不是只看最相关的几片
 */
public record FieldSpec(String name, String type, String title, String description, boolean required,
                        List<String> hints, List<String> options, String unit, List<FieldSpec> fields,
                        boolean verbatim, Integer topK, boolean exhaustive) {

    public FieldSpec {
        hints = List.copyOf(hints);
        options = List.copyOf(options);
        fields = List.copyOf(fields);
    }

    /** 一个只有名字的字符串字段。核对"字符串列表"里的每一项时临时用。 */
    public static FieldSpec text(String name, boolean verbatim) {
        return new FieldSpec(name, "string", "", "", false, List.of(), List.of(), "", List.of(), verbatim, null, false);
    }

    public boolean isList() {
        return type.equals("list");
    }

    /**
     * 定位用的检索词：中文名、别名、说明，加上各个子字段的名字。对应 {@code Field.query}。
     * 什么都没有时用字段名。
     */
    public String query() {
        List<String> parts = new ArrayList<>();
        parts.add(title);
        parts.addAll(hints);
        parts.add(description);
        for (FieldSpec sub : fields) {
            parts.add(sub.title().isEmpty() ? sub.name() : sub.title());
        }
        parts.removeIf(String::isEmpty);
        String q = String.join(" ", parts);
        return q.isEmpty() ? name : q;
    }

    /** 写进提示词里的字段说明。见 {@link #describe(String)}。 */
    public String describe() {
        return describe("");
    }

    /**
     * 写进提示词里的字段说明。对应 {@code Field.describe}。例如：
     * <pre>
     * - total_amount（合同总金额）：number，单位：元，必填。合同含税总价（原文中可能写作：合同总价、价税合计）
     * </pre>
     * 列表字段下面再列出每一项包含哪些子字段。这段文字原样发给模型，要和 Python 版逐字相同。
     *
     * @param indent 每一行前面的缩进。子字段比它所属的列表字段多缩进四个空格
     */
    public String describe(String indent) {
        StringBuilder head = new StringBuilder(indent).append("- ").append(name);
        if (!title.isEmpty()) {
            head.append("（").append(title).append("）");
        }
        head.append("：").append(type);
        if (type.equals("enum")) {
            head.append("，可选项：").append(String.join(" / ", options));
        }
        if (!unit.isEmpty()) {
            head.append("，单位：").append(unit);
        }
        if (required) {
            head.append("，必填");
        }
        if ((type.equals("string") || type.equals("list")) && fields.isEmpty() && !verbatim) {
            head.append("，可以概括");
        }
        if (!description.isEmpty()) {
            head.append("。").append(description);
        }
        if (!hints.isEmpty()) {
            head.append("（原文中可能写作：").append(String.join("、", hints)).append("）");
        }
        List<String> lines = new ArrayList<>();
        lines.add(head.toString());
        if (isList()) {
            if (!fields.isEmpty()) {
                lines.add(indent + "  每一项是一个对象，包含：");
                for (FieldSpec sub : fields) {
                    lines.add(sub.describe(indent + "    "));
                }
            } else {
                lines.add(indent + "  每一项是一个字符串");
            }
        }
        return String.join("\n", lines);
    }
}
