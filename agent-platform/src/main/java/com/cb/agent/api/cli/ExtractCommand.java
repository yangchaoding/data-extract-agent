package com.cb.agent.api.cli;

import com.cb.agent.app.extract.ExtractJob;
import com.cb.agent.app.extract.ExtractService;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.engine.ExtractionResult;
import com.cb.agent.capability.extract.format.PyFormat;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * extract 命令：按 skill 的字段定义（或者直接给的一份字段定义）从识别结果里抽数据。
 *
 * <p>结果去哪里，和 Python 版相同：
 * <ul>
 *   <li>输入里有几份文档时，{@code -o} 是输出目录（默认 {@code extract_out}），每份写一个 {@code <文档名>.json}；</li>
 *   <li>只有一份文档时，{@code -o} 是文件名；给的是一个已有的目录时，写在目录里，文件名叫 {@code <文档名>.json}；</li>
 *   <li>不给 {@code -o} 时打到标准输出。</li>
 * </ul>
 *
 * <p>一份文档失败不影响其余的（批量时），每份的结果概况打到标准错误输出。有失败时退出码是 1。
 */
final class ExtractCommand {

    /** 多份文档时默认的输出目录，和 Python 版相同。 */
    static final String DEFAULT_OUTPUT_DIR = "extract_out";

    private ExtractCommand() {
    }

    /**
     * @param input 读文档的动作。放在准备好抽取之后才执行：skill 找不到、字段定义写错、
     *              模型接口地址不让连，这些都应该在读文件之前就报出来
     * @return 退出码
     */
    static int run(CliArgs cli, Supplier<List<Document>> input, ExtractService service, PrintStream out,
                   PrintStream err) {
        ExtractJob job = job(cli, service);
        List<Document> docs = input.get();
        String output = cli.value("output");

        Path outDir = null;
        if (docs.size() > 1) { // 多份文档：每份写一个文件
            outDir = Path.of(output == null ? DEFAULT_OUTPUT_DIR : output);
            createDirectories(outDir);
        }
        int failed = 0;
        for (Document doc : docs) {
            ExtractionResult result;
            try {
                result = job.run(doc);
            } catch (RuntimeException e) { // 一份文档失败不影响其余的
                if (outDir == null) {
                    throw e;
                }
                failed++;
                err.println(doc.name() + ": 失败 - " + e.getMessage());
                continue;
            }
            String payload = PyFormat.jsonIndented(result.toMap(), 2);
            if (outDir != null) {
                write(outDir.resolve(doc.name() + ".json"), payload);
            } else if (output != null) {
                write(target(output, doc.name()), payload);
            } else {
                out.println(payload);
            }
            err.println(summary(doc.name(), result));
        }
        return failed > 0 ? 1 : 0;
    }

    /** 凑齐字段定义、引擎参数和模型客户端。写错了在这一步就报出来。 */
    private static ExtractJob job(CliArgs cli, ExtractService service) {
        String skill = cli.value("skill");
        String schema = cli.value("schema");
        if (skill == null && schema == null) {
            throw new CliArgs.UsageException("extract 要用 --skill 指明用哪个 skill，或者用 --schema 给出字段定义文件");
        }
        if (skill != null && schema != null) {
            throw new CliArgs.UsageException("--skill 和 --schema 只能给一个");
        }
        UnaryOperator<EngineOptions> adjust = adjust(cli);
        String model = cli.value("model");
        String baseUrl = cli.value("base-url");
        return skill != null
                ? service.bySkill(skill, adjust, model, baseUrl)
                : service.bySchema(Path.of(schema), adjust, model, baseUrl);
    }

    /** 命令行上临时改的引擎参数；没给的就用 skill 清单里写的或默认值。 */
    private static UnaryOperator<EngineOptions> adjust(CliArgs cli) {
        return options -> {
            EngineOptions out = options;
            if (cli.value("chunk-tokens") != null) {
                out = out.chunkTokens(cli.intValue("chunk-tokens", 0));
            }
            if (cli.value("context-tokens") != null) {
                out = out.contextTokens(cli.intValue("context-tokens", 0));
            }
            if (cli.value("top-k") != null) {
                out = out.topK(cli.intValue("top-k", 0));
            }
            if (cli.value("max-rounds") != null) {
                out = out.maxRounds(cli.intValue("max-rounds", 0));
            }
            if (cli.value("workers") != null) {
                out = out.workers(cli.intValue("workers", 0));
            }
            if (cli.flag("no-route")) {
                out = out.route(false);
            }
            return out;
        };
    }

    /** 只有一份文档时 {@code -o} 指向的地方。指向一个已有的目录时，在它里面按文档名写文件。 */
    private static Path target(String output, String documentName) {
        Path path = Path.of(output);
        if (Files.isDirectory(path)) {
            return path.resolve(documentName + ".json");
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            createDirectories(parent);
        }
        return path;
    }

    /** 一份文档的结果概况，打到标准错误输出，和 Python 版相同的写法。 */
    static String summary(String documentName, ExtractionResult result) {
        List<String> review = result.needsReview();
        String needsReview = review.isEmpty() ? "" : "，需要复核：" + String.join("、", review);
        return documentName + ": 通过 " + result.count("ok") + "，未通过核对 " + result.count("unverified")
                + "，未找到 " + result.count("missing") + needsReview;
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("建不了输出目录 " + dir + "：" + e.getMessage(), e);
        }
    }

    private static void write(Path path, String payload) {
        try {
            Files.writeString(path, payload, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("写不了 " + path + "：" + e.getMessage(), e);
        }
    }
}
