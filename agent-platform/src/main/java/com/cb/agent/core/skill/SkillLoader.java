package com.cb.agent.core.skill;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 从目录里读 skill。
 *
 * <p>一个 skill 一个目录，目录里至少有两个文件：
 * <pre>
 *   personnel-appointment/
 *     skill.yaml     清单：名称、描述、匹配规则、字段定义在哪、引擎参数
 *     SKILL.md       做法说明
 * </pre>
 * 清单里的路径相对 skill 目录来算，所以字段定义可以放在目录里，也可以指向目录外面已有的文件。
 *
 * <p>检查得很严：键名拼错、必填项没写、指向的文件不存在，都在这里报错并说明是哪个 skill 的哪一项。
 * 清单写错了要在加载时就暴露，不能等到用它抽取时才发现。
 */
public final class SkillLoader {

    /** 清单的文件名。 */
    public static final String MANIFEST = "skill.yaml";

    /** 清单里认识的键。 */
    private static final Set<String> KEYS = Set.of(
            "name", "version", "title", "description", "match", "instructions", "schema", "tools", "engine",
            "validators");
    /** {@code match} 下面认识的键。 */
    private static final Set<String> MATCH_KEYS = Set.of("title_any", "keywords", "min_keywords");

    private SkillLoader() {
    }

    /**
     * 读一个 skill。
     *
     * @param directory skill 的目录，里面要有 {@code skill.yaml}
     * @throws IllegalArgumentException 目录不对，或者清单的内容不符合要求。消息里说明了原因
     */
    public static Skill load(Path directory) {
        Path dir = directory.toAbsolutePath().normalize();
        Path manifest = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalArgumentException(dir + " 不是一个 skill 目录：里面没有 " + MANIFEST);
        }
        String text;
        try {
            text = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读不了 " + manifest + "：" + e.getMessage(), e);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1); // Windows 上保存的文件常带着 BOM
        }
        Object raw;
        try {
            // SafeConstructor 只会建出普通的 Map、List、字符串、数字，不会按文件里的标记去创建任意的 Java 对象
            raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(manifest + " 的格式不对（" + e.getMessage() + "）", e);
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(manifest + " 的内容应该是一组键值");
        }
        String where = manifest.toString();
        unknownKeys(map, KEYS, where);

        String name = text(map.get("name"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException(where + "：缺少 name");
        }
        String folder = dir.getFileName().toString();
        if (!name.equals(folder)) {
            // 按名字找 skill 时找的是目录名，两边不一样会找不到
            throw new IllegalArgumentException(where + "：name 是 " + name + "，和目录名 " + folder + " 不一样");
        }
        String description = text(map.get("description"));
        if (description.isEmpty()) {
            throw new IllegalArgumentException(where + "：缺少 description（选 skill 时要靠它判断）");
        }
        String title = text(map.get("title"));

        Path instructions = file(dir, map.get("instructions"), "SKILL.md", where + "：instructions");
        Path schema = map.get("schema") == null ? null : file(dir, map.get("schema"), null, where + "：schema");

        Map<String, Object> engine = new LinkedHashMap<>();
        Object rawEngine = map.get("engine");
        if (rawEngine != null) {
            if (!(rawEngine instanceof Map<?, ?> engineMap)) {
                throw new IllegalArgumentException(where + "：engine 应该是一组键值，例如 {contextTokens: 6000}");
            }
            engineMap.forEach((k, v) -> engine.put(String.valueOf(k), v));
        }

        return new Skill(name, version(map.get("version"), where), title.isEmpty() ? name : title, description,
                match(map.get("match"), where), dir, instructions, schema,
                strings(map.get("tools"), where + "：tools"), engine,
                strings(map.get("validators"), where + "：validators"));
    }

    /**
     * 读一个目录下的全部 skill：每个带着 {@code skill.yaml} 的子目录是一个。
     *
     * <p>没有清单的子目录直接跳过（可能是别的工具放的东西）；有清单但写错了的会报错，不会被悄悄跳过。
     *
     * @return 按名称排序的 skill；一个都没有时是空列表
     */
    public static List<Skill> loadAll(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("skill 目录不存在：" + root.toAbsolutePath().normalize());
        }
        List<Path> dirs;
        try (Stream<Path> children = Files.list(root)) {
            dirs = children.filter(p -> Files.isRegularFile(p.resolve(MANIFEST))).sorted().toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("读不了 skill 目录 " + root + "：" + e.getMessage(), e);
        }
        List<Skill> out = new ArrayList<>();
        for (Path dir : dirs) {
            out.add(load(dir));
        }
        return out;
    }

    /**
     * 按名称找一个 skill。
     *
     * @param root skills 目录
     * @throws IllegalArgumentException 没有这个 skill。消息里列出了有哪些
     */
    public static Skill find(Path root, String name) {
        Path dir = root.resolve(name);
        if (Files.isRegularFile(dir.resolve(MANIFEST))) {
            return load(dir);
        }
        List<String> names = loadAll(root).stream().map(Skill::name).toList();
        throw new IllegalArgumentException("没有名为 " + name + " 的 skill。"
                + (names.isEmpty() ? root.toAbsolutePath().normalize() + " 下一个 skill 都没有"
                : "现有的：" + String.join("、", names)));
    }

    private static void unknownKeys(Map<?, ?> map, Set<String> known, String where) {
        Set<String> unknown = new TreeSet<>();
        for (Object key : map.keySet()) {
            if (!known.contains(String.valueOf(key))) {
                unknown.add(String.valueOf(key));
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(where + "：不认识的键 " + String.join("、", unknown)
                    + "（认识的有：" + String.join("、", new TreeSet<>(known)) + "）");
        }
    }

    private static int version(Object raw, String where) {
        if (raw == null) {
            return 1;
        }
        if (raw instanceof Integer v && v >= 1) {
            return v;
        }
        throw new IllegalArgumentException(where + "：version 应该是从 1 开始的整数，现在是 " + raw);
    }

    private static SkillMatch match(Object raw, String where) {
        if (raw == null) {
            return SkillMatch.NONE;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + "：match 应该是一组键值");
        }
        unknownKeys(map, MATCH_KEYS, where + "：match");
        List<String> titleAny = strings(map.get("title_any"), where + "：match.title_any");
        List<String> keywords = strings(map.get("keywords"), where + "：match.keywords");
        Object min = map.get("min_keywords");
        int minKeywords = keywords.isEmpty() ? 0 : 1; // 没写的话，命中一个特征词就算
        if (min != null) {
            if (!(min instanceof Integer n) || n < 1 || n > keywords.size()) {
                throw new IllegalArgumentException(where + "：match.min_keywords 应该在 1 到 " + keywords.size()
                        + "（keywords 的个数）之间，现在是 " + min);
            }
            minKeywords = n;
        }
        return new SkillMatch(titleAny, keywords, minKeywords);
    }

    /** 清单里指向的一个文件。相对路径按 skill 目录算；文件必须存在。 */
    private static Path file(Path dir, Object raw, String byDefault, String where) {
        String value = raw == null ? byDefault : text(raw);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(where + " 没有写文件名");
        }
        Path path = dir.resolve(value).normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(where + " 指向的文件不存在：" + path);
        }
        return path;
    }

    private static List<String> strings(Object raw, String where) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(where + " 应该是一个列表");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String s = text(item);
            if (s.isEmpty()) {
                throw new IllegalArgumentException(where + " 里有一项是空的");
            }
            out.add(s);
        }
        return out;
    }

    /** 清单里的一个文字值。折行写法（{@code >}）读出来末尾带着换行，去掉。 */
    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).strip();
    }
}
