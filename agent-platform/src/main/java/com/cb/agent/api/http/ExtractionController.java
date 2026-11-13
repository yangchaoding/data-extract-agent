package com.cb.agent.api.http;

import com.cb.agent.app.document.InputGroup;
import com.cb.agent.app.document.UploadLimits;
import com.cb.agent.app.extract.ExtractionRequest;
import com.cb.agent.app.extract.ExtractionTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽取任务的 HTTP 接口。对应方案第 13 节。
 *
 * <pre>
 *   POST   /api/v1/extractions                              提交，立刻返回任务（202）
 *   GET    /api/v1/extractions                              列出任务
 *   GET    /api/v1/extractions/{id}                         任务的状态和每份文档的情况
 *   GET    /api/v1/extractions/{id}/documents/{index}/result  一份文档的抽取结果
 *   POST   /api/v1/extractions/{id}/cancel                  取消
 *   DELETE /api/v1/extractions/{id}                         删除（记录、提交上来的文件、结果）
 * </pre>
 *
 * <p>这个类只做三件事：把请求读成应用层认识的样子（{@link ApiRequests}），调用 {@link ExtractionTaskService}，
 * 把结果回出去。出错的情况由 {@link ApiExceptionHandler} 统一回，这里不处理。
 *
 * <p>调用方能指定的只有 skill 的名称、文档、几个引擎参数、幂等键。skill 的目录、模型接口的地址、并发数
 * 都不能由请求指定：前两样会让服务去读任意目录、把文档发到别处，第三样是部署时按模型服务的容量定的。
 */
@RestController
@RequestMapping("/api/v1/extractions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ExtractionController {

    /** 幂等键也可以放在这个请求头里，和放在请求体里的 {@code idempotencyKey} 是一回事。 */
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final Set<String> SUBMIT_KEYS = Set.of("skill", "documents", "options", "idempotencyKey");
    private static final Set<String> SUBMIT_FORM_KEYS = Set.of("skill", "name", "idempotencyKey", "chunkTokens",
            "contextTokens", "topK", "maxRounds", "route");

    /** 列任务时一次最多返回多少个。 */
    private static final int MAX_LIST = 200;

    private final ExtractionTaskService tasks;
    private final UploadLimits limits;

    public ExtractionController(ExtractionTaskService tasks, UploadLimits limits) {
        this.tasks = tasks;
        this.limits = limits;
    }

    /**
     * 提交（JSON）。请求体：
     * <pre>
     *   {"skill": "personnel-appointment",          // 或者 "auto"，按文档内容自动选
     *    "documents": [{"name": "张三", "files": [{"name": "p1.json", "json": {…识别结果…}}]}],
     *    "options": {"topK": 5},                    // 可以不写
     *    "idempotencyKey": "batch-2026-11-13-001"}  // 可以不写
     * </pre>
     *
     * @return 新建的任务回 202；幂等键对上了、返回的是原来那个任务时回 200
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> submit(
            @RequestBody JsonNode body,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String keyHeader) {
        ApiRequests.object(body, SUBMIT_KEYS);
        return submitted(new ExtractionRequest(ApiRequests.text(body, "skill", "请求"), ApiRequests.documents(body, limits),
                ApiRequests.options(body), key(ApiRequests.text(body, "idempotencyKey", "请求"), keyHeader)));
    }

    /**
     * 提交（multipart 表单）。文件用 {@code files} 上传，可以有多个，算一组（相当于命令行里给了一个目录）；
     * 其余的是表单里的文字项：{@code skill}、{@code name}（文档名）、{@code idempotencyKey}，以及几个引擎参数。
     * <pre>
     *   curl -F skill=personnel-appointment -F "files=@form.json" http://127.0.0.1:8080/api/v1/extractions
     * </pre>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> submitForm(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam Map<String, String> form,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String keyHeader) {
        ApiRequests.form(form, SUBMIT_FORM_KEYS);
        List<InputGroup> documents = ApiRequests.documents(files, form.get("name"), limits);
        return submitted(new ExtractionRequest(form.get("skill"), documents, ApiRequests.options(form),
                key(ApiRequests.blankToNull(form.get("idempotencyKey")), keyHeader)));
    }

    private ResponseEntity<Map<String, Object>> submitted(ExtractionRequest request) {
        ExtractionTaskService.Submission submission = tasks.submit(request);
        // 202：已经收下，还没做完。Location 是查这个任务的地址
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .header(HttpHeaders.LOCATION, "/api/v1/extractions/" + submission.task().get("id"))
                .body(submission.task());
    }

    /** 幂等键：请求体（或表单）里的和请求头里的给一个就行；两处都给了时要相同。 */
    private static String key(String fromBody, String fromHeader) {
        String header = ApiRequests.blankToNull(fromHeader);
        if (fromBody != null && header != null && !fromBody.strip().equals(header)) {
            throw new IllegalArgumentException("幂等键给了两个，而且不一样：请求里的 idempotencyKey 是 " + fromBody.strip()
                    + "，请求头 " + IDEMPOTENCY_HEADER + " 是 " + header + "。只给一个");
        }
        return fromBody != null ? fromBody : header;
    }

    /**
     * 列出任务，新提交的在前。每个任务只有概况。
     *
     * @param status 只要这个状态的；不写就都要
     * @param limit  最多返回几个，1 到 {@value #MAX_LIST}
     */
    @GetMapping
    Map<String, Object> list(@RequestParam(value = "status", required = false) String status,
                             @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_LIST) {
            throw new IllegalArgumentException("limit 要在 1 到 " + MAX_LIST + " 之间，现在是 " + limit);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", tasks.list(ApiRequests.blankToNull(status), limit));
        return out;
    }

    /**
     * 一个任务现在的样子。
     *
     * @param results 写 {@code true} 时，已经抽完的文档带上抽取结果（{@code documents[].result}）。
     *                默认不带：轮询状态时用不着，结果又比较大
     */
    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable("id") String id,
                            @RequestParam(value = "results", defaultValue = "false") boolean results) {
        return tasks.get(id, results);
    }

    /**
     * 一份文档的抽取结果。内容和命令行 {@code extract} 写出来的文件相同，另外多了 skill、routing、files 三项。
     *
     * @param index 文档在任务里的序号，从 0 开始（任务的 {@code documents[].index}）
     */
    @GetMapping("/{id}/documents/{index}/result")
    ResponseEntity<byte[]> result(@PathVariable("id") String id, @PathVariable("index") int index) {
        // 结果是存下来的 JSON 文字，原样回出去，不解析了再写一遍：这样拿到的和存储里的一字不差
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(tasks.result(id, index).getBytes(StandardCharsets.UTF_8));
    }

    /** 取消。正在处理的文档要等它停下来，所以回来时任务的状态可能还是 running（{@code cancelRequested} 是 true）。 */
    @PostMapping("/{id}/cancel")
    Map<String, Object> cancel(@PathVariable("id") String id) {
        return tasks.cancel(id);
    }

    /** 删除一个已经结束的任务。删了就找不回来。成功时回 204，没有内容。 */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") String id) {
        tasks.delete(id);
        return ResponseEntity.noContent().build();
    }
}
