package com.cb.agent.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从目录里读 skill：仓库里现成的那个能读出来，清单写错了的要报错并说清楚错在哪。
 */
class SkillLoaderTest {

    /** 仓库里 Claude Code 用的 skill 目录。测试的当前目录是 agent-platform。 */
    private static final Path SKILLS = Path.of("..", ".claude", "skills");

    @Test
    void 读出仓库里的干部任免审批表skill() {
        Skill skill = SkillLoader.load(SKILLS.resolve("personnel-appointment"));

        assertEquals("personnel-appointment", skill.name());
        assertEquals(1, skill.version());
        assertEquals("干部任免审批表", skill.title());
        assertTrue(skill.description().contains("拟任职务"), skill.description());
        assertEquals(List.of("干部任免审批表", "任免审批表"), skill.match().titleAny());
        assertEquals(4, skill.match().keywords().size());
        assertEquals(2, skill.match().minKeywords());
        assertEquals(Map.of("contextTokens", 6000, "topK", 5), skill.engine());
        // 路径都已经按 skill 目录算成了绝对路径，并且文件确实在
        assertTrue(skill.instructions().isAbsolute() && skill.instructions().endsWith("SKILL.md"));
        assertTrue(Files.isRegularFile(skill.schema()), "字段定义指向仓库里已有的那一份：" + skill.schema());
        assertEquals("personnel_appointment.yaml", skill.schema().getFileName().toString());
        assertTrue(skill.readInstructions().contains("# 干部任免审批表抽取"), "做法说明读的是 SKILL.md 的正文");
    }

    @Test
    void 按名称找skill找不到时列出现有的() {
        assertEquals("personnel-appointment", SkillLoader.find(SKILLS, "personnel-appointment").name());
        assertEquals(List.of("personnel-appointment"), SkillLoader.loadAll(SKILLS).stream().map(Skill::name).toList());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SkillLoader.find(SKILLS, "invoice"));
        assertEquals("没有名为 invoice 的 skill。现有的：personnel-appointment", e.getMessage());
    }

    @Test
    void 只写了名称和描述时其余用默认值(@TempDir Path root) throws IOException {
        Path dir = skill(root, "weekly-report", """
                name: weekly-report
                description: 写周报
                """);

        Skill skill = SkillLoader.load(dir);

        assertEquals(1, skill.version());
        assertEquals("weekly-report", skill.title(), "没写中文名就用名称");
        assertTrue(skill.match().isEmpty(), "没写匹配规则：只能由人指定或由模型来选");
        assertNull(skill.schema(), "不是每个 skill 都抽取数据");
        assertEquals(Map.of(), skill.engine());
        assertEquals(List.of(), skill.tools());
        assertEquals(dir.toAbsolutePath().normalize().resolve("SKILL.md"), skill.instructions());
    }

    @Test
    void 没有清单的子目录被跳过(@TempDir Path root) throws IOException {
        skill(root, "b-skill", "name: b-skill\ndescription: 乙\n");
        skill(root, "a-skill", "name: a-skill\ndescription: 甲\n");
        Files.createDirectories(root.resolve("notes")); // 别的工具放的目录

        assertEquals(List.of("a-skill", "b-skill"), SkillLoader.loadAll(root).stream().map(Skill::name).toList());
    }

    @Test
    void 清单写错了时说明错在哪(@TempDir Path root) throws IOException {
        assertError(root, "no-manifest", null, "不是一个 skill 目录：里面没有 skill.yaml");
        assertError(root, "typo", "name: typo\ndescription: x\nshema: schema.yaml\n",
                "不认识的键 shema");
        assertError(root, "renamed", "name: something-else\ndescription: x\n",
                "name 是 something-else，和目录名 renamed 不一样");
        assertError(root, "no-desc", "name: no-desc\n", "缺少 description");
        assertError(root, "no-schema-file", "name: no-schema-file\ndescription: x\nschema: fields.yaml\n",
                "schema 指向的文件不存在");
        assertError(root, "bad-version", "name: bad-version\ndescription: x\nversion: 0\n",
                "version 应该是从 1 开始的整数");
        assertError(root, "bad-match", "name: bad-match\ndescription: x\nmatch: {keywords: [甲, 乙], min_keywords: 3}\n",
                "match.min_keywords 应该在 1 到 2");
        assertError(root, "bad-match-key", "name: bad-match-key\ndescription: x\nmatch: {title: [甲]}\n",
                "match：不认识的键 title");
        assertError(root, "bad-engine", "name: bad-engine\ndescription: x\nengine: 6000\n",
                "engine 应该是一组键值");
        assertError(root, "not-a-map", "- just\n- a list\n", "的内容应该是一组键值");
    }

    @Test
    void 做法说明的文件不存在也算写错(@TempDir Path root) throws IOException {
        Path dir = root.resolve("no-instructions");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yaml"), "name: no-instructions\ndescription: x\n", StandardCharsets.UTF_8);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SkillLoader.load(dir));
        assertTrue(e.getMessage().contains("instructions 指向的文件不存在"), e.getMessage());
    }

    /** 建一个 skill 目录：清单是给定的内容，再放一个空的 SKILL.md。 */
    private static Path skill(Path root, String name, String manifest) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# " + name + "\n", StandardCharsets.UTF_8);
        if (manifest != null) {
            Files.writeString(dir.resolve("skill.yaml"), manifest, StandardCharsets.UTF_8);
        }
        return dir;
    }

    private static void assertError(Path root, String name, String manifest, String expected) throws IOException {
        Path dir = skill(root, name, manifest);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SkillLoader.load(dir),
                name + " 应该报错");
        assertTrue(e.getMessage().contains(expected), name + " 的报错是：" + e.getMessage());
        assertTrue(e.getMessage().contains(name), "报错里要说明是哪个 skill：" + e.getMessage());
    }
}
