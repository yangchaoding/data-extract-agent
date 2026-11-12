package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.chunk.Chunker;
import com.cb.agent.capability.document.index.Bm25Index;
import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.text.SourceText;
import com.cb.agent.capability.document.text.TextMatch;
import com.cb.agent.capability.extract.JsonReply;
import com.cb.agent.capability.extract.format.PyFormat;
import com.cb.agent.capability.extract.prompt.Prompts;
import com.cb.agent.capability.extract.schema.FieldSpec;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.capability.extract.value.ValueReader;
import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.OutputTruncatedException;
import com.cb.agent.core.llm.Usage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 抽取引擎：定位 → 抽取 → 核对；核对不过就带着问题重问，找不到就换一批片段再找。
 * 对应 Python 版 {@code agent.py} 的 {@code ExtractionAgent}。
 *
 * <p>一份文档的处理过程：
 * <ol>
 *   <li>切片，建关键词索引；片段多的时候再让模型按目录给每个字段挑片段（定位）；</li>
 *   <li>每个字段取排在最前面的几个片段，候选片段合在一起不超预算的字段放进同一次调用（抽取）；</li>
 *   <li>模型给出的每个值都要带原文证据。程序核对证据确实是片段里的原文、值确实能从证据里读出来（核对）；</li>
 *   <li>没通过核对的字段，带着具体问题重问；没找到的字段，换下一批片段再找；</li>
 *   <li>列表字段顺着找到的位置向相邻片段延伸，直到不再有新的条目。</li>
 * </ol>
 *
 * <p>模型只负责读和判断，核对由程序做。所以结果里 {@code ok} 的意思是"值确实出自引用的那段原文"。
 *
 * <p>这个类没有可变的状态，可以被多个线程同时使用，每次 {@link #run} 互不影响。
 */
public final class ExtractionEngine {

    private final LlmClient llm;
    private final EngineOptions options;
    private final BatchRunner runner;

    public ExtractionEngine(LlmClient llm, EngineOptions options) {
        this(llm, options, new BatchRunner(options.workers()));
    }

    /**
     * @param runner 执行模型调用的并发控制。几份文档共用一个时，同时进行的调用数合在一起受它限制
     */
    public ExtractionEngine(LlmClient llm, EngineOptions options, BatchRunner runner) {
        this.llm = Objects.requireNonNull(llm);
        this.options = Objects.requireNonNull(options);
        this.runner = Objects.requireNonNull(runner);
    }

    /**
     * 按字段定义从一份文档里抽取数据。
     *
     * @throws com.cb.agent.core.llm.LlmCallException 模型调用失败。已经完成的部分不保留
     */
    public ExtractionResult run(Document doc, Schema schema) {
        Usage before = llm.usage();
        List<Chunk> chunks = Chunker.chunk(doc, options.effectiveChunkTokens(), options.contextTokens());
        Map<String, FieldState> states = states(doc, schema, chunks);
        Map<Integer, List<Integer>> siblings = siblings(chunks);
        for (int round = 1; round <= options.maxRounds(); round++) {
            Map<String, List<Integer>> wants = new LinkedHashMap<>();
            for (Map.Entry<String, FieldState> entry : states.entrySet()) {
                FieldState state = entry.getValue();
                if (state.done()) {
                    continue;
                }
                List<Integer> picked = state.take(siblings);
                if (picked.isEmpty()) {
                    state.markDone(); // 所有片段都看过了
                } else {
                    wants.put(entry.getKey(), picked);
                    state.setRounds(round);
                }
            }
            if (wants.isEmpty()) {
                break;
            }
            List<Batch> batches = BatchPlanner.plan(wants, chunks, options);
            List<CallOutput> outputs = runner.map(batch -> extract(doc, schema, fieldsOf(schema, batch),
                    chunksOf(chunks, batch)), batches);
            for (int i = 0; i < batches.size(); i++) {
                Batch batch = batches.get(i);
                CallOutput output = outputs.get(i);
                for (String name : batch.fields()) {
                    FieldState state = states.get(name);
                    state.add(output.items(name), batch.chunks());
                    if (output.unparsed().contains(name)) {
                        state.addNote("第 " + round + " 轮：模型的回复不是合法的 JSON");
                    }
                    if (output.truncated().contains(name)) {
                        state.addNote("第 " + round + " 轮：模型的输出被截断，结果可能不全（调大 EXTRACT_LLM_MAX_TOKENS）");
                    }
                }
            }
            for (String name : wants.keySet()) {
                states.get(name).settle(chunks.size());
            }
        }
        for (FieldState state : states.values()) {
            if (state.isList() && !state.items().isEmpty() && !state.done()) {
                state.addNote("到最大轮数时还在向相邻片段延伸，列表可能不完整（调大 max_rounds，或把字段设为 exhaustive）");
            }
        }
        Map<String, FieldResult> fields = new LinkedHashMap<>();
        states.forEach((name, state) -> fields.put(name, state.result(chunks.size())));
        return new ExtractionResult(doc.name(), schema.name(), fields, chunks.size(), llm.usage().minus(before));
    }

    private static List<FieldSpec> fieldsOf(Schema schema, Batch batch) {
        List<FieldSpec> out = new ArrayList<>();
        for (String name : batch.fields()) {
            out.add(schema.field(name));
        }
        return out;
    }

    /** 一次调用给模型看的片段，按文档顺序。 */
    private static List<Chunk> chunksOf(List<Chunk> chunks, Batch batch) {
        List<Chunk> out = new ArrayList<>();
        for (int i : new TreeSet<>(batch.chunks())) {
            out.add(chunks.get(i));
        }
        return out;
    }

    // -- 定位 ---------------------------------------------------------------

    /**
     * 给每个字段排出片段队列。
     *
     * <p>队列里永远是全部片段，只是顺序不同：模型挑的和关键词检索排在前面的交替着放在最前，其余的跟在后面。
     * 所以定位是"排序"不是"过滤"，多找几轮总能看到全文。
     */
    private Map<String, FieldState> states(Document doc, Schema schema, List<Chunk> chunks) {
        Bm25Index index = new Bm25Index(chunks);
        Map<String, List<Integer>> routed = Map.of();
        if (options.route() && chunks.size() > options.topK()
                && !schema.fields().stream().allMatch(FieldSpec::exhaustive)) {
            routed = route(doc, schema, chunks);
        }
        Map<String, FieldState> states = new LinkedHashMap<>();
        for (FieldSpec f : schema.fields()) {
            List<Integer> queue;
            if (f.exhaustive()) {
                queue = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    queue.add(i); // 通读全文：按文档顺序
                }
            } else {
                queue = interleave(routed.getOrDefault(f.name(), List.of()), index.rank(f.query()));
            }
            states.put(f.name(), new FieldState(f, queue, options.topK()));
        }
        return states;
    }

    /**
     * 让模型看一眼片段目录，给每个字段挑片段。
     *
     * <p>定位只是辅助：目录太长、回复不成形、输出被截断，都返回空，这时只靠关键词检索。
     */
    private Map<String, List<Integer>> route(Document doc, Schema schema, List<Chunk> chunks) {
        List<FieldSpec> fields = schema.fields().stream().filter(f -> !f.exhaustive()).toList();
        String prompt = Prompts.routing(doc, fields, chunks, options.topK(), options.routeTokens(),
                schema.description());
        if (prompt == null) {
            return Map.of();
        }
        Map<String, Object> data;
        try {
            data = JsonReply.parse(llm.complete(Prompts.ROUTE_SYSTEM, prompt));
        } catch (IllegalArgumentException | OutputTruncatedException e) {
            return Map.of();
        }
        Map<String, Integer> byId = new HashMap<>();
        for (Chunk c : chunks) {
            byId.put(c.id(), c.index());
        }
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (FieldSpec f : fields) {
            Object ids = data.get(f.name());
            if (ids instanceof String one) {
                ids = List.of(one);
            }
            if (ids instanceof List<?> list) {
                Set<Integer> picked = new LinkedHashSet<>(); // 模型给了不认识的编号就丢掉，重复的只留一个
                for (Object id : list) {
                    Integer at = byId.get(PyFormat.str(id));
                    if (at != null) {
                        picked.add(at);
                    }
                }
                out.put(f.name(), new ArrayList<>(picked));
            }
        }
        return out;
    }

    /** 两个列表交替着合并，去掉重复的。模型挑的片段和关键词检索到的片段都能较早被看到。 */
    static List<Integer> interleave(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size() && seen.add(a.get(i))) {
                out.add(a.get(i));
            }
            if (i < b.size() && seen.add(b.get(i))) {
                out.add(b.get(i));
            }
        }
        return out;
    }

    /**
     * 共用同一个版面块的片段：大表、长段落拆出来的各个分段。
     *
     * @return 片段序号 → 和它共用版面块的其他片段；没有这样的片段时不在表里
     */
    static Map<Integer, List<Integer>> siblings(List<Chunk> chunks) {
        Map<Integer, List<Integer>> byBlock = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            for (Block block : c.blocks()) {
                byBlock.computeIfAbsent(block.id(), k -> new ArrayList<>()).add(c.index());
            }
        }
        Map<Integer, List<Integer>> out = new LinkedHashMap<>();
        for (List<Integer> group : byBlock.values()) {
            for (int i : group) {
                List<Integer> mates = out.computeIfAbsent(i, k -> new ArrayList<>());
                for (int j : group) {
                    if (j != i && !mates.contains(j)) {
                        mates.add(j);
                    }
                }
            }
        }
        out.values().removeIf(List::isEmpty);
        return out;
    }

    // -- 抽取与核对 ---------------------------------------------------------

    /**
     * 一次抽取。输出被截断时把任务对半拆开重做：先拆字段，只剩一个字段时拆片段。
     *
     * <p>拆到只剩一个字段、一个片段还是放不下，就记下来，结果里会提示调大输出长度的上限。
     */
    private CallOutput extract(Document doc, Schema schema, List<FieldSpec> fields, List<Chunk> chunks) {
        try {
            return extractOnce(doc, schema, fields, chunks);
        } catch (OutputTruncatedException e) {
            CallOutput out = new CallOutput();
            if (fields.size() > 1) {
                int mid = fields.size() / 2;
                out.merge(extract(doc, schema, fields.subList(0, mid), chunks));
                out.merge(extract(doc, schema, fields.subList(mid, fields.size()), chunks));
            } else if (chunks.size() > 1) {
                int mid = chunks.size() / 2;
                out.merge(extract(doc, schema, fields, chunks.subList(0, mid)));
                out.merge(extract(doc, schema, fields, chunks.subList(mid, chunks.size())));
            } else {
                return CallOutput.truncated(fields.get(0).name());
            }
            return out;
        }
    }

    /**
     * 调一次模型，读出回复并核对；有没通过核对的值，就带着具体的问题重问。
     *
     * @throws OutputTruncatedException 模型的输出被截断了。由 {@link #extract} 拆开重做
     */
    private CallOutput extractOnce(Document doc, Schema schema, List<FieldSpec> fields, List<Chunk> chunks) {
        Map<String, Chunk> shown = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            shown.put(c.id(), c);
        }
        CallOutput out = new CallOutput();
        List<FieldSpec> pending = fields;
        String feedback = "";
        for (int attempt = 0; attempt <= options.retries(); attempt++) {
            String prompt = Prompts.extraction(doc, pending, chunks, feedback, schema.description());
            Map<String, Object> data;
            try {
                data = JsonReply.parse(llm.complete(Prompts.SYSTEM, prompt));
            } catch (IllegalArgumentException e) {
                out.setUnparsed(pending.stream().map(FieldSpec::name).toList());
                feedback = "- 上一次的回复不是合法的 JSON 对象。只输出一个 JSON 对象，不要有其他文字。";
                continue;
            }
            out.setUnparsed(List.of());
            data = unwrap(data, pending);
            List<FieldSpec> again = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (FieldSpec f : pending) {
                List<Item> items = read(f, data.get(f.name()), shown);
                if (f.isList()) {
                    items = mergeRetry(out.items(f.name()), items);
                }
                out.items().put(f.name(), items);
                boolean bad = false;
                for (int n = 0; n < items.size(); n++) {
                    Item item = items.get(n);
                    if (!item.issues().isEmpty()) {
                        bad = true;
                        notes.add(note(f, n + 1, item));
                    }
                }
                if (bad) {
                    again.add(f); // 只重问有问题的字段
                }
            }
            if (again.isEmpty()) {
                break;
            }
            pending = again;
            feedback = String.join("\n", notes);
        }
        return out;
    }

    /** 模型有时会在外面多包一层，比如 {@code {"result": {...}}}。顶层没有要抽的字段名时，往里找一层。 */
    private static Map<String, Object> unwrap(Map<String, Object> data, List<FieldSpec> pending) {
        for (FieldSpec f : pending) {
            if (data.containsKey(f.name())) {
                return data;
            }
        }
        for (Object inner : data.values()) {
            if (inner instanceof Map<?, ?> map) {
                for (FieldSpec f : pending) {
                    if (map.containsKey(f.name())) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> found = (Map<String, Object>) map;
                        return found;
                    }
                }
            }
        }
        return data;
    }

    /** 重问时告诉模型的一条问题：它上次答了什么，哪里不对。 */
    private static String note(FieldSpec f, int n, Item item) {
        String where = f.isList() ? f.name() + " 的第 " + n + " 项" : f.name();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("value", item.value());
        answer.put("evidence", item.evidence());
        return "- " + where + "：你的回答是 " + PyFormat.json(answer) + "；问题：" + String.join("；", item.issues());
    }

    /**
     * 列表字段重问之后：上一次已经通过核对的条目留着，再加上这次回复里多出来的。
     *
     * <p>模型重答时可能给出完整的列表，也可能只给改正的那几项，两种情况结果都应该是完整的列表。
     */
    private static List<Item> mergeRetry(List<Item> previous, List<Item> answer) {
        List<Item> kept = new ArrayList<>();
        List<Object> unmatched = new ArrayList<>();
        for (Item item : previous) {
            if (item.verified()) {
                kept.add(item);
                unmatched.add(item.value());
            }
        }
        List<Item> out = new ArrayList<>(kept);
        for (Item item : answer) {
            // 这次又给了一遍的，是上一次留下的那一条，不重复收；注意相同的值可以有好几条，所以对上一条划掉一条
            if (!unmatched.remove(item.value())) {
                out.add(item);
            }
        }
        return out;
    }

    /** 读出模型对一个字段的回答。 */
    private List<Item> read(FieldSpec f, Object raw, Map<String, Chunk> shown) {
        if (!f.isList()) {
            if (raw instanceof List<?> list) { // 单值字段却给了列表：取第一个
                raw = list.isEmpty() ? null : list.get(0);
            }
            Item item = item(f, raw, shown);
            return item == null ? new ArrayList<>() : new ArrayList<>(List.of(item));
        }
        if (raw instanceof Map<?, ?> map) {
            raw = map.get("items") instanceof List<?> inner ? inner : List.of(raw);
        }
        List<Item> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object one : list) {
                Item item = item(f, one, shown);
                if (item != null) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /**
     * 读出模型给的一个值并核对。
     *
     * @return 值和它的出处；模型表示没找到时返回 null
     */
    private Item item(FieldSpec f, Object raw, Map<String, Chunk> shown) {
        Object value = raw;
        String evidence = "";
        Chunk claimed = null;
        if (raw instanceof Map<?, ?> map) {
            // 模型有时不包 value，直接把子字段平铺在这一项里
            boolean flat = !f.fields().isEmpty() && !map.containsKey("value")
                    && f.fields().stream().anyMatch(sub -> map.containsKey(sub.name()));
            if (flat || map.containsKey("value") || map.containsKey("evidence")) {
                value = flat ? raw : map.get("value");
                evidence = quoteOf(map.get("evidence"));
                Object id = map.get("chunk");
                claimed = shown.get(falsy(id) ? "" : PyFormat.str(id));
            }
        }
        if (isEmpty(value)) {
            return null;
        }
        ValueReader.Located located = ValueReader.locateEvidence(evidence, claimed, new ArrayList<>(shown.values()));
        Chunk chunk = located.chunk();
        List<String> issues = located.issues();
        Item item = new Item(value, evidence, chunk, locateBlock(chunk, evidence), issues);
        item.setLocator(Provenance.locate(chunk, item.block(), evidence));
        // 证据本身不成立时，值只整理类型，不再拿去和它对，免得在错的证据上再报出一堆衍生的问题
        String quote = issues.isEmpty() ? evidence : "";
        if (!f.isList()) {
            ValueReader.Read read = ValueReader.readValue(f, value, quote, chunk, item.block());
            item.setValue(read.value());
            issues.addAll(read.issues());
        } else if (f.fields().isEmpty()) {
            ValueReader.Read read = ValueReader.readValue(FieldSpec.text(f.name(), f.verbatim()), value, quote,
                    chunk, item.block());
            item.setValue(read.value());
            issues.addAll(read.issues());
        } else if (!(value instanceof Map<?, ?> object)) {
            issues.add("这一项应该是一个包含各子字段的对象");
        } else {
            Map<String, Object> row = new LinkedHashMap<>();
            boolean allNull = true;
            for (FieldSpec sub : f.fields()) {
                Object v = object.get(sub.name());
                if (isEmpty(v)) {
                    row.put(sub.name(), null);
                    if (sub.required()) {
                        issues.add("缺少 " + sub.name());
                    }
                    continue;
                }
                ValueReader.Read read = ValueReader.readValue(sub, v, quote, chunk, item.block());
                row.put(sub.name(), read.value());
                allNull &= read.value() == null;
                for (String message : read.issues()) {
                    issues.add(sub.name() + "：" + message);
                }
            }
            if (allNull) {
                return null; // 每个子字段都是空的，等于没有这一项
            }
            item.setValue(row);
        }
        return item;
    }

    /** 模型给的证据：可能是一个字符串，也可能把几段写成了列表。 */
    private static String quoteOf(Object quote) {
        if (falsy(quote)) {
            return "";
        }
        if (quote instanceof List<?> parts) {
            List<String> lines = new ArrayList<>();
            for (Object part : parts) {
                lines.add(PyFormat.str(part));
            }
            return String.join("\n", lines);
        }
        return PyFormat.str(quote);
    }

    /** Python 里会被当成"假"的值：空、零、false。模型没填这一项时就是这些。 */
    private static boolean falsy(Object value) {
        if (value == null || isEmpty(value)) {
            return true;
        }
        if (value instanceof Boolean b) {
            return !b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() == 0;
        }
        return false;
    }

    private static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof String s && s.isEmpty())
                || (value instanceof List<?> list && list.isEmpty())
                || (value instanceof Map<?, ?> map && map.isEmpty());
    }

    /**
     * 证据落在片段里的哪个版面块上。引了几段时以最长的一段为准。
     *
     * @return 版面块；没有定位到片段、证据是空的、或者哪个块都不像时返回 null
     */
    static Block locateBlock(Chunk chunk, String evidence) {
        List<String> parts = TextMatch.quoteParts(evidence);
        if (chunk == null || parts.isEmpty()) {
            return null;
        }
        String part = parts.get(0);
        for (String p : parts) {
            if (TextMatch.matchKey(p).length() > TextMatch.matchKey(part).length()) {
                part = p;
            }
        }
        Block holder = null;
        int holderLength = 0;
        Block closest = null;
        double closestScore = -1;
        for (Block block : chunk.blocks()) {
            SourceText source = new SourceText(block.text());
            if (source.supports(part)) {
                // 几个块里都有这段文字时（页眉里也印着标题），取最短的那个
                if (holder == null || source.key().length() < holderLength) {
                    holder = block;
                    holderLength = source.key().length();
                }
            }
            double score = source.similarity(part);
            if (score > closestScore) {
                closest = block;
                closestScore = score;
            }
        }
        if (holder != null) {
            return holder;
        }
        // 证据跨了两个块，或者带上了片段里的标记（如"[印章]"）：取最接近的块
        return closest != null && closestScore >= 0.5 ? closest : null;
    }
}
