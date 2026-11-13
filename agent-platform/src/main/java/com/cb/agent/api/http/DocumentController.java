package com.cb.agent.api.http;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.document.InputGroup;
import com.cb.agent.app.document.UploadLimits;
import com.cb.agent.app.extract.ExtractService;
import com.cb.agent.app.extract.ExtractionRequest;
import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.core.skill.Skill;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不调用模型、当场就有结果的几个接口。对应方案第 13 节。提交任务之前用它们看一看：
 *
 * <pre>
 *   POST /api/v1/chunks   文档会被切成哪几片
 *   POST /api/v1/route    按规则会选中哪个 skill，依据是什么
 *   GET  /api/v1/skills   现在有哪些 skill
 * </pre>
 *
 * <p>前两个收的文档和提交抽取时的写法相同（JSON 里的 {@code documents}，或者表单上传的 {@code files}）。
 * 文档只在这一次请求里用，不存下来。
 *
 * <p>方案里还列了一个 {@code POST /api/v1/skills/reload}，这里没有做，因为用不着：skill 的清单和字段定义
 * 没有缓存，每次用到都是从目录里现读的，改了之后下一个任务就用新的。
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentController {

    private static final Set<String> CHUNK_KEYS = Set.of("documents", "chunkTokens", "contextTokens", "text");
    private static final Set<String> CHUNK_FORM_KEYS = Set.of("name", "chunkTokens", "contextTokens", "text");
    private static final Set<String> ROUTE_KEYS = Set.of("documents");
    private static final Set<String> ROUTE_FORM_KEYS = Set.of("name");

    private final DocumentReadService documents;
    private final ExtractService extraction;
    private final UploadLimits limits;

    public DocumentController(DocumentReadService documents, ExtractService extraction, UploadLimits limits) {
        this.documents = documents;
        this.extraction = extraction;
        this.limits = limits;
    }

    // ---- 切片 ---------------------------------------------------------------------------

    /**
     * 看切片（JSON）。请求体里除了 {@code documents}，还可以写：
     * {@code chunkTokens}（每片的大小上限）、{@code contextTokens}（整篇不超过它时不切）、
     * {@code text}（写 true 时带上每一片的文字，默认不带）。
     */
    @PostMapping(value = "/chunks", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> chunks(@RequestBody JsonNode body) {
        ApiRequests.object(body, CHUNK_KEYS);
        return chunks(ApiRequests.documents(body, limits), ApiRequests.integer(body, "chunkTokens", "请求"),
                ApiRequests.integer(body, "contextTokens", "请求"), ApiRequests.bool(body, "text", "请求"));
    }

    /** 看切片（multipart 表单）。文件用 {@code files} 上传，其余的是文字项。 */
    @PostMapping(value = "/chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> chunksForm(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                   @RequestParam Map<String, String> form) {
        ApiRequests.form(form, CHUNK_FORM_KEYS);
        return chunks(ApiRequests.documents(files, form.get("name"), limits), ApiRequests.integer(form, "chunkTokens"),
                ApiRequests.integer(form, "contextTokens"), ApiRequests.bool(form, "text"));
    }

    private Map<String, Object> chunks(List<InputGroup> groups, Integer chunkTokens, Integer contextTokens,
                                       Boolean withText) {
        limits.check(groups);
        // 没写的用引擎的默认值；写了的由引擎参数自己检查范围，和提交抽取时是同一套规则
        EngineOptions options = new ExtractionRequest.Options(chunkTokens, contextTokens, null, null, null)
                .applyTo(EngineOptions.DEFAULTS);
        boolean text = Boolean.TRUE.equals(withText);
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Document doc : read(groups)) {
            List<Map<String, Object>> chunks = new ArrayList<>();
            // 和抽取引擎切的一样：每片的上限不会比一次调用能放的原文还大
            for (Chunk chunk : documents.chunk(doc, options.effectiveChunkTokens(), options.contextTokens())) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", chunk.id());
                c.put("section", chunk.section());
                c.put("pages", chunk.pages());
                c.put("tokens", chunk.tokens());
                c.put("hasTable", chunk.hasTable());
                if (text) {
                    c.put("text", chunk.text());
                }
                chunks.add(c);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", doc.name());
            d.put("pageCount", doc.pageCount());
            d.put("tokens", doc.tokens());
            d.put("chunkCount", chunks.size());
            d.put("chunks", chunks);
            docs.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunkTokens", options.effectiveChunkTokens());
        out.put("contextTokens", options.contextTokens());
        out.put("documents", docs);
        return out;
    }

    // ---- 选 skill -----------------------------------------------------------------------

    /**
     * 只选 skill，不抽取（JSON）。回来的是每份文档的结论和依据：选中了谁、是哪一级定的、得分、
     * 每个候选命中了标题里的哪个词、正文里的哪几个特征词。提交时把 skill 写成 {@code auto}，用的就是这里的结论。
     */
    @PostMapping(value = "/route", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> route(@RequestBody JsonNode body) {
        ApiRequests.object(body, ROUTE_KEYS);
        return route(ApiRequests.documents(body, limits));
    }

    /** 只选 skill，不抽取（multipart 表单）。 */
    @PostMapping(value = "/route", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> routeForm(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                  @RequestParam Map<String, String> form) {
        ApiRequests.form(form, ROUTE_FORM_KEYS);
        return route(ApiRequests.documents(files, form.get("name"), limits));
    }

    private Map<String, Object> route(List<InputGroup> groups) {
        limits.check(groups);
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Document doc : read(groups)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", doc.name());
            d.put("pageCount", doc.pageCount());
            d.put("title", doc.title());
            d.put("routing", extraction.route(doc).toMap());
            docs.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", docs);
        return out;
    }

    private List<Document> read(List<InputGroup> groups) {
        List<Document> docs = new ArrayList<>();
        for (InputGroup group : groups) {
            docs.addAll(documents.read(group));
        }
        return docs;
    }

    // ---- skill 清单 ---------------------------------------------------------------------

    /**
     * 现有的 skill。{@code extractable} 是 true 的可以写在提交抽取的 {@code skill} 里；
     * {@code match} 是按规则选它的条件，没写条件的 skill 不参加自动选择。
     */
    @GetMapping("/skills")
    Map<String, Object> skills() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Skill skill : extraction.skills()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", skill.name());
            s.put("version", skill.version());
            s.put("title", skill.title());
            s.put("description", skill.description().replaceAll("\\s+", " ").strip());
            s.put("extractable", skill.schema() != null);
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("titleAny", skill.match().titleAny());
            match.put("keywords", skill.match().keywords());
            match.put("minKeywords", skill.match().minKeywords());
            s.put("match", skill.match().isEmpty() ? null : match);
            list.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skills", list);
        return out;
    }
}
