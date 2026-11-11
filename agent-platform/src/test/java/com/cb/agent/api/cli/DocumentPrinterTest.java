package com.cb.agent.api.cli;

import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code inspect} 命令打印出来的内容。
 *
 * <p>这份输出是给人看的，测试只检查关键信息在不在：页数、块数、每个块的页码和类别、
 * 跨页表格每一页各有几行。不检查排版的细节，免得改一个空格测试就挂。
 */
class DocumentPrinterTest {

    private static String print(Document doc, boolean fullText) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DocumentPrinter.print(new PrintStream(buf, true, StandardCharsets.UTF_8), doc, fullText);
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** 两页，每页单独调一次服务的结果；两页的表格被拼成了一张。 */
    private static Document twoPageDocument() {
        return new PaddleOcrVlJsonReader().fromResult(
                Golden.read(Golden.FIXTURES.resolve("paddle").resolve("response_list.json")), "审批表", "test");
    }

    @Test
    void 打印出文档的概况和每个块() {
        String out = print(twoPageDocument(), false);

        assertTrue(out.contains("# 审批表：2 页，3 个版面块"), out);
        assertTrue(out.contains("读取器：paddle-ocr-vl-json"), out);
        assertTrue(out.contains("标题：逐页调用的结果"), out);
        assertTrue(out.contains("标题（文档标题）"), out);
        assertTrue(out.contains("类别 vision_footnote"), out);
        assertTrue(out.contains("框 [104, 257, 1083, 1453]"), out);
    }

    @Test
    void 跨页的表格打印出每一页各有几行和各页的位置() {
        String out = print(twoPageDocument(), false);

        assertTrue(out.contains("第1-2页"), out);
        assertTrue(out.contains("4 行 × 2 列"), out);
        assertTrue(out.contains("第1页 2 行，第2页 2 行"), out);
        assertTrue(out.contains("各页的位置"), out);
    }

    @Test
    void 默认只打印开头一小段加上show才打印全文() {
        Document doc = twoPageDocument();
        String brief = print(doc, false);
        String full = print(doc, true);

        // 不显示全文时，整张表被压成一行
        assertFalse(brief.contains("\n      | 民族 | 汉族 |"), brief);
        // 显示全文时，表格的每一行各占一行
        assertTrue(full.contains("\n      | 民族 | 汉族 |"), full);
    }
}
