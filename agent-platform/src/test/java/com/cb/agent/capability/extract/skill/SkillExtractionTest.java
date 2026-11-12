package com.cb.agent.capability.extract.skill;

import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.schema.FieldSpec;
import com.cb.agent.core.skill.Skill;
import com.cb.agent.core.skill.SkillLoader;
import com.cb.agent.core.skill.SkillMatch;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * skill 的字段定义和引擎参数怎么用到抽取上。
 */
class SkillExtractionTest {

    private static final Path SKILL_DIR = Path.of("..", ".claude", "skills", "personnel-appointment");

    @Test
    void 干部任免审批表的字段定义和引擎参数() {
        SkillExtraction extraction = SkillExtraction.of(SkillLoader.load(SKILL_DIR), EngineOptions.DEFAULTS);

        assertEquals("personnel_appointment", extraction.schema().name());
        assertEquals(26, extraction.schema().fields().size(), "25 个本人字段，加上家庭成员这个列表");
        FieldSpec family = extraction.schema().field("家庭成员及主要社会关系");
        assertTrue(family.isList() && family.exhaustive());
        assertEquals(List.of("称谓", "姓名", "年龄", "政治面貌", "工作单位及职务"),
                family.fields().stream().map(FieldSpec::name).toList());
        assertTrue(extraction.schema().field("姓名").required(), "必填的只有姓名");

        assertEquals(6000, extraction.options().contextTokens());
        assertEquals(5, extraction.options().topK());
        assertEquals(EngineOptions.DEFAULTS.maxRounds(), extraction.options().maxRounds(), "清单里没写的用默认值");
    }

    @Test
    void 清单里写的引擎参数盖住默认值() {
        EngineOptions defaults = EngineOptions.DEFAULTS.workers(2);

        EngineOptions options = SkillExtraction.options(
                skill(Map.of("contextTokens", 3000, "topK", 2, "route", false, "maxRounds", 6)), defaults);

        assertEquals(3000, options.contextTokens());
        assertEquals(2, options.topK());
        assertEquals(false, options.route());
        assertEquals(6, options.maxRounds());
        assertEquals(defaults.chunkTokens(), options.chunkTokens());
        assertEquals(2, options.workers(), "并发数是部署时定的，不由 skill 决定");
    }

    @Test
    void 引擎参数写错了时说明是哪个skill的哪一项() {
        assertEngineError(Map.of("topk", 5), "skill demo 的 engine 里有不认识的参数：topk");
        assertEngineError(Map.of("workers", 8), "不认识的参数：workers"); // 并发数不能写在 skill 里
        assertEngineError(Map.of("topK", "五"), "skill demo 的 engine.topK 应该是整数，现在是 五");
        assertEngineError(Map.of("route", "no"), "skill demo 的 engine.route 应该是 true 或 false");
        assertEngineError(Map.of("topK", 0), "skill demo 的 engine：topK 要大于 0");
    }

    @Test
    void 没有字段定义的skill不能用来抽取() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SkillExtraction.of(skill(Map.of()), EngineOptions.DEFAULTS));
        assertEquals("skill demo 的清单里没有写 schema，它不是用来抽取数据的", e.getMessage());
    }

    private static void assertEngineError(Map<String, Object> engine, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SkillExtraction.options(skill(engine), EngineOptions.DEFAULTS));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    /** 一个只有引擎参数的 skill，没有字段定义。 */
    private static Skill skill(Map<String, Object> engine) {
        return new Skill("demo", 1, "演示", "演示用", SkillMatch.NONE, Path.of("demo"), Path.of("demo", "SKILL.md"),
                null, List.of(), engine, List.of());
    }
}
