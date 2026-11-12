package com.cb.agent.capability.extract.skill;

import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.capability.extract.schema.SchemaLoader;
import com.cb.agent.core.skill.Skill;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 按一个 skill 的定义来抽取：用它的字段定义，用它清单里写的引擎参数。
 *
 * <p>skill 的清单只是数据，不知道"抽取引擎"是什么。清单里的 {@code schema} 和 {@code engine}
 * 这两项怎么用，由这里解释：读出字段定义并检查，把引擎参数和默认值合在一起。
 * 两样都在 {@link #of} 里做完，所以清单或字段定义写错了，在准备阶段就会报错，不会等到跑了一半。
 *
 * <p>这里只管"准备"：得到字段定义和引擎参数。拿它们去跑引擎是调用方的事。
 */
public final class SkillExtraction {

    /** 清单的 {@code engine} 里可以写的参数。并发数不在里面：它是部署时定的，不是某一类文档的属性。 */
    private static final Set<String> ENGINE_KEYS = Set.of(
            "chunkTokens", "contextTokens", "topK", "maxRounds", "retries", "route", "routeTokens",
            "maxFieldsPerCall");

    private final Skill skill;
    private final Schema schema;
    private final EngineOptions options;

    private SkillExtraction(Skill skill, Schema schema, EngineOptions options) {
        this.skill = skill;
        this.schema = schema;
        this.options = options;
    }

    /**
     * 准备按这个 skill 抽取。
     *
     * @param defaults 引擎参数的默认值；清单里写了的以清单为准
     * @throws IllegalArgumentException 这个 skill 没有字段定义，或者字段定义、引擎参数写错了。消息里说明了原因
     */
    public static SkillExtraction of(Skill skill, EngineOptions defaults) {
        if (skill.schema() == null) {
            throw new IllegalArgumentException("skill " + skill.name() + " 的清单里没有写 schema，它不是用来抽取数据的");
        }
        return new SkillExtraction(skill, SchemaLoader.load(skill.schema()), options(skill, defaults));
    }

    public Skill skill() {
        return skill;
    }

    /** 这个 skill 的字段定义。 */
    public Schema schema() {
        return schema;
    }

    /** 实际用的引擎参数：默认值加上清单里改的那几项。 */
    public EngineOptions options() {
        return options;
    }

    /** 把清单里写的引擎参数盖到默认值上。键不认识、值的类型不对都报错。 */
    static EngineOptions options(Skill skill, EngineOptions defaults) {
        Map<String, Object> engine = skill.engine();
        String where = "skill " + skill.name() + " 的 engine";
        Set<String> unknown = new TreeSet<>(engine.keySet());
        unknown.removeAll(ENGINE_KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(where + " 里有不认识的参数：" + String.join("、", unknown)
                    + "（认识的有：" + String.join("、", new TreeSet<>(ENGINE_KEYS)) + "）");
        }
        EngineOptions out = defaults;
        try {
            for (Map.Entry<String, Object> entry : engine.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                out = switch (key) {
                    case "chunkTokens" -> out.chunkTokens(integer(key, value, where));
                    case "contextTokens" -> out.contextTokens(integer(key, value, where));
                    case "topK" -> out.topK(integer(key, value, where));
                    case "maxRounds" -> out.maxRounds(integer(key, value, where));
                    case "retries" -> out.retries(integer(key, value, where));
                    case "routeTokens" -> out.routeTokens(integer(key, value, where));
                    case "maxFieldsPerCall" -> out.maxFieldsPerCall(integer(key, value, where));
                    default -> out.route(bool(key, value, where));
                };
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage().startsWith(where)) {
                throw e;
            }
            throw new IllegalArgumentException(where + "：" + e.getMessage(), e); // 取值范围不对，由 EngineOptions 报出
        }
        return out;
    }

    private static int integer(String key, Object value, String where) {
        if (value instanceof Integer number) {
            return number;
        }
        throw new IllegalArgumentException(where + "." + key + " 应该是整数，现在是 " + value);
    }

    private static boolean bool(String key, Object value, String where) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(where + "." + key + " 应该是 true 或 false，现在是 " + value);
    }
}
