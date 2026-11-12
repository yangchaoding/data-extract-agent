package com.cb.agent.app.extract;

import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.capability.extract.schema.SchemaLoader;
import com.cb.agent.capability.extract.skill.SkillExtraction;
import com.cb.agent.core.llm.LlmClientProvider;
import com.cb.agent.core.skill.Skill;
import com.cb.agent.core.skill.SkillLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * "抽取数据"这个用例：按一个 skill 的定义，或者直接按一份字段定义，从文档里抽出结构化数据。
 *
 * <p>接入层只调用这里。这里负责把三样东西凑齐：字段定义、引擎参数、模型客户端，
 * 凑齐之后交出一个 {@link ExtractJob}，由调用方拿去逐份文档执行。
 *
 * <p>引擎参数有三层，后面的盖住前面的：引擎的默认值 → skill 清单里写的 → 调用方这一次临时改的。
 */
@Service
public class ExtractService {

    private final LlmClientProvider llms;
    private final Path skillsDir;

    /**
     * @param skillsDir 放 skill 的目录，每个子目录一个 skill。相对路径按程序的当前目录算
     */
    public ExtractService(LlmClientProvider llms, @Value("${agent.skills.dir:../.claude/skills}") String skillsDir) {
        this.llms = llms;
        this.skillsDir = Path.of(skillsDir);
    }

    /** 现有的 skill，按名称排序。 */
    public List<Skill> skills() {
        return SkillLoader.loadAll(skillsDir);
    }

    /**
     * 找一个 skill。
     *
     * @param nameOrDirectory skill 的名称（到 skills 目录里找），或者一个 skill 目录的路径
     * @throws IllegalArgumentException 没有这个 skill，或者它的清单写错了
     */
    public Skill skill(String nameOrDirectory) {
        try {
            Path asDirectory = Path.of(nameOrDirectory);
            if (Files.isDirectory(asDirectory)) {
                return SkillLoader.load(asDirectory);
            }
        } catch (InvalidPathException e) {
            // 不是一个合法的路径，那就当成名称去找
        }
        return SkillLoader.find(skillsDir, nameOrDirectory);
    }

    /**
     * 准备按 skill 抽取。
     *
     * @param nameOrDirectory skill 的名称或目录
     * @param adjust          这一次临时改的引擎参数；不改就传 {@code UnaryOperator.identity()}
     * @param model           模型名；null 表示用配置里的
     * @param baseUrl         模型接口地址；null 表示用配置里的
     * @throws IllegalArgumentException skill 或字段定义有问题，或者模型接口地址不在允许的范围里
     */
    public ExtractJob bySkill(String nameOrDirectory, UnaryOperator<EngineOptions> adjust, String model,
                              String baseUrl) {
        SkillExtraction extraction = SkillExtraction.of(skill(nameOrDirectory), EngineOptions.DEFAULTS);
        return new ExtractJob(extraction.skill().name(), extraction.schema(), adjust.apply(extraction.options()),
                llms.get(model, baseUrl));
    }

    /**
     * 准备按一份字段定义抽取，不经过 skill。调试字段定义、处理还没有做成 skill 的文档时用。
     *
     * @param schemaFile 字段定义文件（YAML 或 JSON）
     */
    public ExtractJob bySchema(Path schemaFile, UnaryOperator<EngineOptions> adjust, String model, String baseUrl) {
        Schema schema = SchemaLoader.load(schemaFile);
        return new ExtractJob(null, schema, adjust.apply(EngineOptions.DEFAULTS), llms.get(model, baseUrl));
    }
}
