package com.cb.agent.capability.extract.schema;

import com.cb.agent.capability.extract.format.PyFormat;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 读取并检查 schema 文件（YAML 或 JSON）。对应 Python 版 {@code schema.py} 的
 * {@code load_schema}、{@code schema_from_dict}、{@code _field}。
 *
 * <p>检查得很严：类型写错、属性名拼错、enum 没给选项，都会在这里报错并说明是哪个字段。
 * schema 写错了要在一开始就暴露，不能等到抽取结果不对了再回头找。
 * 报错的文字和 Python 版相同。
 */
public final class SchemaLoader {

    /** 认识的类型。 */
    private static final List<String> TYPES =
            List.of("boolean", "date", "enum", "integer", "list", "number", "string"); // 按字母排好，报错时直接列出来
    /** 字段上认识的属性。 */
    private static final Set<String> ATTRS = Set.of(
            "name", "type", "title", "description", "required", "hints", "options", "unit", "fields",
            "verbatim", "top_k", "exhaustive");

    private SchemaLoader() {
    }

    /**
     * 读 schema 文件。扩展名是 {@code .json} 的按 JSON 读，其余按 YAML 读。
     *
     * @throws IllegalArgumentException 文件读不了，或者内容不符合要求。消息里说明了原因
     */
    public static Schema load(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读不了 schema 文件 " + path + "：" + e.getMessage(), e);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1); // Windows 上保存的文件常带着 BOM
        }
        Object raw;
        try {
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                raw = JsonMapper.builder().build().readValue(text, Object.class);
            } else {
                // SafeConstructor 只会建出普通的 Map、List、字符串、数字，不会按文件里的标记去创建任意的 Java 对象
                raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("schema 文件格式不对：" + path + "（" + e.getMessage() + "）", e);
        }
        return fromMap(raw);
    }

    /**
     * 从已经解析好的内容建 schema。对应 {@code schema_from_dict}。
     *
     * @param raw 解析 YAML 或 JSON 得到的对象，应该是一个 Map
     */
    public static Schema fromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || !(map.get("fields") instanceof List<?> rawFields) || rawFields.isEmpty()) {
            throw new IllegalArgumentException("schema 需要一个非空的 fields 列表");
        }
        List<FieldSpec> fields = new ArrayList<>();
        for (Object f : rawFields) {
            fields.add(field(f, "fields", false));
        }
        Set<String> seen = new HashSet<>();
        Set<String> dup = new TreeSet<>();
        for (FieldSpec f : fields) {
            if (!seen.add(f.name())) {
                dup.add(f.name());
            }
        }
        if (!dup.isEmpty()) {
            throw new IllegalArgumentException("字段名重复: " + PyFormat.repr(List.copyOf(dup)));
        }
        String name = truthy(map.get("name")) ? PyFormat.str(map.get("name")) : "schema";
        String description = truthy(map.get("description")) ? PyFormat.str(map.get("description")) : "";
        return new Schema(name, fields, description);
    }

    /**
     * 检查并读出一个字段。对应 {@code _field}。
     *
     * @param where  这个字段在 schema 里的位置，报错时用，例如 {@code fields.payment_schedule}
     * @param nested 是不是某个 list 字段的子字段
     */
    private static FieldSpec field(Object raw, String where, boolean nested) {
        if (!(raw instanceof Map<?, ?> map) || !truthy(map.get("name"))) {
            throw new IllegalArgumentException(where + ": 每个字段都要是带 name 的对象");
        }
        String name = PyFormat.str(map.get("name"));
        where = where + "." + name;

        Set<String> unknown = new TreeSet<>();
        for (Object key : map.keySet()) {
            if (!ATTRS.contains(String.valueOf(key))) {
                unknown.add(String.valueOf(key));
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(where + ": 不认识的属性 " + PyFormat.repr(List.copyOf(unknown)));
        }
        String type = map.containsKey("type") ? PyFormat.str(map.get("type")) : "string";
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException(where + ": type 必须是 " + PyFormat.repr(TYPES) + " 之一，现在是 " + PyFormat.repr(type));
        }
        if (type.equals("list") && nested) {
            throw new IllegalArgumentException(where + ": list 的子字段不能再是 list");
        }
        List<String> options = strings(map.get("options"));
        if (type.equals("enum") && options.isEmpty()) {
            throw new IllegalArgumentException(where + ": enum 类型需要 options");
        }
        String unit = truthy(map.get("unit")) ? PyFormat.str(map.get("unit")) : "";
        if (!unit.isEmpty() && !type.equals("number") && !type.equals("integer")) {
            throw new IllegalArgumentException(where + ": 只有 number 和 integer 类型可以有 unit");
        }
        List<FieldSpec> subs = new ArrayList<>();
        if (truthy(map.get("fields")) && map.get("fields") instanceof Collection<?> rawSubs) {
            for (Object sub : rawSubs) {
                subs.add(field(sub, where, true));
            }
        }
        if (!subs.isEmpty() && !type.equals("list")) {
            throw new IllegalArgumentException(where + ": 只有 list 类型可以有子字段");
        }
        Set<String> subNames = new HashSet<>();
        for (FieldSpec sub : subs) {
            if (!subNames.add(sub.name())) {
                throw new IllegalArgumentException(where + ": 子字段名重复");
            }
        }
        return new FieldSpec(
                name, type,
                truthy(map.get("title")) ? PyFormat.str(map.get("title")) : "",
                truthy(map.get("description")) ? PyFormat.str(map.get("description")) : "",
                map.containsKey("required") && truthy(map.get("required")),
                strings(map.get("hints")), options, unit, subs,
                !map.containsKey("verbatim") || truthy(map.get("verbatim")),
                truthy(map.get("top_k")) ? toInt(map.get("top_k"), where) : null,
                map.containsKey("exhaustive") && truthy(map.get("exhaustive")));
    }

    /** 把一个列表里的每一项变成字符串。没有写或者是空的返回空列表。 */
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                out.add(PyFormat.str(item));
            }
        }
        return out;
    }

    private static Integer toInt(Object value, String where) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + ": top_k 要是一个整数，现在是 " + PyFormat.repr(value));
        }
    }

    /**
     * 按 Python 的规矩判断一个值算不算"有"：null、false、0、空字符串、空列表、空字典都算没有。
     * YAML 里 {@code title:} 后面什么都不写时值是 null，按没有处理。
     */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }
}
