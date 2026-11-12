package com.cb.agent.core.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 一个 skill 的清单（{@code skill.yaml}）。对应方案第 9.1 节。
 *
 * <p>skill 是一包数据，告诉 agent 遇到某类任务该怎么做。清单里的东西分三类：
 * <ul>
 *   <li>选它的时候要看：{@link #name()}、{@link #description()}、{@link #match()}；</li>
 *   <li>选中之后要读的：{@link #instructions()}（{@code SKILL.md} 的正文）；</li>
 *   <li>由工具使用、不进上下文的资源：{@link #schema()} 字段定义、{@link #validators()} 校验器。</li>
 * </ul>
 *
 * <p>清单只管"有什么"，不管"怎么用"。{@code engine} 里的参数由用到它的能力去解释，
 * 所以这里原样保存成 {@code Map}，键是清单里的写法（camelCase）。
 *
 * <p>路径都是加载时按 skill 目录算好的绝对路径，调用方不用再关心相对谁。
 *
 * @param name         名称，在 skills 目录里唯一，和目录名相同
 * @param version      版本。改了字段定义或做法就加一，供以后回看"这个结果是用哪版做的"
 * @param title        中文名，给人看的
 * @param description  一两句话说明这类文档长什么样、不包括什么。选 skill 时给模型看的就是它
 * @param match        用规则选这个 skill 的条件（按方案第 9.4 节，规则路由在阶段 5）
 * @param directory    skill 所在的目录
 * @param instructions 做法说明（{@code SKILL.md}）的路径
 * @param schema       字段定义的路径；这个 skill 不抽取数据时为 null
 * @param tools        这个 skill 允许用到的工具名
 * @param engine       抽取引擎的参数，写在清单里的原样；键不认识时由用到它的能力报错
 * @param validators   字段定义之外的校验器名
 */
public record Skill(String name, int version, String title, String description, SkillMatch match,
                    Path directory, Path instructions, Path schema, List<String> tools,
                    Map<String, Object> engine, List<String> validators) {

    public Skill {
        tools = List.copyOf(tools);
        engine = Map.copyOf(engine);
        validators = List.copyOf(validators);
    }

    /** 读做法说明（{@code SKILL.md}）的正文。选中这个 skill 之后才读，不进别的 skill 的上下文。 */
    public String readInstructions() {
        try {
            return Files.readString(instructions, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读不了 " + instructions + "：" + e.getMessage(), e);
        }
    }
}
