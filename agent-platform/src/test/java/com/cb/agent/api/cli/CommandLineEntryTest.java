package com.cb.agent.api.cli;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.extract.ExtractService;
import com.cb.agent.capability.document.DocumentLoader;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行的测试：命令能不能执行，参数认不认，写错了时退出码和提示对不对。
 *
 * <p>直接调用 {@link CommandLineEntry#execute}，不启动整个应用，所以很快。
 * 用的输入是教程里的那两份示例数据，教程里贴的输出如果变了，这里会先发现。
 */
class CommandLineEntryTest {

    private static final String FORM = "samples/personnel_form_1.6.json";
    private static final String CONTRACT = "src/test/resources/fixtures/paddle/sample_contract";

    private final CommandLineEntry cli = new CommandLineEntry(
            new DocumentReadService(new DocumentLoader(List.of(new PaddleOcrVlJsonReader()))),
            // 这里测的命令都不调用模型；真去取模型客户端就说明哪里走错了
            new ExtractService((model, baseUrl) -> {
                throw new AssertionError("这些命令不应该用到模型");
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

    // ---- inspect ----

    @Test
    void inspect打印出文档的概况() {
        assertEquals(0, run("inspect", FORM));
        assertTrue(out().contains("# personnel_form_1.6：2 页，3 个版面块"), out());
        assertTrue(out().contains("第1页 10 行，第2页 9 行"), out());
        assertEquals("", err(), "成功时标准错误输出里不该有东西");
    }

    @Test
    void inspect加json输出的是JSON数组() {
        assertEquals(0, run("inspect", FORM, "--json"));
        assertTrue(out().trim().startsWith("["), out());
        assertTrue(out().contains("\"row_pages\""), out());
    }

    // ---- chunks ----

    @Test
    void 小文档默认不切片() {
        assertEquals(0, run("chunks", FORM));
        assertTrue(out().contains("切成 1 片"), out());
        assertTrue(out().contains("[含表格]"), out());
    }

    @Test
    void 调小预算之后大表按行拆开每段重复表头() {
        assertEquals(0, run("chunks", FORM, "--chunk-tokens", "300", "--context-tokens", "600", "--show"));
        assertTrue(out().contains("（表格分段 1/"), out());
        assertTrue(out().contains("表头已重复"), out());
        // 每一段都带着表头的第一行
        long headerRepeats = out().lines().filter(l -> l.startsWith("| 姓名 | 林致远 |")).count();
        assertTrue(headerRepeats >= 2, "表头应该在每一段里都出现，现在出现了 " + headerRepeats + " 次");
    }

    @Test
    void 选项的值可以用空格隔开也可以用等号() {
        run("chunks", CONTRACT, "--chunk-tokens", "200", "--context-tokens", "0");
        String spaced = out();
        outBuf.reset();
        run("chunks", CONTRACT, "--chunk-tokens=200", "--context-tokens=0");
        assertEquals(spaced, out(), "两种写法的结果应该相同");
        assertTrue(spaced.contains("切成"), spaced);
        assertTrue(!spaced.contains("切成 1 片"), "预算调小之后应该切成多片：" + spaced);
    }

    @Test
    void 切片的每一行有编号页码和章节() {
        assertEquals(0, run("chunks", CONTRACT, "--chunk-tokens", "200", "--context-tokens", "0"));
        assertTrue(out().lines().anyMatch(l -> l.matches("\\s+c1 \\|\\s+第1页 \\|\\s+\\d+ token \\| .*")), out());
        assertTrue(out().contains("第三条"), "章节名应该出现在切片的概况里：" + out());
    }

    // ---- 写错了的情况 ----

    @Test
    void 不带参数时打印用法() {
        assertEquals(0, run());
        assertTrue(out().contains("用法："), out());
        assertTrue(out().contains("chunks"), out());
    }

    @Test
    void 不认识的命令退出码是2() {
        assertEquals(2, run("inspekt", FORM));
        assertTrue(err().contains("不认识的命令：inspekt"), err());
        assertTrue(err().contains("用法："), "写错了的时候要把用法打出来：" + err());
    }

    @Test
    void 不认识的选项退出码是2() {
        assertEquals(2, run("chunks", FORM, "--chunk-size", "300"));
        assertTrue(err().contains("不认识的选项：--chunk-size"), err());
    }

    @Test
    void 选项后面少了值或者不是整数退出码是2() {
        assertEquals(2, run("chunks", FORM, "--chunk-tokens"));
        assertTrue(err().contains("--chunk-tokens 后面要跟一个值"), err());
        errBuf.reset();
        assertEquals(2, run("chunks", FORM, "--chunk-tokens", "很多"));
        assertTrue(err().contains("后面要跟一个整数"), err());
    }

    @Test
    void 没给文件退出码是2() {
        assertEquals(2, run("chunks"));
        assertTrue(err().contains("要给出至少一个文件或目录"), err());
    }

    @Test
    void 文件不存在退出码是1() {
        assertEquals(1, run("inspect", "no_such_file.json"));
        assertTrue(err().contains("错误：找不到输入"), err());
    }
}
