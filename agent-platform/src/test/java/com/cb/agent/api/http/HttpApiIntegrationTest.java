package com.cb.agent.api.http;

import com.cb.agent.AgentPlatformApplication;
import com.cb.agent.app.extract.ExtractionTaskService;
import com.cb.agent.core.llm.LlmClientProvider;
import com.cb.agent.testsupport.ControlledLlm;
import com.cb.agent.testsupport.PersonnelFormRules;
import com.cb.agent.testsupport.RuleLlm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 阶段五的完整 HTTP 验收：请求经过真实 Tomcat、过滤器、MVC 和应用层，文件存在临时目录。
 *
 * <p>模型使用可暂停的规则假模型，不连接真实模型。固定一个工作线程和两个排队名额，便于稳定地验证异步、
 * 队列满与取消；服务只启动一次，每个用例结束后删除自己的任务，避免依赖测试执行顺序。
 */
class HttpApiIntegrationTest {

    private static final String SKILL = "personnel-appointment";
    private static final String TOKEN = "http-integration-token";
    private static final int MAX_FILE_BYTES = 262_144;
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final Path FORM = Path.of("samples/personnel_form_1.6.json");
    private static final Path CONTRACT = Path.of("src/test/resources/fixtures/paddle/sample_contract");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ControlledLlm LLM = new ControlledLlm(new RuleLlm(PersonnelFormRules.rules()));
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    static Path store;

    private static ConfigurableApplicationContext context;
    private static ExtractionTaskService tasks;
    private static int port;

    @BeforeAll
    static void start() {
        SpringApplication app = new SpringApplication(AgentPlatformApplication.class, FakeModel.class);
        context = app.run("serve", "--server.address=127.0.0.1", "--server.port=0",
                "--agent.store.dir=" + store, "--agent.api.token=" + TOKEN,
                "--agent.skills.dir=../.claude/skills", "--agent.tasks.workers=1",
                "--agent.tasks.queue-capacity=2", "--agent.tasks.retention-days=0",
                "--agent.llm.max-concurrency=1", "--agent.api.max-documents=3", "--agent.api.max-files=10",
                "--agent.api.max-file-bytes=" + MAX_FILE_BYTES, "--agent.api.max-request-bytes=" + MAX_REQUEST_BYTES);
        tasks = context.getBean(ExtractionTaskService.class);
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterEach
    void clean() {
        LLM.release();
        LLM.failWith(null);
        awaitTrue(() -> tasks.queuedDocuments() == 0 && tasks.runningDocuments() == 0, "所有文档结束");
        for (Map<String, Object> task : tasks.list(null, 200)) {
            tasks.delete((String) task.get("id"));
        }
    }

    @AfterAll
    static void stop() {
        LLM.release();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void 鉴权在解析请求前进行_健康检查和指标无需令牌() throws Exception {
        HttpResponse<String> missing = send("GET", "/api/v1/skills", null, null, Map.of(), false);
        error(missing, 401, "unauthorized");
        assertEquals("Bearer", missing.headers().firstValue("WWW-Authenticate").orElseThrow());
        error(send("POST", "/api/v1/extractions", "application/json", "{".getBytes(StandardCharsets.UTF_8),
                Map.of("Authorization", "Bearer wrong"), false), 401, "unauthorized");
        assertEquals(200, send("GET", "/api/v1/skills", null, null,
                Map.of("Authorization", "bearer " + TOKEN), false).statusCode());
        assertEquals(200, send("GET", "/actuator/health", null, null, Map.of(), false).statusCode());
        assertEquals(200, send("GET", "/actuator/metrics", null, null, Map.of(), false).statusCode());
        assertTrue(tasks.list(null, 200).isEmpty());
    }

    @Test
    void skill清单_切片和规则路由不调用模型() throws Exception {
        long before = LLM.entered();
        Map<String, Object> skill = maps(body(get("/api/v1/skills")).get("skills")).getFirst();
        assertEquals(SKILL, skill.get("name"));
        assertEquals(1, skill.get("version"));
        assertEquals(true, skill.get("extractable"));
        assertNotNull(skill.get("match"));

        Map<String, Object> request = new LinkedHashMap<>(documents(form(null)));
        request.put("text", true);
        Map<String, Object> chunked = body(post("/api/v1/chunks", request));
        Map<String, Object> document = maps(chunked.get("documents")).getFirst();
        assertEquals(2, document.get("pageCount"));
        assertEquals(1, document.get("chunkCount"));
        assertTrue(((String) maps(document.get("chunks")).getFirst().get("text")).contains("林致远"));

        Map<String, Object> routing = map(maps(body(post("/api/v1/route", documents(form(null))))
                .get("documents")).getFirst().get("routing"));
        assertEquals(SKILL, routing.get("skill"));
        assertEquals("rule", routing.get("method"));
        assertEquals(0.88, routing.get("score"));
        assertFalse(maps(routing.get("candidates")).isEmpty());
        assertEquals(before, LLM.entered());
        assertTrue(tasks.list(null, 200).isEmpty(), "预览不会建立持久化任务");
    }

    @Test
    void JSON提交立即返回_轮询后取得带文件溯源的结果() throws Exception {
        LLM.hold();
        long entered = LLM.entered();
        HttpResponse<String> submitted = post("/api/v1/extractions", extraction(SKILL, null, form(null)));
        assertEquals(202, submitted.statusCode());
        String id = (String) body(submitted).get("id");
        assertEquals("/api/v1/extractions/" + id, submitted.headers().firstValue("Location").orElseThrow());
        awaitTrue(() -> LLM.entered() > entered, "文档开始调用模型");
        assertEquals("running", body(get("/api/v1/extractions/" + id)).get("status"));
        error(get(resultPath(id, 0)), 409, "conflict");

        LLM.release();
        Map<String, Object> task = awaitTask(id);
        assertEquals("succeeded", task.get("status"));
        assertEquals(Map.of("llmCalls", 4, "promptTokens", 400, "completionTokens", 40), task.get("usage"));
        HttpResponse<String> response = get(resultPath(id, 0));
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        Map<String, Object> result = body(response);
        assertEquals("林致远", map(result.get("data")).get("姓名"));
        assertEquals(22, map(result.get("summary")).get("ok"));
        Map<String, Object> file = maps(result.get("files")).getFirst();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(FORM)));
        assertEquals(sha, file.get("sha256"));
        assertEquals("f_" + sha.substring(0, 16), file.get("id"));
        assertEquals(List.of(1, 2), file.get("pages"));
        Map<String, Object> locator = map(map(map(result.get("fields")).get("姓名")).get("locator"));
        assertEquals(file.get("id"), locator.get("file_id"));
        assertEquals("sha256:" + sha, locator.get("file_hash"));
        assertEquals(result, maps(body(get("/api/v1/extractions/" + id + "?results=true"))
                .get("documents")).getFirst().get("result"));
        assertEquals(response.body(), Files.readString(store.resolve(id).resolve("files/result/0.json")));
    }

    @Test
    void 识别JSON可以直接嵌进请求里_也能指定文档名() throws Exception {
        Map<String, Object> group = Map.of("name", "接口样例", "files", List.of(Map.of("name", "form.json",
                "json", JSON.readValue(Files.readAllBytes(FORM), Object.class))));
        HttpResponse<String> submitted = post("/api/v1/extractions", extraction(SKILL, null, group));
        assertEquals(202, submitted.statusCode());
        String id = (String) body(submitted).get("id");
        assertEquals("succeeded", awaitTask(id).get("status"));
        assertEquals("接口样例", body(get(resultPath(id, 0))).get("document"));
    }

    @Test
    void multipart多文件按文档分组_auto部分成功且未路由文档没有模型调用() throws Exception {
        List<Upload> files = new ArrayList<>();
        files.add(upload(FORM));
        try (Stream<Path> pages = Files.list(CONTRACT)) {
            for (Path page : pages.sorted().toList()) {
                files.add(upload(page));
            }
        }
        HttpResponse<String> submitted = multipart("/api/v1/extractions", Map.of("skill", "auto"), files);
        assertEquals(202, submitted.statusCode());
        String id = (String) body(submitted).get("id");
        Map<String, Object> task = awaitTask(id);
        assertEquals("partial", task.get("status"));
        List<Map<String, Object>> docs = maps(task.get("documents"));
        assertEquals(2, docs.size());
        Map<String, Object> succeeded = docs.stream().filter(d -> d.get("status").equals("succeeded"))
                .findFirst().orElseThrow();
        Map<String, Object> unrouted = docs.stream().filter(d -> d.get("status").equals("unrouted"))
                .findFirst().orElseThrow();
        assertEquals("rule", map(succeeded.get("routing")).get("method"));
        assertEquals(1, maps(succeeded.get("files")).size());
        assertEquals("none", map(unrouted.get("routing")).get("method"));
        assertEquals(3, maps(unrouted.get("files")).size());
        assertEquals(0, map(unrouted.get("usage")).get("llmCalls"));
        error(get(resultPath(id, (Integer) unrouted.get("index"))), 409, "conflict");
        assertEquals(200, get(resultPath(id, (Integer) succeeded.get("index"))).statusCode());
    }

    @Test
    void auto不能确定时返回unrouted与候选() throws Exception {
        long before = LLM.entered();
        Map<String, Object> group = contract();
        HttpResponse<String> preview = post("/api/v1/route", documents(group));
        assertEquals("none", map(maps(body(preview).get("documents")).getFirst().get("routing")).get("method"));
        HttpResponse<String> submitted = post("/api/v1/extractions", extraction("auto", null, group));
        assertEquals(202, submitted.statusCode());
        String id = (String) body(submitted).get("id");
        Map<String, Object> task = awaitTask(id);
        assertEquals("unrouted", task.get("status"));
        assertFalse(maps(map(maps(task.get("documents")).getFirst().get("routing")).get("candidates")).isEmpty());
        assertEquals(before, LLM.entered());
    }

    @Test
    void 幂等键支持请求头_重放回200_内容冲突回409() throws Exception {
        String key = "http-" + UUID.randomUUID();
        Map<String, Object> request = extraction(SKILL, null, form(null));
        HttpResponse<String> first = post("/api/v1/extractions", request, Map.of("Idempotency-Key", key));
        assertEquals(202, first.statusCode());
        String id = (String) body(first).get("id");
        HttpResponse<String> again = post("/api/v1/extractions", request, Map.of("Idempotency-Key", key));
        assertEquals(200, again.statusCode());
        assertEquals(id, body(again).get("id"));
        assertEquals(key, body(again).get("idempotencyKey"));
        error(post("/api/v1/extractions", extraction(SKILL, null, form("不同的文档名")),
                Map.of("Idempotency-Key", key)), 409, "conflict");
        error(post("/api/v1/extractions", extraction(SKILL, "body-key", form(null)),
                Map.of("Idempotency-Key", "header-key")), 400, "bad_request");
        assertEquals(4, map(awaitTask(id).get("usage")).get("llmCalls"));
        assertEquals(1, maps(body(get("/api/v1/extractions?status=succeeded&limit=1")).get("tasks")).size());
    }

    @Test
    void 队列满回503及RetryAfter_取消排队任务释放名额() throws Exception {
        LLM.hold();
        long entered = LLM.entered();
        String running = id(post("/api/v1/extractions", extraction(SKILL, null, form(null))));
        awaitTrue(() -> LLM.entered() > entered, "第一份开始处理");
        String waiting = id(post("/api/v1/extractions", extraction(SKILL, null, form("甲"), form("乙"))));
        assertEquals("queued", body(get("/api/v1/extractions/" + waiting)).get("status"));
        assertEquals(2.0, metric("agent.extraction.queued", ""));
        assertEquals(1.0, metric("agent.extraction.running", ""));
        assertEquals(1.0, metric("agent.llm.inflight", ""));
        HttpResponse<String> rejected = post("/api/v1/extractions", extraction(SKILL, null, form(null)));
        error(rejected, 503, "queue_full");
        assertEquals("30", rejected.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(2, maps(body(get("/api/v1/extractions")).get("tasks")).size());

        assertEquals("canceled", body(postEmpty("/api/v1/extractions/" + waiting + "/cancel")).get("status"));
        assertEquals(0.0, metric("agent.extraction.queued", ""));
        String next = id(post("/api/v1/extractions", extraction(SKILL, null, form("下一份"))));
        assertEquals("queued", body(get("/api/v1/extractions/" + next)).get("status"));
        postEmpty("/api/v1/extractions/" + running + "/cancel");
        assertEquals("canceled", awaitTask(running).get("status"));
        LLM.release();
        assertEquals("succeeded", awaitTask(next).get("status"));
    }

    @Test
    void 取消中断正在处理的文档_结束前不能删除_删除后查不到() throws Exception {
        LLM.hold();
        long entered = LLM.entered();
        String id = id(post("/api/v1/extractions", extraction(SKILL, null, form("甲"), form("乙"))));
        awaitTrue(() -> LLM.entered() > entered, "第一份开始处理");
        error(send("DELETE", "/api/v1/extractions/" + id, null, null, Map.of(), true), 409, "conflict");
        Map<String, Object> canceling = body(postEmpty("/api/v1/extractions/" + id + "/cancel"));
        assertEquals(true, canceling.get("cancelRequested"));
        assertEquals("canceled", maps(canceling.get("documents")).get(1).get("status"));
        assertEquals("canceled", awaitTask(id).get("status"));
        error(get(resultPath(id, 0)), 409, "conflict");
        assertEquals(204, send("DELETE", "/api/v1/extractions/" + id, null, null, Map.of(), true).statusCode());
        assertFalse(Files.exists(store.resolve(id)));
        error(get("/api/v1/extractions/" + id), 404, "not_found");
        error(get(resultPath(id, 0)), 404, "not_found");
        error(send("DELETE", "/api/v1/extractions/" + id, null, null, Map.of(), true), 404, "not_found");
    }

    @Test
    void 请求错误使用统一JSON结构与明确状态码() throws Exception {
        error(send("POST", "/api/v1/extractions", "application/json", "{".getBytes(StandardCharsets.UTF_8),
                Map.of(), true), 400, "bad_request");
        error(post("/api/v1/extractions", List.of()), 400, "bad_request");
        error(post("/api/v1/extractions", Map.of("skill", SKILL)), 400, "bad_request");
        Map<String, Object> forbidden = new LinkedHashMap<>(extraction(SKILL, null, form(null)));
        forbidden.put("baseUrl", "http://example.com");
        error(post("/api/v1/extractions", forbidden), 400, "bad_request");
        Map<String, Object> options = new LinkedHashMap<>(extraction(SKILL, null, form(null)));
        options.put("options", Map.of("topK", 0));
        error(post("/api/v1/extractions", options), 400, "bad_request");
        error(post("/api/v1/extractions", extraction("no-such-skill", null, form(null))), 400, "bad_request");
        error(get("/api/v1/no-such-endpoint"), 404, "not_found");
        error(send("PUT", "/api/v1/skills", null, null, Map.of(), true), 405, "method_not_allowed");
        error(send("POST", "/api/v1/extractions", "text/plain", "text".getBytes(StandardCharsets.UTF_8),
                Map.of(), true), 415, "unsupported_media_type");
        error(get("/api/v1/extractions?limit=0"), 400, "bad_request");
        error(get("/api/v1/extractions?limit=bad"), 400, "bad_request");
        error(get("/api/v1/extractions?status=done"), 400, "bad_request");
        assertTrue(tasks.list(null, 200).isEmpty());
    }

    @Test
    void 上传文件与总大小超限回413_文件个数超限回400() throws Exception {
        error(multipart("/api/v1/extractions", Map.of("skill", SKILL),
                List.of(new Upload("large.json", new byte[MAX_FILE_BYTES + 1]))), 413, "too_large");
        List<Upload> total = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            total.add(new Upload("part" + i + ".json", new byte[MAX_FILE_BYTES]));
        }
        error(multipart("/api/v1/extractions", Map.of("skill", SKILL), total), 413, "too_large");
        List<Upload> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(new Upload("part" + i + ".json", Files.readAllBytes(FORM)));
        }
        error(multipart("/api/v1/extractions", Map.of("skill", SKILL), many), 400, "bad_request");
        assertTrue(tasks.list(null, 200).isEmpty());
    }

    @Test
    void 分块请求回411_已知超大请求在读取正文前回413() throws Exception {
        HttpRequest chunked = HttpRequest.newBuilder(uri("/api/v1/extractions")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
                        "{}".getBytes(StandardCharsets.UTF_8)))).build();
        error(HTTP.send(chunked, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 411, "length_required");

        // 只发送头就能拿到拒绝，证明过滤器在把正文读进内存前检查了 Content-Length。
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            String headers = "POST /api/v1/extractions HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nAuthorization: Bearer " + TOKEN + "\r\nContent-Type: application/json"
                    + "\r\nContent-Length: 3145728\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 413"), response);
            assertTrue(response.contains("too_large"), response);
        }
        assertTrue(tasks.list(null, 200).isEmpty());
    }

    @Test
    void 健康检查含队列和磁盘信息_指标记录文档调用与token() throws Exception {
        double documentsBefore = metricOrZero("agent.extraction.documents", "?tag=skill:" + SKILL + "&tag=status:succeeded");
        double callsBefore = metricOrZero("agent.extraction.llm.calls", "?tag=skill:" + SKILL);
        double promptBefore = metricOrZero("agent.extraction.llm.tokens", "?tag=skill:" + SKILL + "&tag=type:prompt");
        double completionBefore = metricOrZero("agent.extraction.llm.tokens", "?tag=skill:" + SKILL + "&tag=type:completion");
        String id = id(post("/api/v1/extractions", extraction(SKILL, null, form(null))));
        assertEquals("succeeded", awaitTask(id).get("status"));
        // 状态先落库、随后记录指标，因此明确等计量完成，避免两个线程恰好交错导致偶发失败。
        awaitTrue(() -> {
            try {
                return metric("agent.extraction.llm.calls", "?tag=skill:" + SKILL) == callsBefore + 4;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "模型用量已计入指标");
        assertEquals(documentsBefore + 1, metric("agent.extraction.documents", "?tag=skill:" + SKILL + "&tag=status:succeeded"));
        assertEquals(promptBefore + 400, metric("agent.extraction.llm.tokens", "?tag=skill:" + SKILL + "&tag=type:prompt"));
        assertEquals(completionBefore + 40, metric("agent.extraction.llm.tokens", "?tag=skill:" + SKILL + "&tag=type:completion"));
        Map<String, Object> health = body(get("/actuator/health"));
        assertEquals("UP", health.get("status"));
        Map<String, Object> detail = map(map(map(health.get("components")).get("tasks")).get("details"));
        assertEquals(0, detail.get("queued"));
        assertEquals(0, detail.get("running"));
        assertEquals(1, detail.get("llmLimit"));
        assertEquals(true, detail.get("storeUsable"));
        assertTrue(((Number) detail.get("storeFreeBytes")).longValue() > 0);
        assertEquals(0.0, metric("agent.llm.inflight", ""));
        assertEquals(0.0, metric("agent.llm.waiting", ""));
        assertTrue(maps(body(get("/actuator/metrics/agent.extraction.duration?tag=skill:" + SKILL
                + "&tag=status:succeeded")).get("measurements")).size() >= 2);
    }

    /** 假模型的装配只在此测试主动加入，不会被其他应用装配测试扫描到。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModel {

        @Bean
        @Primary
        LlmClientProvider integrationLlmClientProvider() {
            return (model, baseUrl) -> LLM;
        }
    }

    /** 一个要上传的文件，名字始终只包含文件名。 */
    private record Upload(String name, byte[] bytes) {
    }

    private static Upload upload(Path file) throws IOException {
        return new Upload(file.getFileName().toString(), Files.readAllBytes(file));
    }

    private static Map<String, Object> form(String name) throws IOException {
        Map<String, Object> group = new LinkedHashMap<>();
        if (name != null) {
            group.put("name", name);
        }
        group.put("files", List.of(file(FORM)));
        return group;
    }

    private static Map<String, Object> contract() throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> pages = Files.list(CONTRACT)) {
            for (Path page : pages.sorted().toList()) {
                files.add(file(page));
            }
        }
        return Map.of("files", files);
    }

    private static Map<String, Object> file(Path file) throws IOException {
        return Map.of("name", file.getFileName().toString(), "content", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    }

    @SafeVarargs
    private static Map<String, Object> documents(Map<String, Object>... groups) {
        return Map.of("documents", List.of(groups));
    }

    @SafeVarargs
    private static Map<String, Object> extraction(String skill, String key, Map<String, Object>... groups) {
        Map<String, Object> request = new LinkedHashMap<>(documents(groups));
        request.put("skill", skill);
        if (key != null) {
            request.put("idempotencyKey", key);
        }
        return request;
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null, Map.of(), true);
    }

    private static HttpResponse<String> postEmpty(String path) throws Exception {
        return send("POST", path, null, null, Map.of(), true);
    }

    private static HttpResponse<String> post(String path, Object body) throws Exception {
        return post(path, body, Map.of());
    }

    private static HttpResponse<String> post(String path, Object body, Map<String, String> headers) throws Exception {
        return send("POST", path, "application/json", JSON.writeValueAsBytes(body), headers, true);
    }

    private static HttpResponse<String> send(String method, String path, String contentType, byte[] body,
                                              Map<String, String> headers, boolean authorized) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (authorized) {
            request.header("Authorization", "Bearer " + TOKEN);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 使用真实 multipart 字节，不借助 MVC 的模拟请求，以覆盖容器的表单解析。 */
    private static HttpResponse<String> multipart(String path, Map<String, String> fields, List<Upload> files) throws Exception {
        String boundary = "agent-integration-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for (Upload file : files) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + file.name() + "\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(file.bytes());
            body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return send("POST", path, "multipart/form-data; boundary=" + boundary, body.toByteArray(), Map.of(), true);
    }

    private static String id(HttpResponse<String> submitted) {
        assertEquals(202, submitted.statusCode(), submitted.body());
        return (String) body(submitted).get("id");
    }

    private static String resultPath(String id, int index) {
        return "/api/v1/extractions/" + id + "/documents/" + index + "/result";
    }

    private static Map<String, Object> awaitTask(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get("/api/v1/extractions/" + id);
            assertEquals(200, response.statusCode(), response.body());
            Map<String, Object> task = body(response);
            if (!List.of("queued", "running").contains(task.get("status"))) {
                return task;
            }
            Thread.sleep(20);
        }
        fail("等了 20 秒任务还没结束：" + id);
        return Map.of();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("等了 20 秒还没等到：" + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待时被中断", e);
            }
        }
    }

    private static double metric(String name, String query) throws Exception {
        HttpResponse<String> response = get("/actuator/metrics/" + name + query);
        assertEquals(200, response.statusCode(), response.body());
        return ((Number) maps(body(response).get("measurements")).getFirst().get("value")).doubleValue();
    }

    private static double metricOrZero(String name, String query) throws Exception {
        HttpResponse<String> response = get("/actuator/metrics/" + name + query);
        if (response.statusCode() == 404) {
            return 0;
        }
        assertEquals(200, response.statusCode(), response.body());
        return ((Number) maps(body(response).get("measurements")).getFirst().get("value")).doubleValue();
    }

    private static void error(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        Map<String, Object> outer = body(response);
        assertEquals(List.of("error"), new ArrayList<>(outer.keySet()));
        Map<String, Object> error = map(outer.get("error"));
        assertEquals(code, error.get("code"));
        assertTrue(error.get("message") instanceof String message && !message.isBlank(), response.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpResponse<String> response) {
        return JSON.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
