package com.cb.agent.app.extract;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.capability.extract.schema.SchemaLoader;
import com.cb.agent.capability.extract.skill.SkillExtraction;
import com.cb.agent.core.llm.LlmClientProvider;
import com.cb.agent.core.skill.Skill;
import com.cb.agent.core.skill.SkillLoader;
import com.cb.agent.core.skill.SkillRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

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

    /** skill 名称的样子。只让这样的名称去 skills 目录里找，带路径分隔符或 {@code ..} 的进不来。 */
    private static final Pattern SKILL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    /** 文档没有标题时，把正文开头这么多字当作标题去匹配。 */
    private static final int TITLE_FALLBACK_CHARS = 200;

    private final LlmClientProvider llms;
    private final Path skillsDir;

    /**
     * @param skillsDir 放 skill 的目录，每个子目录一个 skill。相对路径按程序的当前目录算。
     *                  留空表示自己找，见 {@link #resolveSkillsDir}
     */
    public ExtractService(LlmClientProvider llms, @Value("${agent.skills.dir:}") String skillsDir) {
        this.llms = llms;
        this.skillsDir = resolveSkillsDir(skillsDir);
    }

    /**
     * 定下 skill 目录在哪。
     *
     * <p>配置里写了就用写的。没写时找仓库里 Claude Code 用的那个目录（{@code .claude/skills}）：
     * 程序可能是在 agent-platform 目录下启动的（命令行、mvn），也可能是在仓库根目录下启动的
     * （IDE 默认把项目根目录当作当前目录），两处都看一下。
     */
    static Path resolveSkillsDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        for (String candidate : List.of("../.claude/skills", ".claude/skills")) {
            if (Files.isDirectory(Path.of(candidate))) {
                return Path.of(candidate);
            }
        }
        return Path.of("../.claude/skills"); // 都没有：留着这个路径，用到时的报错会说明找的是哪
    }

    /** skill 目录（绝对路径）。启动时打到日志里，方便确认用的是哪一个。 */
    public Path skillsDir() {
        return skillsDir.toAbsolutePath().normalize();
    }

    /** 现有的 skill，按名称排序。 */
    public List<Skill> skills() {
        return SkillLoader.loadAll(skillsDir);
    }

    /**
     * 按规则给一份文档选 skill（方案第 9.4 节的第一级）。只在能用来抽取的 skill 里选，不调用模型。
     *
     * @return 结论和依据。没选出来时 {@link SkillRouter.Decision#routed()} 是 false
     */
    public SkillRouter.Decision route(Document doc) {
        List<Skill> candidates = skills().stream().filter(skill -> skill.schema() != null).toList();
        StringBuilder text = new StringBuilder();
        for (Block block : doc.blocks()) {
            text.append(block.text()).append('\n');
        }
        // 读取器没认出标题时，拿开头的一小段当标题：标题总在最前面
        String title = doc.title() != null ? doc.title()
                : text.substring(0, Math.min(text.length(), TITLE_FALLBACK_CHARS));
        return SkillRouter.route(candidates, title, text.toString());
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
        return prepare(skill(nameOrDirectory), adjust, model, baseUrl);
    }

    /**
     * 准备按 skill 抽取，只认 skills 目录里的名称，用配置里的模型。HTTP 接口用这一个。
     *
     * <p>和 {@link #bySkill} 的区别：那一个还接受一个目录的路径、可以临时换模型接口，是给本机的命令行用的。
     * 请求来自网络时，这两样都不能让调用方指定：前者能让服务去读任意目录，后者能让文档被发到别处。
     *
     * @param name   skill 的名称
     * @param adjust 这一次临时改的引擎参数
     * @throws IllegalArgumentException 名称不对、没有这个 skill、字段定义有问题，或者引擎参数不在范围里
     */
    public ExtractJob bySkillName(String name, UnaryOperator<EngineOptions> adjust) {
        if (name == null || !SKILL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("skill 的名称不对：" + name + "。名称由字母、数字、横线、下划线组成，"
                    + "现有的 skill 用 GET /api/v1/skills 查看");
        }
        return prepare(SkillLoader.find(skillsDir, name), adjust, null, null);
    }

    /**
     * 确认配置里的模型接口可以用：地址配了，而且在允许的名单里。不会真的去连。
     * 服务启动时调用一次，配置有问题就在启动时报出来，不用等到第一个任务失败。
     *
     * @throws IllegalArgumentException 配置不完整，或者接口地址不在允许的范围里
     */
    public void checkModel() {
        llms.get(null, null);
    }

    private ExtractJob prepare(Skill skill, UnaryOperator<EngineOptions> adjust, String model, String baseUrl) {
        SkillExtraction extraction = SkillExtraction.of(skill, EngineOptions.DEFAULTS);
        return new ExtractJob(skill.name(), skill.version(), extraction.schema(),
                adjust.apply(extraction.options()), llms.get(model, baseUrl));
    }

    /**
     * 准备按一份字段定义抽取，不经过 skill。调试字段定义、处理还没有做成 skill 的文档时用。
     *
     * @param schemaFile 字段定义文件（YAML 或 JSON）
     */
    public ExtractJob bySchema(Path schemaFile, UnaryOperator<EngineOptions> adjust, String model, String baseUrl) {
        Schema schema = SchemaLoader.load(schemaFile);
        return new ExtractJob(null, null, schema, adjust.apply(EngineOptions.DEFAULTS), llms.get(model, baseUrl));
    }
}
