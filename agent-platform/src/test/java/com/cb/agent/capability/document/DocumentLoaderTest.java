package com.cb.agent.capability.document;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.BlockKind;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读取入口的测试。
 *
 * <p>前两个测试是把教程（docs/01-read-paddleocr-vl.md 第 7 步）里的代码原样执行一遍，
 * 用的也是教程里的那份示例数据。教程里的代码改了，这里要跟着改；这里挂了，说明教程已经不对了。
 */
class DocumentLoaderTest {

    /** 项目自带的示例：一份虚构的两页干部任免审批表，结构照着真实的 1.6 服务返回。 */
    private static final Path SAMPLE = Path.of("samples", "personnel_form_1.6.json");

    @Test
    void 教程里读文件的写法() {
        DocumentLoader loader = new DocumentLoader(List.of(new PaddleOcrVlJsonReader()));
        List<Document> docs = loader.load(SAMPLE);

        Document doc = docs.get(0);
        assertEquals("干部任免审批表", doc.title());
        assertEquals(2, doc.pageCount());
        assertEquals(3, doc.blocks().size());
        Block table = doc.blocks().get(1);
        assertEquals(BlockKind.TABLE, table.kind());
        assertEquals("1-2", table.pages());
        assertEquals(2, table.regions().size());
        assertTrue(table.text().startsWith("| 姓名 | 林致远 |"), table.text());
        // 教程里写的那几个数：19 行，第 1 页 10 行，第 2 页 9 行
        assertEquals(19, table.table().rows().size());
        long onPage1 = java.util.stream.IntStream.range(0, 19).filter(i -> table.table().rowSource(i).page() == 1).count();
        assertEquals(10, onPage1);
    }

    @Test
    void 教程里读内存中结果的写法() {
        JsonNode result = Golden.read(SAMPLE);
        Document doc = new PaddleOcrVlJsonReader().fromResult(result, "文档名", "来源说明");

        assertEquals("文档名", doc.name());
        assertEquals("来源说明", doc.source());
        assertEquals("干部任免审批表", doc.title());
    }

    @Test
    void 路径不存在时报错() {
        DocumentLoader loader = new DocumentLoader(List.of(new PaddleOcrVlJsonReader()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loader.load(Path.of("no_such_file.json")));
        assertTrue(e.getMessage().contains("找不到输入"), e.getMessage());
    }

    @Test
    void 没有读取器能读时报错并列出支持哪些(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("扫描件.pdf");
        Files.writeString(pdf, "假装是 PDF", StandardCharsets.UTF_8);
        DocumentLoader loader = new DocumentLoader(List.of(new PaddleOcrVlJsonReader()));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> loader.load(pdf));
        assertTrue(e.getMessage().contains("没有能读这个输入的读取器"), e.getMessage());
        assertTrue(e.getMessage().contains(PaddleOcrVlJsonReader.NAME), "要告诉使用者现在支持什么：" + e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }
}
