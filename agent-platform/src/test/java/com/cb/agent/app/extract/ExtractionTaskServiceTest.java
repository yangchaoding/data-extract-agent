package com.cb.agent.app.extract;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.document.InputGroup;
import com.cb.agent.app.document.UploadLimits;
import com.cb.agent.capability.document.DocumentLoader;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.core.llm.LlmCallException;
import com.cb.agent.core.llm.LlmGate;
import com.cb.agent.infra.store.FileJobStore;
import com.cb.agent.testsupport.ControlledLlm;
import com.cb.agent.testsupport.PersonnelFormRules;
import com.cb.agent.testsupport.RuleLlm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 抽取任务：提交、排队、在后台抽取、查状态、取结果、取消、删除，以及服务重启之后接着做。
 *
 * <p>模型是假的（{@link ControlledLlm} 包着 {@link RuleLlm}：可以让它停住、让它出错），其余都是真的：
 * 读的是示例任免表和测试用的合同，用的是仓库里的 personnel-appointment 这个 skill，任务存在临时目录里。
 */
class ExtractionTaskServiceTest {

    private static final Path FORM = Path.of("samples/personnel_form_1.6.json");
    private static final Path CONTRACT = Path.of("src/test/resources/fixtures/paddle/sample_contract");
    private static final String SKILL = "personnel-appointment";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private final ControlledLlm llm = new ControlledLlm(new RuleLlm(PersonnelFormRules.rules()));
    private final DocumentReadService documents =
            new DocumentReadService(new DocumentLoader(List.of(new PaddleOcrVlJsonReader())));
    private final ExtractService extraction = new ExtractService((model, baseUrl) -> llm, "../.claude/skills");
    /** 计量收到的每一条：skill、状态、模型调用次数。 */
    private final List<String> metered = new CopyOnWriteArrayList<>();
    private final List<ExtractionTaskService> services = new ArrayList<>();

    @AfterEach
    void stop() {
        llm.release();
        services.forEach(ExtractionTaskService::close);
    }

    // ---- 提交和结果 ----------------------------------------------------------------------

    @Test
    void 提交之后在后台抽取_结果里带着skill和文件那一层出处() throws IOException {
        ExtractionTaskService service = service(dir, 2, 10, 0);

        ExtractionTaskService.Submission submission = service.submit(request(SKILL, null, form()));

        assertTrue(submission.created());
        String id = (String) submission.task().get("id");
        assertTrue(id.matches("ex[0-9a-f]{32}"), id);
        assertTrue(List.of("queued", "running", "succeeded").contains(submission.task().get("status")));

        Map<String, Object> task = await(service, id);
        assertEquals("succeeded", task.get("status"));
        assertEquals(Map.of("total", 1, "queued", 0, "running", 0, "succeeded", 1, "failed", 0, "unrouted", 0,
                "canceled", 0), task.get("counts"));
        assertEquals(Map.of("llmCalls", 4L, "promptTokens", 400L, "completionTokens", 40L), task.get("usage"));
        Map<String, Object> doc = docs(task).get(0);
        assertEquals("personnel_form_1.6", doc.get("name"));
        assertEquals(2, doc.get("pageCount"));
        assertEquals(Map.of("name", SKILL, "version", 1), doc.get("skill"));
        assertEquals("explicit", map(doc.get("routing")).get("method"));
        assertEquals(22, map(doc.get("summary")).get("ok"));
        assertEquals(4, map(doc.get("summary")).get("missing"));
        assertNull(doc.get("error"));

        Map<String, Object> result = JSON.readValue(service.result(id, 0), Map.class);
        assertEquals(List.of("document", "schema", "skill", "routing", "files", "data", "fields", "summary"),
                new ArrayList<>(result.keySet()));
        assertEquals("林致远", map(result.get("data")).get("姓名"));
        // 文件那一层：文件的 ID 和哈希由内容算出来，这份文档的两页都出自这一个文件
        byte[] content = Files.readAllBytes(FORM);
        Map<String, Object> file = list(result.get("files")).get(0);
        String sha = (String) file.get("sha256");
        assertEquals(64, sha.length());
        assertEquals("f_" + sha.substring(0, 16), file.get("id"));
        assertEquals("personnel_form_1.6.json", file.get("name"));
        assertEquals(content.length, file.get("bytes"));
        assertEquals(List.of(1, 2), file.get("pages"));
        // 每个值的出处里指明是哪个文件；列表的每一项也有
        Map<String, Object> locator = map(map(map(result.get("fields")).get("姓名")).get("locator"));
        assertEquals(List.of("file_id", "file_hash", "origin", "evidence_regions", "value_regions"),
                new ArrayList<>(locator.keySet()));
        assertEquals(file.get("id"), locator.get("file_id"));
        assertEquals("sha256:" + sha, locator.get("file_hash"));
        Map<String, Object> family = map(map(result.get("fields")).get("家庭成员及主要社会关系"));
        assertEquals(file.get("id"), map(list(family.get("items")).get(0).get("locator")).get("file_id"));

        // 带上结果查任务，结果和单独取的一样
        assertEquals(result, docs(service.get(id, true)).get(0).get("result"));
        // 存储里：提交上来的文件原样存着，还有记录和结果
        Path stored = dir.resolve(id);
        assertTrue(Files.isRegularFile(stored.resolve("record.json")));
        assertArrayEquals(content, Files.readAllBytes(stored.resolve("files/input/0/personnel_form_1.6.json")));
        assertTrue(Files.isRegularFile(stored.resolve("files/result/0.json")));
        assertEquals(List.of(SKILL + " succeeded 4"), metered);
    }

    @Test
    void skill写auto时按规则选_选不出来的那一份不影响别的() {
        ExtractionTaskService service = service(dir, 2, 10, 0);

        String id = id(service.submit(request("auto", null, form(), contract())));
        Map<String, Object> task = await(service, id);

        // 任免表按规则选中了；合同没有对应的 skill
        assertEquals("partial", task.get("status"));
        Map<String, Object> form = docs(task).get(0);
        assertEquals("succeeded", form.get("status"));
        assertEquals("rule", map(form.get("routing")).get("method"));
        assertEquals(SKILL, map(form.get("routing")).get("skill"));
        assertEquals(0.88, map(form.get("routing")).get("score"));
        Map<String, Object> contract = docs(task).get(1);
        assertEquals("sample_contract", contract.get("name"));
        assertEquals("unrouted", contract.get("status"));
        assertEquals("none", map(contract.get("routing")).get("method"));
        assertEquals("没能确定这份文档该用哪个 skill：没有 skill 的 match 条件被满足。在请求里用 skill 直接指定一个",
                contract.get("error"));
        assertNull(contract.get("skill"));
        assertEquals(3, list(contract.get("files")).size(), "合同的三页是三个文件");

        assertEquals("rule", map(JSON.readValue(service.result(id, 0), Map.class).get("routing")).get("method"));
        TaskConflictException e = assertThrows(TaskConflictException.class, () -> service.result(id, 1));
        assertTrue(e.getMessage().startsWith("文档 sample_contract 现在的状态是 unrouted，没有结果"), e.getMessage());
        assertEquals(4, llm.usage().calls(), "没选出 skill 的那一份没有调用模型");
        assertTrue(metered.contains("null unrouted 0"), metered.toString());
    }

    @Test
    void 一组文件读出几份文档时_各份只算自己的文件() throws IOException {
        ExtractionTaskService service = service(dir, 2, 10, 0);
        List<InputGroup.InputFile> files = new ArrayList<>(form().files());
        files.addAll(contract().files());

        String id = id(service.submit(request("auto", null, new InputGroup(null, files))));
        Map<String, Object> task = await(service, id);

        assertEquals(2, docs(task).size(), "四个文件是两份文档：一份任免表，一份三页的合同");
        Map<String, Object> form = docs(task).stream()
                .filter(d -> d.get("name").equals("personnel_form_1.6")).findFirst().orElseThrow();
        assertEquals(List.of("personnel_form_1.6.json"),
                list(form.get("files")).stream().map(f -> f.get("name")).toList());
        Map<String, Object> contract = docs(task).stream()
                .filter(d -> d.get("name").equals("sample_contract")).findFirst().orElseThrow();
        assertEquals(List.of("sample_contract_0_res.json", "sample_contract_1_res.json", "sample_contract_2_res.json"),
                list(contract.get("files")).stream().map(f -> f.get("name")).toList());
        // 结果里也只有它自己的那个文件
        Map<String, Object> result = JSON.readValue(service.result(id, (int) form.get("index")), Map.class);
        assertEquals(1, list(result.get("files")).size());
        assertEquals(List.of(1, 2), list(result.get("files")).get(0).get("pages"));
    }

    @Test
    void 请求有问题时当场报错_什么都不留下() throws IOException {
        ExtractionTaskService service = service(dir, 2, 10, 0);

        assertBad(service, request("nope", null, form()), "没有名为 nope 的 skill。现有的：personnel-appointment");
        assertBad(service, request("../skills", null, form()), "skill 的名称不对：../skills");
        assertBad(service, new ExtractionRequest(SKILL, List.of(form()),
                new ExtractionRequest.Options(null, null, 0, null, null), null), "topK 要大于 0");
        assertBad(service, new ExtractionRequest("auto", List.of(form()),
                new ExtractionRequest.Options(0, null, null, null, null), null), "chunkTokens 要大于 0");
        // 指定了文档名，但这一组文件读出来不止一份
        List<InputGroup.InputFile> mixed = new ArrayList<>(form().files());
        mixed.addAll(contract().files());
        assertBad(service, request(SKILL, null, new InputGroup("张三", mixed)), "分成几组提交，或者不指定文档名");
        // 不是识别结果的 JSON。报错里不带服务器上的临时目录
        IllegalArgumentException e = assertBad(service, request(SKILL, null, new InputGroup(null,
                List.of(new InputGroup.InputFile("other.json", "{\"a\": 1}".getBytes())))),
                "文件 other.json：不是 PaddleOCR-VL 的识别结果：找不到 parsing_res_list");
        assertFalse(e.getMessage().contains("agent-upload-"), e.getMessage());
        // 太大的是另一种异常，接口上回 413
        assertThrows(UploadLimits.TooLargeException.class, () -> service.submit(request(SKILL, null,
                new InputGroup(null, List.of(new InputGroup.InputFile("big.json", new byte[(1 << 20) + 1]))))));

        try (Stream<Path> children = Files.list(dir)) {
            assertEquals(List.of(), children.toList(), "没有接受的提交不在存储里留东西");
        }
        assertEquals(0, llm.entered());
        assertEquals(List.of(), service.list(null, 10));
    }

    @Test
    void 同一个幂等键再提交一次得到原来的任务() {
        ExtractionTaskService service = service(dir, 2, 10, 0);

        String first = id(service.submit(request(SKILL, "batch-1", form())));
        ExtractionTaskService.Submission again = service.submit(request(SKILL, "batch-1", form()));

        assertFalse(again.created());
        assertEquals(first, again.task().get("id"));
        assertEquals("batch-1", again.task().get("idempotencyKey"));
        await(service, first);
        assertEquals(4, llm.usage().calls(), "只抽了一次");

        // 同一个键，内容不一样：不能悄悄返回原来的任务，也不能悄悄新建一个
        TaskConflictException e = assertThrows(TaskConflictException.class,
                () -> service.submit(request(SKILL, "batch-1", form("另一个人"))));
        assertTrue(e.getMessage().startsWith("幂等键 batch-1 已经用在任务 " + first + " 上了"), e.getMessage());
        assertThrows(TaskConflictException.class, () -> service.submit(request("auto", "batch-1", form())));

        // 不带键的每次都是新任务
        String second = id(service.submit(request(SKILL, null, form())));
        String third = id(service.submit(request(SKILL, null, form())));
        assertNotEquals(second, third);
        assertEquals(3, service.list(null, 10).size());
    }

    // ---- 排队、取消、删除 -----------------------------------------------------------------

    @Test
    void 同时提交相同幂等键也只建立一个任务() throws Exception {
        ExtractionTaskService service = service(dir, 1, 10, 0);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ExtractionTaskService.Submission>> submissions = new ArrayList<>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 6; i++) {
                submissions.add(callers.submit(() -> {
                    start.await();
                    return service.submit(request(SKILL, "same-key", form()));
                }));
            }
            start.countDown();
            List<ExtractionTaskService.Submission> replies = new ArrayList<>();
            for (Future<ExtractionTaskService.Submission> submission : submissions) {
                replies.add(submission.get(20, TimeUnit.SECONDS));
            }
            assertEquals(1, replies.stream().filter(ExtractionTaskService.Submission::created).count());
            String id = id(replies.get(0));
            assertTrue(replies.stream().allMatch(reply -> id.equals(reply.task().get("id"))));
            await(service, id);
        }
        assertEquals(1, new FileJobStore(dir).ids().size(), "竞争中创建的临时任务都应清掉");
        assertEquals(4, llm.usage().calls(), "同一业务请求只抽取一次");
    }

    @Test
    void 服务停止后拒绝提交_不登记也不留文件() throws IOException {
        ExtractionTaskService service = service(dir, 1, 1, 0);
        service.close();
        assertThrows(QueueFullException.class, () -> service.submit(request(SKILL, null, form())));
        assertTrue(service.list(null, 10).isEmpty());
        try (Stream<Path> children = Files.list(dir)) {
            assertEquals(List.of(), children.toList());
        }
    }

    @Test
    void 取消排队任务后可以立即使用空出的容量() {
        ExtractionTaskService service = service(dir, 1, 1, 0);
        llm.hold();
        String first = id(service.submit(request(SKILL, null, form("处理中"))));
        awaitTrue(() -> llm.entered() > 0, "第一份开始调用模型");
        for (int i = 0; i < 3; i++) {
            String canceled = id(service.submit(request(SKILL, null, form("取消" + i))));
            assertEquals("canceled", service.cancel(canceled).get("status"));
            assertEquals(0, service.queuedDocuments());
        }
        String next = id(service.submit(request(SKILL, null, form("继续处理"))));
        assertEquals(1, service.queuedDocuments());
        llm.release();
        assertEquals("succeeded", await(service, first).get("status"));
        assertEquals("succeeded", await(service, next).get("status"));
        assertEquals(8, llm.usage().calls(), "排队时取消的文档没有调用模型");
    }

    @Test
    void 排队的文档数有上限_满了就拒绝() {
        ExtractionTaskService service = service(dir, 1, 2, 0);
        llm.hold();

        String running = id(service.submit(request(SKILL, null, form())));
        awaitTrue(() -> llm.entered() > 0, "第一份文档开始处理");
        String waiting = id(service.submit(request(SKILL, null, form("甲"), form("乙"))));
        assertEquals(2, service.queuedDocuments());
        assertEquals(1, service.runningDocuments());
        assertEquals("queued", service.get(waiting, false).get("status"));

        QueueFullException full = assertThrows(QueueFullException.class,
                () -> service.submit(request(SKILL, null, form())));
        assertEquals("现在有 2 份文档在排队，再加上这 1 份就超过了上限 2。过一会儿再提交", full.getMessage());
        // 一次就超过容量的：等多久都排不进去，是请求的问题
        assertBad(service, request(SKILL, null, form("甲"), form("乙"), form("丙")),
                "这次提交读出来是 3 份文档，超过了队列的容量 2");
        assertEquals(2, service.list(null, 10).size(), "被拒绝的提交没有留下任务");
        assertEquals(2, new FileJobStore(dir).ids().size());

        llm.release();
        assertEquals("succeeded", await(service, running).get("status"));
        assertEquals("succeeded", await(service, waiting).get("status"));
        assertEquals(0, service.queuedDocuments());
        assertEquals(0, service.runningDocuments());
    }

    @Test
    void 取消_排队的直接取消_正在处理的被打断() {
        ExtractionTaskService service = service(dir, 1, 10, 0);
        llm.hold();
        String id = id(service.submit(request(SKILL, null, form("甲"), form("乙"))));
        awaitTrue(() -> llm.entered() > 0, "第一份文档开始处理");

        Map<String, Object> canceling = service.cancel(id);

        assertEquals(true, canceling.get("cancelRequested"));
        assertEquals("canceled", docs(canceling).get(1).get("status"), "排队的那一份当场就取消了");
        Map<String, Object> task = await(service, id);
        assertEquals("canceled", task.get("status"));
        assertEquals("canceled", docs(task).get(0).get("status"));
        assertNull(docs(task).get(0).get("error"), "被取消不是出错");
        assertEquals(0, service.queuedDocuments());
        TaskConflictException e = assertThrows(TaskConflictException.class, () -> service.result(id, 0));
        assertEquals("文档 甲 现在的状态是 canceled，没有结果", e.getMessage());
        awaitTrue(() -> metered.size() == 2, "两份文档都报了计量");
        assertTrue(metered.contains("null canceled 0") && metered.contains(SKILL + " canceled 0"), metered.toString());

        // 那个线程接着处理下一个任务，不受上一次打断的影响
        llm.release();
        String next = id(service.submit(request(SKILL, null, form())));
        assertEquals("succeeded", await(service, next).get("status"));
        // 已经结束的任务再取消，什么都不变
        assertEquals("succeeded", service.cancel(next).get("status"));
        assertEquals(false, service.get(next, false).get("cancelRequested"));
    }

    @Test
    void 删除_没结束的不能删_删了之后什么都不剩() {
        ExtractionTaskService service = service(dir, 1, 10, 0);
        llm.hold();
        String id = id(service.submit(request(SKILL, "k", form())));
        awaitTrue(() -> llm.entered() > 0, "开始处理");

        TaskConflictException e = assertThrows(TaskConflictException.class, () -> service.delete(id));
        assertEquals("任务 " + id + " 还没结束（running）。先取消，等它停下来再删", e.getMessage());

        llm.release();
        await(service, id);
        service.delete(id);

        assertFalse(Files.exists(dir.resolve(id)), "记录、提交上来的文件、结果都删了");
        assertEquals("没有 ID 是 " + id + " 的任务",
                assertThrows(TaskNotFoundException.class, () -> service.get(id, false)).getMessage());
        assertThrows(TaskNotFoundException.class, () -> service.result(id, 0));
        assertThrows(TaskNotFoundException.class, () -> service.cancel(id));
        assertThrows(TaskNotFoundException.class, () -> service.delete(id));
        // 幂等键也跟着放开了：同一个键再提交是一个新任务
        ExtractionTaskService.Submission again = service.submit(request(SKILL, "k", form()));
        assertTrue(again.created());
        assertNotEquals(id, again.task().get("id"));
    }

    @Test
    void 模型接口出错时这份文档失败_原因记在任务里() {
        ExtractionTaskService service = service(dir, 1, 10, 0);
        llm.failWith(new LlmCallException("连不上 http://127.0.0.1:1/v1"));

        String id = id(service.submit(request(SKILL, null, form())));
        Map<String, Object> task = await(service, id);

        assertEquals("failed", task.get("status"));
        assertEquals("模型接口调用失败：连不上 http://127.0.0.1:1/v1", docs(task).get(0).get("error"));
        assertEquals(Map.of("name", SKILL, "version", 1), docs(task).get(0).get("skill"));
        assertEquals(List.of(SKILL + " failed 0"), metered);

        // 服务没有因此停下：模型好了之后，下一个任务照常做
        llm.failWith(null);
        assertEquals("succeeded", await(service, id(service.submit(request(SKILL, null, form())))).get("status"));
    }

    @Test
    void 列出任务_新的在前_可以按状态筛() throws InterruptedException {
        ExtractionTaskService service = service(dir, 2, 10, 0);
        String first = id(service.submit(request(SKILL, null, form())));
        await(service, first);
        Thread.sleep(5); // 提交时间精确到毫秒，隔开一点才分得出先后
        String second = id(service.submit(request("auto", null, contract())));
        await(service, second);

        assertEquals(List.of(second, first), service.list(null, 10).stream().map(t -> t.get("id")).toList());
        assertEquals(List.of(second), service.list(null, 1).stream().map(t -> t.get("id")).toList());
        assertEquals(List.of(first), service.list("succeeded", 10).stream().map(t -> t.get("id")).toList());
        assertEquals(List.of(second), service.list("unrouted", 10).stream().map(t -> t.get("id")).toList());
        assertFalse(service.list(null, 10).get(0).containsKey("documents"), "清单里只有概况");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.list("done", 10));
        assertTrue(e.getMessage().startsWith("不认识的状态：done。可以写的有：queued、running、succeeded"), e.getMessage());
    }

    // ---- 重启、保留期 --------------------------------------------------------------------

    @Test
    void 重启时调小队列容量_历史积压仍逐份完成() {
        ExtractionTaskService first = service(dir.resolve("before"), 1, 4, 0);
        llm.hold();
        String id = id(first.submit(request(SKILL, null, form("甲"), form("乙"), form("丙"), form("丁"))));
        awaitTrue(() -> llm.entered() > 0, "第一份开始处理");
        first.close();
        copy(dir.resolve("before"), dir.resolve("after"));
        llm.release();

        ExtractionTaskService recovered = service(dir.resolve("after"), 1, 1, 0);
        Map<String, Object> task = await(recovered, id);
        assertEquals("succeeded", task.get("status"));
        assertEquals(4, map(task.get("counts")).get("succeeded"));
        assertEquals(0, recovered.queuedDocuments());
        assertEquals(0, recovered.runningDocuments());
    }

    @Test
    void 恢复时输入内容被改过_失败且不调用模型() throws IOException {
        Path before = dir.resolve("before");
        Path after = dir.resolve("after");
        ExtractionTaskService first = service(before, 1, 10, 0);
        llm.hold();
        String id = id(first.submit(request(SKILL, null, form())));
        awaitTrue(() -> llm.entered() > 0, "开始调用模型");
        first.close();
        copy(before, after);
        Files.writeString(after.resolve(id).resolve("files/input/0/personnel_form_1.6.json"), "{}");
        llm.release();
        long entered = llm.entered();

        ExtractionTaskService recovered = service(after, 1, 10, 0);
        Map<String, Object> task = await(recovered, id);
        assertEquals("failed", task.get("status"));
        assertTrue(String.valueOf(docs(task).get(0).get("error")).contains("SHA-256 和提交时不一致"));
        assertEquals(entered, llm.entered(), "不能使用改过的文件并在结果里沿用旧哈希");
    }

    @Test
    void 记录里的任务ID与目录不符时跳过() {
        ExtractionTaskService first = service(dir, 1, 10, 0);
        String id = id(first.submit(request(SKILL, null, form())));
        await(first, id);
        first.close();
        edit(dir, id, record -> record.put("id", "exanother"));

        ExtractionTaskService recovered = service(dir, 1, 10, 0);
        assertTrue(recovered.list(null, 10).isEmpty());
        assertTrue(Files.exists(dir.resolve(id).resolve("record.json")), "异常记录保留供排查");
    }

    @Test
    void 服务停下再启动_没做完的接着做_做完的还查得到() {
        ExtractionTaskService first = service(dir, 1, 10, 0);
        String done = id(first.submit(request(SKILL, "done-key", form("已完成"))));
        await(first, done);
        llm.hold();
        String id = id(first.submit(request(SKILL, null, form("甲"), form("乙"))));
        awaitTrue(() -> llm.entered() > 4, "第一份文档开始处理");

        first.close();

        // 停下时正在处理的那一份放回了排队，不算失败，也不算进尝试的次数
        Map<String, Object> record = record(dir, id);
        assertEquals(List.of("queued", "queued"), list(record.get("documents")).stream().map(d -> d.get("status")).toList());
        assertEquals(0, list(record.get("documents")).get(0).get("attempts"));

        llm.release();
        ExtractionTaskService second = service(dir, 1, 10, 0);

        Map<String, Object> task = await(second, id);
        assertEquals("succeeded", task.get("status"));
        assertEquals(2, map(task.get("counts")).get("succeeded"));
        assertEquals("林致远", map(JSON.readValue(second.result(id, 1), Map.class).get("data")).get("姓名"));
        // 重启之前做完的任务：状态、结果、幂等键都还在
        assertEquals("succeeded", second.get(done, false).get("status"));
        assertEquals("已完成", JSON.readValue(second.result(done, 0), Map.class).get("document"));
        ExtractionTaskService.Submission replay = second.submit(request(SKILL, "done-key", form("已完成")));
        assertFalse(replay.created());
        assertEquals(done, replay.task().get("id"));
    }

    @Test
    void 服务是被杀掉的_处理到一半的重新做_接连几次都这样就不再试() {
        ExtractionTaskService first = service(dir.resolve("a"), 1, 10, 0);
        llm.hold();
        String id = id(first.submit(request(SKILL, null, form())));
        awaitTrue(() -> llm.entered() > 0, "开始处理");
        // 这时候磁盘上的样子，就是进程被杀掉之后留下来的样子：文档是 running
        assertEquals("running", list(record(dir.resolve("a"), id).get("documents")).get(0).get("status"));
        copy(dir.resolve("a"), dir.resolve("b"));
        copy(dir.resolve("a"), dir.resolve("c"));
        llm.release();
        await(first, id);

        // 第一次遇到：重新排队，从头再做
        ExtractionTaskService second = service(dir.resolve("b"), 1, 10, 0);
        assertEquals("succeeded", await(second, id).get("status"));
        assertEquals(2, list(record(dir.resolve("b"), id).get("documents")).get(0).get("attempts"));

        // 已经开始过 3 次，每次都没做完：多半是这份文档让服务崩溃，不再试
        edit(dir.resolve("c"), id, record -> list(record.get("documents")).get(0)
                .put("attempts", ExtractionTaskService.MAX_ATTEMPTS));
        metered.clear();
        ExtractionTaskService third = service(dir.resolve("c"), 1, 10, 0);
        Map<String, Object> task = third.get(id, false);
        assertEquals("failed", task.get("status"));
        assertEquals("这份文档已经开始处理过 3 次，每次都是做到一半服务就停了，不再重试。确认服务稳定之后重新提交",
                docs(task).get(0).get("error"));
        assertEquals(List.of("null failed 0"), metered);
    }

    @Test
    void 记录读不了的任务跳过_不影响别的任务() throws IOException {
        ExtractionTaskService first = service(dir, 1, 10, 0);
        String good = id(first.submit(request(SKILL, null, form())));
        await(first, good);
        first.close();
        Files.createDirectories(dir.resolve("exbroken"));
        Files.writeString(dir.resolve("exbroken/record.json"), "{\"id\": \"exbroken\"");

        ExtractionTaskService second = service(dir, 1, 10, 0);

        assertEquals("succeeded", second.get(good, false).get("status"));
        assertThrows(TaskNotFoundException.class, () -> second.get("exbroken", false));
        assertTrue(Files.exists(dir.resolve("exbroken/record.json")), "读不了的记录留在原处，不删");
    }

    @Test
    void 过了保留期的任务自动删掉() {
        ExtractionTaskService first = service(dir.resolve("a"), 1, 10, 0);
        String id = id(first.submit(request(SKILL, null, form())));
        await(first, id);
        assertEquals(0, first.purgeExpired(), "没有配置保留期：不自动删");
        first.close();
        // 把它改成三天前结束的
        copy(dir.resolve("a"), dir.resolve("b"));
        String old = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS).toString();
        edit(dir.resolve("b"), id, record -> {
            record.put("createdAt", old);
            list(record.get("documents")).get(0).put("finishedAt", old);
        });

        // 保留 7 天：还留着
        ExtractionTaskService keeping = service(dir.resolve("b"), 1, 10, 7);
        assertEquals(0, keeping.purgeExpired());
        assertEquals("succeeded", keeping.get(id, false).get("status"));
        keeping.close();

        // 保留 1 天：启动时就清掉了
        ExtractionTaskService purging = service(dir.resolve("b"), 1, 10, 1);
        awaitTrue(() -> purging.list(null, 10).isEmpty(), "过期的任务被删掉");
        assertThrows(TaskNotFoundException.class, () -> purging.get(id, false));
        assertFalse(Files.exists(dir.resolve("b").resolve(id)), "存储里也没有了");
        // 刚做完的不受影响
        String fresh = id(purging.submit(request(SKILL, null, form())));
        await(purging, fresh);
        assertEquals(0, purging.purgeExpired());
        assertEquals("succeeded", purging.get(fresh, false).get("status"));
    }

    @Test
    void 各项设置要在范围里() {
        assertEquals("agent.tasks.workers 要大于 0，现在是 0", assertThrows(IllegalArgumentException.class,
                () -> new ExtractionTaskService.Settings(0, 10, 0)).getMessage());
        assertEquals("agent.tasks.queue-capacity 要大于 0，现在是 0", assertThrows(IllegalArgumentException.class,
                () -> new ExtractionTaskService.Settings(1, 0, 0)).getMessage());
        assertEquals("agent.tasks.retention-days 不能是负数，现在是 -1", assertThrows(IllegalArgumentException.class,
                () -> new ExtractionTaskService.Settings(1, 10, -1)).getMessage());
    }

    // ---- 小工具 -------------------------------------------------------------------------

    private ExtractionTaskService service(Path root, int workers, int capacity, int retentionDays) {
        ExtractionTaskService service = new ExtractionTaskService(extraction, documents, new FileJobStore(root),
                new LlmGate(8), new UploadLimits(20, 200, 1 << 20, 4 << 20),
                new ExtractionTaskService.Settings(workers, capacity, retentionDays),
                (skill, status, elapsedMs, usage) -> metered.add(skill + " " + status + " " + usage.calls()));
        services.add(service);
        service.start();
        return service;
    }

    private static ExtractionRequest request(String skill, String key, InputGroup... groups) {
        return new ExtractionRequest(skill, List.of(groups), null, key);
    }

    /** 示例任免表，一个文件。 */
    private static InputGroup form() {
        return form(null);
    }

    private static InputGroup form(String name) {
        return new InputGroup(name, List.of(file(FORM)));
    }

    /** 测试用的合同，三页各一个文件。 */
    private static InputGroup contract() {
        try (Stream<Path> files = Files.list(CONTRACT)) {
            return new InputGroup(null, files.sorted().map(ExtractionTaskServiceTest::file).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputGroup.InputFile file(Path path) {
        try {
            return new InputGroup.InputFile(path.getFileName().toString(), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String id(ExtractionTaskService.Submission submission) {
        return (String) submission.task().get("id");
    }

    private static IllegalArgumentException assertBad(ExtractionTaskService service, ExtractionRequest request,
                                                      String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.submit(request));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
        return e;
    }

    /** 等任务结束，返回它最后的样子。 */
    private static Map<String, Object> await(ExtractionTaskService service, String id) {
        awaitTrue(() -> {
            Object status = service.get(id, false).get("status");
            return !status.equals("queued") && !status.equals("running");
        }, "任务 " + id + " 结束");
        return service.get(id, false);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("等了 20 秒还没等到：" + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待时被打断");
            }
        }
    }

    /** 存储里一个任务的记录。 */
    private static Map<String, Object> record(Path root, String id) {
        return map(JSON.readValue(new FileJobStore(root).readRecord(id), Map.class));
    }

    /** 改存储里一个任务的记录。 */
    private static void edit(Path root, String id, Consumer<Map<String, Object>> change) {
        Map<String, Object> record = record(root, id);
        change.accept(record);
        new FileJobStore(root).writeRecord(id, JSON.writeValueAsString(record));
    }

    private static void copy(Path from, Path to) {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Map<String, Object>> docs(Map<String, Object> task) {
        return list(task.get("documents"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
