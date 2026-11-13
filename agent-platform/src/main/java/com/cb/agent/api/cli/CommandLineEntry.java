package com.cb.agent.api.cli;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.extract.ExtractService;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.core.llm.LlmCallException;
import com.cb.agent.core.skill.Skill;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令行入口。作为服务运行时（{@code serve}）没有它：那时请求从 HTTP 接口进来，见 {@code api.http}。
 *
 * <p>四个命令，前三个不调用模型：
 * <pre>
 *   inspect &lt;文件或目录&gt;… [--show] [--json]
 *       把识别结果读进来，打印出读成了什么样
 *   chunks  &lt;文件或目录&gt;… [--chunk-tokens N] [--context-tokens N] [--show]
 *       看文档会被切成哪几片
 *   skills
 *       列出现有的 skill
 *   extract &lt;文件或目录&gt;… --skill 名称 [-o 输出]
 *       按 skill 的字段定义抽取数据。会调用模型
 * </pre>
 *
 * <p>退出码：0 成功；1 处理失败（文件不存在、不是认识的格式、模型调用失败、批量时有文档失败）；2 命令写错了。
 */
@Component
@ConditionalOnNotWebApplication
public class CommandLineEntry implements ApplicationRunner, ExitCodeGenerator {

    /** 每片的大小上限的默认值，和 Python 版相同。 */
    static final int DEFAULT_CHUNK_TOKENS = 1000;
    /** 一次调用里放多少原文的默认值；整篇文档不超过它时不切片。和 Python 版相同。 */
    static final int DEFAULT_CONTEXT_TOKENS = 6000;

    private final DocumentReadService documents;
    private final ExtractService extraction;
    private final JsonMapper json = JsonMapper.builder().build();
    private int exitCode = 0;

    public CommandLineEntry(DocumentReadService documents, ExtractService extraction) {
        this.documents = documents;
        this.extraction = extraction;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Windows 控制台的默认编码不是 UTF-8，直接用 System.out 打印中文会变成乱码，所以自己建一个按 UTF-8 输出的流
        PrintStream out = utf8(FileDescriptor.out);
        PrintStream err = utf8(FileDescriptor.err);
        exitCode = execute(args.getSourceArgs(), out, err);
    }

    /**
     * 执行一条命令。单独拿出来是为了测试时能直接调用，不用启动整个应用。
     *
     * @param raw 命令行参数，第一个是命令
     * @return 退出码
     */
    int execute(String[] raw, PrintStream out, PrintStream err) {
        if (raw.length == 0) {
            printUsage(out);
            return 0;
        }
        try {
            CliArgs cli = CliArgs.parse(raw);
            switch (cli.command()) {
                case "inspect" -> inspect(cli, out);
                case "chunks" -> chunks(cli, out);
                case "skills" -> skills(out);
                case "extract" -> {
                    return ExtractCommand.run(cli, () -> read(cli), extraction, out, err);
                }
                default -> throw new CliArgs.UsageException("不认识的命令：" + cli.command());
            }
            return 0;
        } catch (CliArgs.UsageException e) {
            err.println(e.getMessage());
            printUsage(err);
            return 2;
        } catch (IllegalArgumentException e) {
            // 读取器、skill 和字段定义的检查抛出的都是这一种异常，消息已经是写给人看的，直接打印
            err.println("错误：" + e.getMessage());
            return 1;
        } catch (LlmCallException e) {
            // 认证、网络、模型名写错等
            err.println("模型接口调用失败：" + e.getMessage());
            return 1;
        }
    }

    /** skills：列出现有的 skill。 */
    private void skills(PrintStream out) {
        List<Skill> all = extraction.skills();
        if (all.isEmpty()) {
            out.println("没有找到 skill。");
            return;
        }
        for (Skill skill : all) {
            out.println(skill.name() + "  " + skill.title() + "（第 " + skill.version() + " 版）"
                    + (skill.schema() == null ? "" : "，可以用来抽取"));
            out.println("    " + skill.description().replaceAll("\\s+", " "));
        }
    }

    /** inspect：读进来，打印出读成了什么样。 */
    private void inspect(CliArgs cli, PrintStream out) {
        List<Document> docs = read(cli);
        if (cli.flag("json")) {
            List<Map<String, Object>> dumps = new ArrayList<>();
            for (Document doc : docs) {
                dumps.add(documents.dump(doc));
            }
            out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(dumps));
        } else {
            for (Document doc : docs) {
                DocumentPrinter.print(out, doc, cli.flag("show"));
            }
        }
    }

    /** chunks：看文档会被切成哪几片。 */
    private void chunks(CliArgs cli, PrintStream out) {
        int chunkTokens = cli.intValue("chunk-tokens", DEFAULT_CHUNK_TOKENS);
        int contextTokens = cli.intValue("context-tokens", DEFAULT_CONTEXT_TOKENS);
        for (Document doc : read(cli)) {
            ChunkPrinter.print(out, doc, documents.chunk(doc, chunkTokens, contextTokens), cli.flag("show"));
        }
    }

    private List<Document> read(CliArgs cli) {
        if (cli.inputs().isEmpty()) {
            throw new CliArgs.UsageException(cli.command() + " 后面要给出至少一个文件或目录");
        }
        return documents.read(cli.inputs().stream().map(Path::of).toList());
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static PrintStream utf8(FileDescriptor fd) {
        return new PrintStream(new FileOutputStream(fd), true, StandardCharsets.UTF_8);
    }

    private static void printUsage(PrintStream out) {
        out.println("用法：");
        out.println("  serve [--server.port=端口]");
        out.println("      作为服务一直运行，提供 HTTP 接口（提交抽取任务、查状态、取结果）。按 Ctrl+C 停止。");
        out.println("      默认只在本机的 8080 端口监听，配置见 application.yml 的 server 和 agent.api。");
        out.println();
        out.println("  inspect <文件或目录>… [--show] [--json]");
        out.println("      把识别结果读进来，打印出读成了什么样。不调用模型。");
        out.println("        --show   打印每个块的全文（默认只打印开头一小段）");
        out.println("        --json   输出完整的结构（JSON）");
        out.println();
        out.println("  chunks <文件或目录>… [--chunk-tokens N] [--context-tokens N] [--show]");
        out.println("      看文档会被切成哪几片。不调用模型。");
        out.println("        --chunk-tokens N     每片的大小上限（默认 " + DEFAULT_CHUNK_TOKENS + "）");
        out.println("        --context-tokens N   整篇文档不超过它时不切片（默认 " + DEFAULT_CONTEXT_TOKENS + "）");
        out.println("        --show               打印每片的全文");
        out.println();
        out.println("  skills");
        out.println("      列出现有的 skill。不调用模型。");
        out.println();
        out.println("  extract <文件或目录>… --skill 名称 [-o 输出] [选项]");
        out.println("      按 skill 的字段定义抽取数据。会调用模型，文档的内容会发给模型接口。");
        out.println("        --skill 名称         用哪个 skill（skills 命令列出的名称，或者一个 skill 目录）");
        out.println("        --schema 文件        不用 skill，直接给一份字段定义（和 --skill 二选一）");
        out.println("        -o, --output 路径    结果写到哪：一份文档时是文件，多份时是目录（默认 "
                + ExtractCommand.DEFAULT_OUTPUT_DIR + "）；不给就打印出来");
        out.println("        --top-k N            每个字段每一轮看几个片段");
        out.println("        --max-rounds N       一个字段最多找几轮");
        out.println("        --no-route           不让模型按目录定位，只用关键词检索");
        out.println("        --workers N          同时进行的模型调用数");
        out.println("        --chunk-tokens N、--context-tokens N   同 chunks 命令");
        out.println("        --model 名称、--base-url 地址          临时换一个模型或接口地址");
        out.println();
        out.println("  <文件或目录> 是 PaddleOCR-VL 的识别结果：一个 JSON 文件，或者放着 JSON 文件的目录。可以给多个。");
    }
}
