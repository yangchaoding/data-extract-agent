package com.cb.agent.capability.document.reader.paddle;

import com.cb.agent.capability.document.DocumentDump;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读取器的差分测试：同一份识别结果，Java 版读出的文档必须和 Python 版逐项相同。
 *
 * <p>输入在 {@code fixtures/paddle} 下，Python 版读出的结果在 {@code golden/paddle} 下，
 * 两者都由 {@code tools/make_golden.py} 生成。每个用例针对读取时的一种处理：标题层级、页眉去重、
 * 跨页表格、各种版面类别、各种外层包装。
 *
 * <p>比较的是 Python 版有的那些字段。Java 版新增的字段（每一行的页码、相对比例的位置、读取报告）
 * Python 版没有，在 {@link PaddleOcrVlJsonReaderTest} 里单独测。
 */
class PaddleReaderGoldenTest {

    private static final Path FIXTURES = Golden.FIXTURES.resolve("paddle");
    private static final Path GOLDEN = Golden.GOLDEN.resolve("paddle");

    private final PaddleOcrVlJsonReader reader = new PaddleOcrVlJsonReader();

    /** 所有的用例名：fixtures/paddle 下每个 JSON 文件是一个用例。 */
    static Stream<String> cases() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            List<String> names = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .sorted()
                    .toList();
            assertTrue(names.size() >= 10, "用例太少，可能没有运行 tools/make_golden.py");
            return names.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 读出的文档和Python版逐项相同(String name) {
        JsonNode data = Golden.read(FIXTURES.resolve(name + ".json"));
        Document doc = reader.fromResult(data, name, "fixture");
        assertSameAsPython(Golden.read(GOLDEN.resolve(name + ".json")), doc, name);
    }

    @Test
    void 按目录读每页一个文件的合同样例() {
        // 三个文件是同一份合同的三页，靠每页里的 input_path 归成一份文档
        List<Document> docs = reader.read(FIXTURES.resolve("sample_contract"));
        JsonNode expected = Golden.read(GOLDEN.resolve("sample_contract.json"));
        assertEquals(expected.size(), docs.size(), "读出的文档份数不一致");
        for (int i = 0; i < docs.size(); i++) {
            assertSameAsPython(expected.get(i), docs.get(i), "sample_contract");
        }
    }

    /** 逐项比较。哪一个块的哪一个字段不一样，失败信息里会说清楚。 */
    private static void assertSameAsPython(JsonNode expected, Document doc, String label) {
        JsonNode actual = Golden.tree(DocumentDump.toMap(doc));
        for (String field : List.of("name", "title", "page_count")) {
            assertEquals(expected.get(field), actual.get(field), label + "：文档的 " + field + " 不一致");
        }
        JsonNode expectedBlocks = expected.get("blocks");
        JsonNode actualBlocks = actual.get("blocks");
        assertEquals(expectedBlocks.size(), actualBlocks.size(), label + "：版面块的个数不一致");
        for (int i = 0; i < expectedBlocks.size(); i++) {
            JsonNode e = expectedBlocks.get(i);
            JsonNode a = actualBlocks.get(i);
            String where = label + "：第 " + i + " 个块（" + e.get("label").stringValue() + "）的 ";
            for (Map.Entry<String, JsonNode> field : e.properties()) {
                if (field.getKey().equals("table")) {
                    continue; // 表格下面单独比
                }
                assertEquals(field.getValue(), a.get(field.getKey()), where + field.getKey() + " 不一致");
            }
            JsonNode et = e.get("table");
            JsonNode at = a.get("table");
            assertEquals(et.isNull(), at.isNull(), where + "有没有表格不一致");
            if (!et.isNull()) {
                assertNotNull(at);
                for (String field : List.of("rows", "header_rows", "explicit_header")) {
                    assertEquals(et.get(field), at.get(field), where + "表格的 " + field + " 不一致");
                }
            }
        }
    }
}
