package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.capability.extract.schema.SchemaLoader;
import com.cb.agent.testsupport.Golden;
import com.cb.agent.testsupport.ReplayLlm;
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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 抽取流程的录播差分测试：拿 Python 版跑同一批用例（同样的文档、字段定义、假模型、引擎参数），
 * 把发给模型的每一条提示词、模型的回复、最后的结果都记在 {@code golden/extract.json} 里。
 * 这里用同样的输入重跑一遍，要求：
 *
 * <ol>
 *   <li><b>发给模型的提示词逐字相同</b>——录播模型按提示词原文取回复，对不上就失败并给出差别。
 *       切片、检索排序、成批、提示词拼装，任何一处不一致都会在这里露出来；</li>
 *   <li><b>调用的次数和顺序相同</b>——串行跑的时候还要顺序也一样；</li>
 *   <li><b>结果相同</b>——每个字段的状态、值、证据、问题说明，以及用量统计；</li>
 *   <li>并发跑（workers=4）也要得到同样的结果，因为成批和收敛都不依赖调用的先后。</li>
 * </ol>
 *
 * <p>有一处是 Java 版故意更准的：证据引的是跨页表格的某一行时，出处给的是这一行所在的那一页，
 * 而 Python 版给的是整张表的页码范围。比较之前会把出处的页码和框按 Python 版的口径还原，
 * Java 版更准的那部分在 {@link ProvenanceTest} 里单独验证。
 */
class ExtractionParityTest {

    private static final JsonNode GOLDEN = Golden.read(Golden.GOLDEN.resolve("extract.json"));

    static Stream<String> cases() {
        List<String> names = new ArrayList<>();
        for (JsonNode one : GOLDEN) {
            names.add(one.get("name").stringValue());
        }
        assertTrue(names.size() >= 25, "用例太少，可能没有运行 tools/make_golden.py");
        return names.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 抽取流程和Python版相同(String name) {
        JsonNode one = find(name);
        Document doc = document(one);
        Schema schema = schema(one);

        ReplayLlm serial = replay(one);
        ExtractionResult result = engine(serial, options(one, 1)).run(doc, schema);
        assertEquals(order(one), serial.consumedOrder(), name + "：调用的次数或顺序不一致");
        assertEquals(List.of(), serial.unused(), name + "：Python 版记下的调用没有全部用上");
        assertEquals(Golden.canon(Golden.plain(one.get("result"))), Golden.canon(pythonView(result)),
                name + "：结果不一致");

        // 并发跑：成批和收敛都不依赖调用的先后，结果不该变
        ReplayLlm parallel = replay(one);
        ExtractionResult concurrent = engine(parallel, options(one, 4)).run(doc, schema);
        assertEquals(Golden.canon(pythonView(result)), Golden.canon(pythonView(concurrent)),
                name + "：并发跑的结果和串行跑的不一致");
        assertEquals(List.of(), parallel.unused(), name + "：并发跑时有调用没用上记下的回复");
    }

    private static ExtractionEngine engine(ReplayLlm llm, EngineOptions options) {
        return new ExtractionEngine(llm, options);
    }

    private static ReplayLlm replay(JsonNode one) {
        List<JsonNode> calls = new ArrayList<>();
        one.get("calls").forEach(calls::add);
        return new ReplayLlm(calls);
    }

    private static List<String> order(JsonNode one) {
        List<String> out = new ArrayList<>();
        for (JsonNode call : one.get("calls")) {
            out.add(call.get("kind").stringValue() + "\u0000" + call.get("user").stringValue());
        }
        return out;
    }

    private static Document document(JsonNode one) {
        String name = one.get("doc").stringValue();
        PaddleOcrVlJsonReader reader = new PaddleOcrVlJsonReader();
        if (name.equals("sample_contract")) {
            return reader.read(Golden.FIXTURES.resolve("paddle").resolve("sample_contract")).get(0);
        }
        Path fixture = Golden.FIXTURES.resolve("extract").resolve(name + ".json");
        return reader.fromResult(Golden.read(fixture), name, "fixture");
    }

    private static Schema schema(JsonNode one) {
        JsonNode spec = one.get("schema");
        if (spec.isTextual()) {
            return SchemaLoader.load(Golden.FIXTURES.resolve("schemas").resolve(spec.stringValue()));
        }
        return SchemaLoader.fromMap(Golden.plain(spec));
    }

    private static EngineOptions options(JsonNode one, int workers) {
        JsonNode o = one.get("options");
        return EngineOptions.DEFAULTS
                .chunkTokens(o.get("chunk_tokens").intValue())
                .contextTokens(o.get("context_tokens").intValue())
                .topK(o.get("top_k").intValue())
                .maxRounds(o.get("max_rounds").intValue())
                .retries(o.get("retries").intValue())
                .route(o.get("route").booleanValue())
                .routeTokens(o.get("route_tokens").intValue())
                .maxFieldsPerCall(o.get("max_fields_per_call").intValue())
                .workers(workers);
    }

    private static JsonNode find(String name) {
        for (JsonNode one : GOLDEN) {
            if (one.get("name").stringValue().equals(name)) {
                return one;
            }
        }
        fail("基准文件里没有用例 " + name);
        throw new IllegalStateException();
    }

    /**
     * 把结果还原成 Python 版的口径，好逐字比较。
     *
     * <p>去掉只有 Java 版才有的 {@code locator}；出处的页码和框换回块本身的写法
     * （Python 版不知道表格的每一行来自哪一页）。
     */
    static Map<String, Object> pythonView(ExtractionResult result) {
        Map<String, Object> out = result.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) out.get("fields");
        for (Map.Entry<String, FieldResult> entry : result.fields().entrySet()) {
            FieldResult fieldResult = entry.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) fields.get(entry.getKey());
            if (fieldResult.field().isList()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) map.get("items");
                for (int i = 0; i < fieldResult.items().size(); i++) {
                    pythonView(items.get(i), fieldResult.items().get(i));
                }
            } else if (!fieldResult.items().isEmpty()) {
                pythonView(map, fieldResult.items().get(0));
            }
            if (!fieldResult.alternatives().isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> alternatives = (List<Map<String, Object>>) map.get("alternatives");
                for (int i = 0; i < fieldResult.alternatives().size(); i++) {
                    pythonView(alternatives.get(i), fieldResult.alternatives().get(i));
                }
            }
        }
        return out;
    }

    private static void pythonView(Map<String, Object> map, Item item) {
        map.remove("locator");
        Block block = item.block();
        if (block == null) {
            return; // 没有定位到版面块时，页码和章节取自片段，两边的算法相同
        }
        map.remove("pages");
        map.remove("bbox");
        if (!block.pages().isEmpty()) {
            map.put("pages", block.pages());
        }
        if (block.bbox() != null) {
            map.put("bbox", block.bbox().toList());
        }
    }
}
