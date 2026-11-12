package com.cb.agent.capability.extract;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.chunk.Chunker;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.capability.extract.format.PyFormat;
import com.cb.agent.capability.extract.prompt.Prompts;
import com.cb.agent.capability.extract.schema.FieldSpec;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.capability.extract.schema.SchemaLoader;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段定义、提示词、格式化、回复解析的差分测试：结果都要和 Python 版逐字相同。
 *
 * <p>这几样的共同点是它们决定了模型看到什么、程序怎么读模型的回复。差一个字，
 * 模型的回答就可能不一样，所以用完全相等来比，不留余量。
 */
class SchemaPromptGoldenTest {

    private static final Path SCHEMAS = Golden.FIXTURES.resolve("schemas");
    private static final Path PADDLE = Golden.FIXTURES.resolve("paddle");

    // ---- 按 Python 规则格式化 ----------------------------------------------------------

    @Test
    void repr和json的格式和Python相同() {
        JsonNode cases = Golden.read(Golden.GOLDEN.resolve("pyformat.json"));
        assertTrue(cases.size() >= 25, "基准数据太少");
        for (JsonNode c : cases.values()) {
            Object value = Golden.plain(c.get("value"));
            String label = "值：" + c.get("json").stringValue();
            assertEquals(c.get("repr").stringValue(), PyFormat.repr(value), "repr 不一致。" + label);
            assertEquals(c.get("str").stringValue(), PyFormat.str(value), "str 不一致。" + label);
            assertEquals(c.get("json").stringValue(), PyFormat.json(value), "json 不一致。" + label);
            assertEquals(c.get("json_sorted").stringValue(), PyFormat.jsonSorted(value), "按键排序的 json 不一致。" + label);
            assertEquals(c.get("json_indent2").stringValue(), PyFormat.jsonIndented(value, 2), "带缩进的 json 不一致。" + label);
        }
    }

    // ---- 字段定义 ----------------------------------------------------------------------

    @Test
    void 读出的字段定义和Python版相同() {
        JsonNode schemas = Golden.read(Golden.GOLDEN.resolve("schemas.json")).get("schemas");
        assertEquals(5, schemas.size(), "仓库自带五份字段定义");
        for (JsonNode e : schemas.values()) {
            String file = e.get("file").stringValue();
            Schema schema = SchemaLoader.load(SCHEMAS.resolve(file));
            assertEquals(e.get("name").stringValue(), schema.name(), file + "：名字不一致");
            assertEquals(e.get("description").stringValue(), schema.description(), file + "：说明不一致");
            assertSameFields(e.get("fields"), schema.fields(), file);
        }
    }

    private static void assertSameFields(JsonNode expected, List<FieldSpec> fields, String where) {
        assertEquals(expected.size(), fields.size(), where + "：字段个数不一致");
        for (int i = 0; i < fields.size(); i++) {
            JsonNode e = expected.get(i);
            FieldSpec f = fields.get(i);
            String w = where + " 的字段 " + f.name() + "：";
            assertEquals(e.get("name").stringValue(), f.name(), w + "name");
            assertEquals(e.get("type").stringValue(), f.type(), w + "type");
            assertEquals(e.get("title").stringValue(), f.title(), w + "title");
            assertEquals(e.get("description").stringValue(), f.description(), w + "description");
            assertEquals(e.get("required").booleanValue(), f.required(), w + "required");
            assertEquals(e.get("hints"), Golden.tree(f.hints()), w + "hints");
            assertEquals(e.get("options"), Golden.tree(f.options()), w + "options");
            assertEquals(e.get("unit").stringValue(), f.unit(), w + "unit");
            assertEquals(e.get("verbatim").booleanValue(), f.verbatim(), w + "verbatim");
            assertEquals(Golden.plain(e.get("top_k")), f.topK(), w + "top_k");
            assertEquals(e.get("exhaustive").booleanValue(), f.exhaustive(), w + "exhaustive");
            assertEquals(e.get("query").stringValue(), f.query(), w + "检索词");
            assertEquals(e.get("describe").stringValue(), f.describe(), w + "写进提示词的说明");
            assertSameFields(e.get("fields"), f.fields(), where + "." + f.name());
        }
    }

    @Test
    void 字段定义写错时的报错和Python版相同() {
        JsonNode errors = Golden.read(Golden.GOLDEN.resolve("schemas.json")).get("errors");
        assertTrue(errors.size() >= 10, "基准数据太少");
        for (JsonNode e : errors.values()) {
            Object raw = Golden.plain(e.get("raw"));
            String label = "输入：" + e.get("raw");
            assertTrue(!e.get("error").isNull(), "这些用例都应该报错。" + label);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SchemaLoader.fromMap(raw), "应该报错。" + label);
            assertEquals(e.get("error").stringValue(), ex.getMessage(), "报错的文字不一致。" + label);
        }
    }

    // ---- 提示词 ------------------------------------------------------------------------

    @Test
    void 系统提示词和Python版逐字相同() {
        JsonNode golden = Golden.read(Golden.GOLDEN.resolve("prompts.json"));
        assertEquals(golden.get("system").stringValue(), Prompts.SYSTEM);
        assertEquals(golden.get("route_system").stringValue(), Prompts.ROUTE_SYSTEM);
    }

    @Test
    void 拼出来的抽取提示词和定位提示词和Python版逐字相同() {
        JsonNode cases = Golden.read(Golden.GOLDEN.resolve("prompts.json")).get("cases");
        assertTrue(cases.size() >= 6, "基准数据太少");
        PaddleOcrVlJsonReader reader = new PaddleOcrVlJsonReader();
        int i = 0;
        for (JsonNode c : cases.values()) {
            String docName = c.get("doc").stringValue();
            String label = "第 " + (i++) + " 个用例（" + docName + " + " + c.get("schema").stringValue() + "）";
            Document doc = docName.equals("sample_contract")
                    ? reader.read(PADDLE.resolve("sample_contract")).get(0)
                    : reader.fromResult(Golden.read(PADDLE.resolve(docName + ".json")), docName, "fixture");
            Schema schema = SchemaLoader.load(SCHEMAS.resolve(c.get("schema").stringValue()));
            List<Chunk> chunks = Chunker.chunk(doc, c.get("max_tokens").intValue(), c.get("whole_under").intValue());
            List<FieldSpec> fields = new ArrayList<>();
            for (JsonNode name : c.get("fields").values()) {
                fields.add(schema.field(name.stringValue()));
            }
            List<Chunk> shown = new ArrayList<>();
            for (JsonNode idx : c.get("chunks").values()) {
                shown.add(chunks.get(idx.intValue()));
            }

            assertEquals(c.get("extraction").stringValue(),
                    Prompts.extraction(doc, fields, shown, c.get("feedback").stringValue(), schema.description()),
                    label + "：抽取提示词不一致");

            String routing = Prompts.routing(doc, fields, chunks, c.get("top_k").intValue(),
                    c.get("budget").intValue(), schema.description());
            if (c.get("routing").isNull()) {
                assertNull(routing, label + "：目录放不下时不该有定位提示词");
            } else {
                assertEquals(c.get("routing").stringValue(), routing, label + "：定位提示词不一致");
            }
        }
    }

    // ---- 从模型的回复里取 JSON ----------------------------------------------------------

    @Test
    void 从回复里取出的JSON和Python版相同() {
        JsonNode cases = Golden.read(Golden.GOLDEN.resolve("jsonreply.json"));
        assertTrue(cases.size() >= 12, "基准数据太少");
        for (JsonNode c : cases.values()) {
            String text = c.get("text").stringValue();
            String label = "回复：[" + text + "]";
            if (c.get("ok").booleanValue()) {
                assertEquals(Golden.canon(Golden.plain(c.get("result"))), Golden.canon(JsonReply.parse(text)), label);
            } else {
                assertThrows(IllegalArgumentException.class, () -> JsonReply.parse(text), "应该取不出 JSON。" + label);
            }
        }
    }
}
