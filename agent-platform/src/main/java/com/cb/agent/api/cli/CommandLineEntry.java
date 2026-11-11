package com.cb.agent.api.cli;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.capability.document.model.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
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
 * 命令行入口。
 *
 * <p>现在只有一个命令：
 * <pre>
 *   inspect &lt;文件或目录&gt;… [--show] [--json]
 * </pre>
 * 把识别结果读进来，打印出读成了什么样。不调用模型。
 * <ul>
 *   <li>不带选项：每个块打印一行概况和开头一小段文字；</li>
 *   <li>{@code --show}：打印每个块的全文；</li>
 *   <li>{@code --json}：输出完整的结构（JSON 数组，每份文档一项），可以存成文件或交给别的程序。</li>
 * </ul>
 *
 * <p>退出码：0 成功；1 读取失败（文件不存在、不是认识的格式）；2 命令写错了。
 */
@Component
public class CommandLineEntry implements ApplicationRunner, ExitCodeGenerator {

    private final DocumentReadService documents;
    private final JsonMapper json = JsonMapper.builder().build();
    private int exitCode = 0;

    public CommandLineEntry(DocumentReadService documents) {
        this.documents = documents;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Windows 控制台的默认编码不是 UTF-8，直接用 System.out 打印中文会变成乱码，所以自己建一个按 UTF-8 输出的流
        PrintStream out = utf8(FileDescriptor.out);
        PrintStream err = utf8(FileDescriptor.err);

        List<String> words = args.getNonOptionArgs();
        if (words.isEmpty()) {
            printUsage(out);
            return;
        }
        String command = words.get(0);
        if (!command.equals("inspect")) {
            err.println("不认识的命令：" + command);
            printUsage(err);
            exitCode = 2;
            return;
        }
        List<String> inputs = words.subList(1, words.size());
        if (inputs.isEmpty()) {
            err.println("inspect 后面要给出至少一个文件或目录");
            printUsage(err);
            exitCode = 2;
            return;
        }
        try {
            List<Document> docs = documents.read(inputs.stream().map(Path::of).toList());
            if (args.containsOption("json")) {
                List<Map<String, Object>> dumps = new ArrayList<>();
                for (Document doc : docs) {
                    dumps.add(documents.dump(doc));
                }
                out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(dumps));
            } else {
                for (Document doc : docs) {
                    DocumentPrinter.print(out, doc, args.containsOption("show"));
                }
            }
        } catch (IllegalArgumentException e) {
            // 读取器抛出的都是这一种异常，消息已经是写给人看的，直接打印
            err.println("错误：" + e.getMessage());
            exitCode = 1;
        }
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
        out.println("  inspect <文件或目录>… [--show] [--json]");
        out.println();
        out.println("  把识别结果读进来，打印出读成了什么样。不调用模型。");
        out.println("    <文件或目录>  PaddleOCR-VL 的识别结果：一个 JSON 文件，或者放着 JSON 文件的目录。可以给多个");
        out.println("    --show        打印每个块的全文（默认只打印开头一小段）");
        out.println("    --json        输出完整的结构（JSON）");
    }
}
