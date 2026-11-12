package com.cb.agent.capability.document.chunk;

import com.cb.agent.capability.document.index.Bm25Index;
import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片和检索的差分测试：同一份文档、同样的设置，切出来的每一片都要和 Python 版相同，检索的得分和排序也要相同。
 *
 * <p>每份用例文档在四种设置下各切一遍：默认的、中等的、小的、很小的。很小的那种会把大表按行拆开、
 * 把长段落按句子拆开，这两条路径在默认设置下走不到。
 */
class ChunkerGoldenTest {

    private static final JsonNode GOLDEN = Golden.read(Golden.GOLDEN.resolve("chunks.json"));
    private static final Path FIXTURES = Golden.FIXTURES.resolve("paddle");

    static Stream<String> cases() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : GOLDEN.properties()) {
            names.add(e.getKey());
        }
        assertTrue(names.size() >= 10, "用例太少，可能没有运行 tools/make_golden.py");
        return names.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 切片和检索的结果和Python版相同(String name) {
        Document doc = read(name);
        for (JsonNode run : GOLDEN.get(name).values()) {
            int maxTokens = run.get("max_tokens").intValue();
            int wholeUnder = run.get("whole_under").intValue();
            String setting = name + "（每片上限 " + maxTokens + "，不超过 " + wholeUnder + " 不切）";

            List<Chunk> chunks = Chunker.chunk(doc, maxTokens, wholeUnder);
            JsonNode expected = run.get("chunks");
            assertEquals(expected.size(), chunks.size(), setting + "：切出的片数不一致");
            for (int i = 0; i < chunks.size(); i++) {
                assertSameChunk(expected.get(i), chunks.get(i), setting + " 第 " + i + " 片的 ");
            }
            assertSameRanking(run.get("bm25"), new Bm25Index(chunks), setting);
        }
    }

    private static Document read(String name) {
        PaddleOcrVlJsonReader reader = new PaddleOcrVlJsonReader();
        if (name.equals("sample_contract")) {
            return reader.read(FIXTURES.resolve("sample_contract")).get(0);
        }
        return reader.fromResult(Golden.read(FIXTURES.resolve(name + ".json")), name, "fixture");
    }

    private static void assertSameChunk(JsonNode e, Chunk c, String where) {
        assertEquals(e.get("index").intValue(), c.index(), where + "序号不一致");
        assertEquals(e.get("id").stringValue(), c.id(), where + "编号不一致");
        assertEquals(e.get("text").stringValue(), c.text(), where + "文字不一致");
        assertEquals(e.get("path"), Golden.tree(c.path()), where + "章节路径不一致");
        assertEquals(e.get("pages").stringValue(), c.pages(), where + "页码不一致");
        assertEquals(e.get("section").stringValue(), c.section(), where + "章节不一致");
        assertEquals(e.get("tokens").intValue(), c.tokens(), where + "token 数不一致");
        assertEquals(e.get("has_table").booleanValue(), c.hasTable(), where + "有没有表格不一致");
        assertEquals(e.get("headings"), Golden.tree(c.headings()), where + "标题不一致");
        assertEquals(e.get("block_ids"), Golden.tree(c.blocks().stream().map(Block::id).toList()), where + "包含的块不一致");
        assertEquals(e.get("render").stringValue(), c.render(), where + "给模型看的形式不一致");
    }

    private static void assertSameRanking(JsonNode expected, Bm25Index index, String setting) {
        for (JsonNode q : expected.values()) {
            String query = q.get("query").stringValue();
            String where = setting + "，检索词 [" + query + "]：";
            double[] scores = index.scores(query);
            JsonNode expectedScores = q.get("scores");
            assertEquals(expectedScores.size(), scores.length, where + "得分的个数不一致");
            boolean ambiguous = false; // 有没有两片的得分几乎相同却又不完全相同
            for (int i = 0; i < scores.length; i++) {
                // 浮点数累加的顺序两边不同，最后几位可能有出入，所以留一点余量
                assertEquals(expectedScores.get(i).doubleValue(), scores[i], 1e-9, where + "第 " + i + " 片的得分不一致");
                for (int j = 0; j < i; j++) {
                    double gap = Math.abs(scores[i] - scores[j]);
                    ambiguous |= gap > 0 && gap < 1e-9;
                }
            }
            if (!ambiguous) {
                assertEquals(q.get("rank"), Golden.tree(index.rank(query)), where + "排序不一致");
            }
        }
    }
}
