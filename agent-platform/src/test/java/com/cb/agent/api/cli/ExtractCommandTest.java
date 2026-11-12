package com.cb.agent.api.cli;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.extract.ExtractService;
import com.cb.agent.capability.document.DocumentLoader;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.core.llm.LlmCallException;
import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.Usage;
import com.cb.agent.testsupport.Golden;
import com.cb.agent.testsupport.PersonnelFormRules;
import com.cb.agent.testsupport.RuleLlm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * extract 命令的测试：按 skill 抽取能不能跑通，结果写到哪，写错了、出错了时的表现。
 *
 * <p>模型是假的（{@link RuleLlm}，按正则在片段里找），其余都是真的：读的是教程里的示例任免表，
 * 用的是仓库里的 personnel-appointment 这个 skill 和它的字段定义。教程里贴的输出如果变了，这里会先发现。
 */
class ExtractCommandTest {

    private static final String FORM = "samples/personnel_form_1.6.json";
    private static final String CONTRACT = "src/test/resources/fixtures/paddle/sample_contract";
    private static final String SKILL = "personnel-appointment";

    /** 每次取模型客户端时给的模型名和接口地址。 */
    private final List<String> requested = new ArrayList<>();
    private LlmClient llm = new RuleLlm(PersonnelFormRules.rules());
    private final CommandLineEntry cli = new CommandLineEntry(
            new DocumentReadService(new DocumentLoader(List.of(new PaddleOcrVlJsonReader()))),
            new ExtractService((model, baseUrl) -> {
                requested.add(model + " @ " + baseUrl);
                return llm;
            }, "../.claude/skills"));
    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    private int run(String... args) {
        return cli.execute(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    // ---- 按 skill 抽取 ----

    @Test
    void 按skill抽取示例任免表() {
        assertEquals(0, run("extract", FORM, "--skill", SKILL), err());

        JsonNode result = Golden.JSON.readTree(out());
        assertEquals("personnel_form_1.6", result.get("document").stringValue());
        assertEquals("personnel_appointment", result.get("schema").stringValue());
        JsonNode data = result.get("data");
        assertEquals("林致远", data.get("姓名").stringValue());
        assertEquals("1975.03（49岁）", data.get("出生年月（岁）").stringValue(), "年月照原文保留，不推算");
        assertEquals("山东济南", data.get("籍贯").stringValue());
        assertEquals("山东青岛", data.get("出生地").stringValue(), "籍贯和出生地在同一行，不能取反");
        assertEquals("青岚市统计局党组书记、局长", data.get("拟任职务").stringValue());
        assertTrue(data.get("拟免职务").isNull(), "空着的栏是 null");
        assertEquals("无", data.get("奖惩情况").stringValue(), "原文明写的“无”保留");

        JsonNode family = data.get("家庭成员及主要社会关系");
        assertEquals(4, family.size(), "四个人，表头那一行不算");
        assertEquals(Golden.canon(Map.of("称谓", "妻子", "姓名", "苏婉清", "年龄", "47", "政治面貌", "中共党员",
                "工作单位及职务", "青岚市第二中学教师")), Golden.canon(Golden.plain(family.get(0))));

        JsonNode summary = result.get("summary");
        assertEquals(22, summary.get("ok").intValue());
        assertEquals(0, summary.get("unverified").intValue());
        assertEquals(4, summary.get("missing").intValue());
        assertEquals(0, summary.get("needs_review").size(), "必填的只有姓名，它找到了");
        assertEquals("personnel_form_1.6: 通过 22，未通过核对 0，未找到 4", err().strip());
    }

    @Test
    void 每个值带着它在原件上的位置() {
        assertEquals(0, run("extract", FORM, "--skill", SKILL), err());
        JsonNode fields = Golden.JSON.readTree(out()).get("fields");

        // 这张表从第 1 页跨到第 2 页：姓名在第 1 页，呈报单位在第 2 页
        assertEquals("1", fields.get("姓名").get("pages").stringValue());
        assertEquals("2", fields.get("呈报单位").get("pages").stringValue());
        JsonNode locator = fields.get("呈报单位").get("locator");
        assertEquals("OCR", locator.get("origin").stringValue());
        assertEquals(2, locator.get("evidence_regions").get(0).get("page").intValue());
        assertEquals("BLOCK", locator.get("evidence_regions").get(0).get("granularity").stringValue());
        // 家庭成员的每一项各有自己的出处
        JsonNode members = fields.get("家庭成员及主要社会关系").get("items");
        assertEquals("| 家庭主要成员及重要社会关系 | 妻子 | 苏婉清 | 47 | 中共党员 | 青岚市第二中学教师 |",
                members.get(0).get("evidence").stringValue());
        assertEquals("2", members.get(0).get("pages").stringValue());
    }

    @Test
    void 引擎参数用的是skill清单里写的命令行给了就以命令行为准() {
        assertEquals(0, run("extract", FORM, "--skill", SKILL, "--model", "m1", "--base-url", "http://127.0.0.1:1/v1"));
        assertEquals(List.of("m1 @ http://127.0.0.1:1/v1"), requested, "模型名和接口地址交给了模型客户端的提供者");

        errBuf.reset();
        assertEquals(1, run("extract", FORM, "--skill", SKILL, "--top-k", "0"));
        assertTrue(err().contains("topK 要大于 0"), err());
    }

    @Test
    void 可以不用skill直接给字段定义() {
        llm = new RuleLlm(Map.of("contract_no", RuleLlm.Rule.value("合同编号：(\\S+)")));
        assertEquals(0, run("extract", CONTRACT, "--schema", "src/test/resources/fixtures/schemas/contract.yaml"), err());

        JsonNode result = Golden.JSON.readTree(out());
        assertEquals("contract", result.get("schema").stringValue());
        assertFalse(result.get("data").get("contract_no").isNull());
        assertEquals("ok", result.get("fields").get("contract_no").get("status").stringValue());
    }

    // ---- 结果写到哪 ----

    @Test
    void 一份文档时o是文件名(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out").resolve("form.json"); // 上一级目录还不存在，会自动建
        assertEquals(0, run("extract", FORM, "--skill", SKILL, "-o", file.toString()), err());

        assertEquals("", out(), "写到文件时不再打印");
        assertEquals("林致远", Golden.read(file).get("data").get("姓名").stringValue());
    }

    @Test
    void 一份文档时o给的是已有的目录就按文档名写在里面(@TempDir Path dir) {
        assertEquals(0, run("extract", FORM, "--skill", SKILL, "-o", dir.toString()), err());

        assertTrue(Files.isRegularFile(dir.resolve("personnel_form_1.6.json")));
    }

    @Test
    void 多份文档时每份写一个文件(@TempDir Path dir) {
        Path outDir = dir.resolve("results");
        assertEquals(0, run("extract", FORM, CONTRACT, "--skill", SKILL, "-o", outDir.toString()), err());

        assertEquals("", out());
        assertTrue(Files.isRegularFile(outDir.resolve("personnel_form_1.6.json")));
        assertTrue(Files.isRegularFile(outDir.resolve("sample_contract.json")));
        assertEquals(2, err().lines().count(), "每份文档一行概况：" + err());
        // 合同不是任免表，一个字段都找不到，必填的姓名进入复核清单
        assertTrue(err().contains("sample_contract: 通过 0，未通过核对 0，未找到 26，需要复核：姓名"), err());
    }

    @Test
    void 多份文档里一份失败不影响其余的但退出码是1(@TempDir Path dir) {
        LlmClient real = llm;
        llm = new LlmClient() {
            @Override
            public String complete(String system, String user) {
                if (user.contains("软件开发服务合同")) {
                    throw new LlmCallException("连接超时");
                }
                return real.complete(system, user);
            }

            @Override
            public Usage usage() {
                return Usage.ZERO;
            }
        };
        assertEquals(1, run("extract", CONTRACT, FORM, "--skill", SKILL, "-o", dir.toString()));

        assertTrue(err().contains("sample_contract: 失败 - 连接超时"), err());
        assertTrue(Files.isRegularFile(dir.resolve("personnel_form_1.6.json")), "后面的那份照常处理");
        assertFalse(Files.exists(dir.resolve("sample_contract.json")));
    }

    // ---- 写错了、出错了 ----

    @Test
    void 没说用哪个skill退出码是2() {
        assertEquals(2, run("extract", FORM));
        assertTrue(err().contains("--skill"), err());
        errBuf.reset();
        assertEquals(2, run("extract", FORM, "--skill", SKILL, "--schema", "x.yaml"));
        assertTrue(err().contains("只能给一个"), err());
        assertEquals(List.of(), requested, "命令写错了就不该去连模型");
    }

    @Test
    void skill不存在时列出现有的() {
        assertEquals(1, run("extract", FORM, "--skill", "invoice"));
        assertTrue(err().contains("没有名为 invoice 的 skill"), err());
        assertTrue(err().contains(SKILL), "要告诉用户有哪些可以选：" + err());
    }

    @Test
    void 模型调用失败时说明是模型接口的问题() {
        llm = new LlmClient() {
            @Override
            public String complete(String system, String user) {
                throw new LlmCallException("401 Unauthorized");
            }

            @Override
            public Usage usage() {
                return Usage.ZERO;
            }
        };
        assertEquals(1, run("extract", FORM, "--skill", SKILL));
        assertEquals("模型接口调用失败：401 Unauthorized", err().strip());
        assertEquals("", out());
    }

    @Test
    void skills列出现有的skill() {
        assertEquals(0, run("skills"));
        assertTrue(out().contains("personnel-appointment  干部任免审批表（第 1 版），可以用来抽取"), out());
        assertEquals(List.of(), requested);
    }
}
