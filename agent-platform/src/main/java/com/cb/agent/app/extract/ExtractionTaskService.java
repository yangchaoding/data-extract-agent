package com.cb.agent.app.extract;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.document.InputGroup;
import com.cb.agent.app.document.UploadLimits;
import com.cb.agent.app.extract.ExtractionTask.Doc;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.engine.ExtractionResult;
import com.cb.agent.capability.extract.format.PyFormat;
import com.cb.agent.core.llm.LlmCallException;
import com.cb.agent.core.llm.LlmGate;
import com.cb.agent.core.llm.Usage;
import com.cb.agent.core.skill.SkillRouter;
import com.cb.agent.core.store.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "提交抽取任务"这个用例：收下文档，排队，在后台抽取，供调用方查状态、取结果、取消。对应方案第 14 节。
 *
 * <p>为什么是异步的：一份文档的抽取要几十秒到几分钟，同步的 HTTP 请求等不了这么久。
 * 提交之后立刻返回任务 ID，调用方拿着它来查。
 *
 * <p>几件事是这样做的：
 * <ul>
 *   <li><b>排队。</b>一个任务里的每份文档各排各的队，由固定个数的线程处理（{@link Settings#workers}）。
 *       排队的文档数有上限，满了就拒绝新的提交，不让队列无限变长。</li>
 *   <li><b>模型并发。</b>所有文档的模型调用共用一道闸门（{@link LlmGate}）。</li>
 *   <li><b>存储。</b>提交上来的文件、任务的状态、抽出来的结果都存进 {@link JobStore}。
 *       每次状态变化都写下去，所以服务重启之后任务还在。</li>
 *   <li><b>恢复。</b>启动时把上次没做完的文档重新排队（{@link #start}）。同一份文档接连
 *       {@link #MAX_ATTEMPTS} 次都是做到一半服务就停了，就不再试，免得一份有问题的文档让服务反复崩溃。</li>
 *   <li><b>幂等。</b>带着同一个幂等键再提交一次，得到的是原来的任务。</li>
 *   <li><b>取消。</b>排队的文档直接标成已取消；正在处理的，打断它的线程，模型调用会立刻停下。</li>
 *   <li><b>一份失败不影响其余的。</b>每份文档有自己的状态和错误原因。</li>
 *   <li><b>保留期。</b>配置了保留天数之后，结束得比它更早的任务会被自动删掉（{@link #purgeExpired}）。</li>
 * </ul>
 *
 * <p>现在只适合单个实例：任务在这个进程的内存里排队，几个实例之间不会分工。
 *
 * <p>日志里只有任务 ID、文档名、状态、用量，不写原文和抽到的值（方案第 16 节）。失败的原因可能带着
 * 模型接口返回的内容，所以只记在任务里，不进日志。
 */
public class ExtractionTaskService implements AutoCloseable {

    /** 一份文档最多开始处理几次。超过了说明每次都是做到一半服务就停了，不再重试。 */
    static final int MAX_ATTEMPTS = 3;

    /** 任务可能的状态。 */
    private static final List<String> TASK_STATUSES = List.of(ExtractionTask.QUEUED, ExtractionTask.RUNNING,
            ExtractionTask.SUCCEEDED, ExtractionTask.PARTIAL, ExtractionTask.FAILED, ExtractionTask.UNROUTED,
            ExtractionTask.CANCELED);

    private static final Logger log = LoggerFactory.getLogger(ExtractionTaskService.class);

    /**
     * @param workers       同时处理几份文档
     * @param queueCapacity 最多有多少份文档在排队。满了之后新的提交会被拒绝
     * @param retentionDays 任务结束之后留几天，过了就自动删掉（记录、提交上来的文件、结果）；0 表示不自动删
     */
    public record Settings(int workers, int queueCapacity, int retentionDays) {

        public Settings {
            if (workers < 1) {
                throw new IllegalArgumentException("agent.tasks.workers 要大于 0，现在是 " + workers);
            }
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("agent.tasks.queue-capacity 要大于 0，现在是 " + queueCapacity);
            }
            if (retentionDays < 0) {
                throw new IllegalArgumentException("agent.tasks.retention-days 不能是负数，现在是 " + retentionDays);
            }
        }
    }

    /**
     * 一次提交的回应。
     *
     * @param task    任务现在的样子
     * @param created true 是新建的；false 是幂等键对上了，返回的是原来那个任务
     */
    public record Submission(Map<String, Object> task, boolean created) {
    }

    private final ExtractService extraction;
    private final DocumentReadService documents;
    private final JobStore store;
    private final LlmGate gate;
    private final UploadLimits limits;
    private final Settings settings;
    private final ExtractionMeter meter;
    private final JsonMapper json = JsonMapper.builder().build();

    private final Map<String, ExtractionTask> tasks = new ConcurrentHashMap<>();
    /** 幂等键 → 任务 ID。读写都要先拿到 {@link #submitLock}。 */
    private final Map<String, String> byKey = new HashMap<>();
    /** 登记和删除任务时拿着它：保证"查幂等键、查队列还有没有位置、登记"是一个整体。 */
    private final Object submitLock = new Object();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final ThreadPoolExecutor pool;
    /** 定时清理过期任务用的；没有配置保留期时为 null。 */
    private ScheduledExecutorService sweeper;
    private volatile boolean stopping;

    public ExtractionTaskService(ExtractService extraction, DocumentReadService documents, JobStore store,
                                 LlmGate gate, UploadLimits limits, Settings settings, ExtractionMeter meter) {
        this.extraction = extraction;
        this.documents = documents;
        this.store = store;
        this.gate = gate;
        this.limits = limits;
        this.settings = settings;
        this.meter = meter;
        AtomicInteger number = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), runnable -> {
            Thread thread = new Thread(runnable, "extract-" + number.incrementAndGet());
            thread.setDaemon(true); // 不拦着进程退出。没做完的文档下次启动时会接着做
            return thread;
        });
    }

    // ---- 启动和停止 ----------------------------------------------------------------------

    /**
     * 把存储里已有的任务读进来，上次没做完的文档重新排队。服务启动时调用一次。
     *
     * <p>上次停下来时"正在处理"的文档，这一次从头再做：做到一半的中间结果没有存。
     * 记录读不了的任务（文件被改过，或者是别的版本写的）跳过，留在原处，不删。
     */
    public void start() {
        List<ExtractionTask> loaded = new ArrayList<>();
        for (String id : store.ids()) {
            try {
                ExtractionTask task = ExtractionTask.fromRecord(json.readValue(store.readRecord(id), Map.class));
                if (!id.equals(task.id)) {
                    throw new IllegalArgumentException("记录里的任务 ID 和存储目录不一致");
                }
                loaded.add(task);
            } catch (RuntimeException e) {
                log.warn("任务 {} 的记录读不了，跳过：{}", id, e.toString());
            }
        }
        loaded.sort(Comparator.comparing((ExtractionTask task) -> task.createdAt)); // 先提交的先做
        int resumed = 0;
        for (ExtractionTask task : loaded) {
            List<Doc> todo = new ArrayList<>();
            int gaveUp = 0;
            synchronized (task) {
                boolean changed = false;
                for (Doc doc : task.documents) {
                    if (doc.status.equals(ExtractionTask.RUNNING)) {
                        changed = true;
                        if (doc.attempts >= MAX_ATTEMPTS) {
                            doc.status = ExtractionTask.FAILED;
                            doc.error = "这份文档已经开始处理过 " + doc.attempts + " 次，每次都是做到一半服务就停了，不再重试。"
                                    + "确认服务稳定之后重新提交";
                            doc.finishedAt = now();
                            gaveUp++;
                            continue;
                        }
                        doc.status = ExtractionTask.QUEUED;
                        doc.startedAt = null;
                    }
                    if (doc.status.equals(ExtractionTask.QUEUED)) {
                        if (task.cancelRequested) {
                            doc.status = ExtractionTask.CANCELED;
                            doc.finishedAt = now();
                            changed = true;
                        } else {
                            todo.add(doc);
                        }
                    }
                }
                if (changed) {
                    persistQuietly(task);
                }
            }
            synchronized (submitLock) {
                tasks.put(task.id, task);
                if (task.idempotencyKey != null) {
                    byKey.put(task.idempotencyKey, task.id);
                }
            }
            for (int i = 0; i < gaveUp; i++) {
                report(null, ExtractionTask.FAILED, 0, Usage.ZERO);
            }
            queued.addAndGet(todo.size());
            resumed += todo.size();
        }
        // 重启时可能调小了容量，历史任务仍要保留；每次只把执行器装得下的文档交进去，其余等待下一轮调度。
        dispatchPending();
        if (!loaded.isEmpty()) {
            log.info("读进 {} 个已有的任务，其中 {} 份文档上次没做完，重新排队", loaded.size(), resumed);
        }
        if (settings.retentionDays() > 0) {
            sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "extract-sweeper");
                thread.setDaemon(true);
                return thread;
            });
            // 启动时清一次，之后每小时清一次
            sweeper.scheduleWithFixedDelay(this::purgeExpiredQuietly, 0, 1, TimeUnit.HOURS);
        }
    }

    /**
     * 停下来：不再处理新的文档，正在处理的被打断。服务退出时调用。
     * 被打断的和还在排队的文档都留着"排队"的状态，下次启动时接着做。
     */
    @Override
    public void close() {
        synchronized (submitLock) {
            stopping = true;
            pool.shutdownNow(); // 和提交、调度互斥，不能在任务登记后突然拒绝它的工作
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        try {
            if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("停止时还有文档没停下来。它们在存储里是 running，下次启动时会重新排队");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 提交 ---------------------------------------------------------------------------

    /**
     * 提交一个抽取任务。文件在这里就读一遍：读不了的当场报错，不用等到排上队才知道。
     *
     * @return 任务，以及它是不是新建的
     * @throws IllegalArgumentException 请求有问题：skill 不存在、参数不在范围里、文件读不了、超过大小上限
     * @throws TaskConflictException    这个幂等键用过了，而且那一次提交的内容和这一次不一样
     * @throws QueueFullException       排队的文档太多，这一次没有接受
     */
    public Submission submit(ExtractionRequest request) {
        checkAccepting();
        limits.check(request.documents());
        // 先把这一次要用的抽取准备好：skill 不存在、字段定义写错、参数不在范围里，都在这里报出来
        ExtractJob prepared = null;
        if (request.auto()) {
            request.options().applyTo(EngineOptions.DEFAULTS);
        } else {
            prepared = extraction.bySkillName(request.skill(), request.options()::applyTo).limitedBy(gate);
        }
        String skill = request.auto() ? ExtractionRequest.AUTO : prepared.skill();

        List<ExtractionTask.Group> groups = new ArrayList<>();
        for (InputGroup group : request.documents()) {
            List<ExtractionTask.FileInfo> files = new ArrayList<>();
            for (InputGroup.InputFile file : group.files()) {
                files.add(new ExtractionTask.FileInfo(file.name(), file.content().length, sha256(file.content())));
            }
            groups.add(new ExtractionTask.Group(group.name(), files));
        }
        String fingerprint = fingerprint(skill, request.options(), groups);

        // 幂等键对上了就直接返回原来的任务，后面的读文件、存文件都不用做
        Submission replay = replay(request.idempotencyKey(), fingerprint);
        if (replay != null) {
            return replay;
        }

        List<Doc> docs = new ArrayList<>();
        for (int g = 0; g < request.documents().size(); g++) {
            List<Document> read = documents.read(request.documents().get(g));
            for (int i = 0; i < read.size(); i++) {
                docs.add(new Doc(docs.size(), g, i, read.get(i).name(), read.get(i).pageCount(),
                        FileTrace.fileNames(read.get(i))));
            }
        }
        if (docs.size() > settings.queueCapacity()) {
            // 等多久都排不进去，和"现在队列满了"是两回事，要说清楚
            throw new IllegalArgumentException("这次提交读出来是 " + docs.size() + " 份文档，超过了队列的容量 "
                    + settings.queueCapacity() + "（配置 agent.tasks.queue-capacity）。分几次提交");
        }
        ExtractionTask task = new ExtractionTask(newId(), skill, request.options(), request.idempotencyKey(),
                fingerprint, now(), groups, docs);
        if (prepared != null) {
            task.jobs.put(prepared.skill(), prepared);
        }
        // 先把文件和记录存好，再登记、排队：处理它的线程一开始就要从存储里读。
        // 这时候别人还不知道这个 ID，所以不用拿着全局的锁做这些慢的事
        try {
            for (int g = 0; g < request.documents().size(); g++) {
                for (InputGroup.InputFile file : request.documents().get(g).files()) {
                    store.writeFile(task.id, inputName(g, file.name()), file.content());
                }
            }
            synchronized (task) {
                persist(task);
            }
            synchronized (submitLock) {
                checkAccepting();
                replay = replay(request.idempotencyKey(), fingerprint); // 存文件的这一会儿，同一个键可能已经被登记了
                if (replay == null) {
                    if (queued.get() + docs.size() > settings.queueCapacity()) {
                        throw new QueueFullException("现在有 " + queued.get() + " 份文档在排队，再加上这 " + docs.size()
                                + " 份就超过了上限 " + settings.queueCapacity() + "。过一会儿再提交");
                    }
                    tasks.put(task.id, task);
                    if (task.idempotencyKey != null) {
                        byKey.put(task.idempotencyKey, task.id);
                    }
                    queued.addAndGet(docs.size());
                }
            }
        } catch (RuntimeException e) {
            deleteQuietly(task.id); // 没有登记成：不留下半个任务
            throw e;
        }
        if (replay != null) {
            deleteQuietly(task.id);
            return replay;
        }
        dispatchPending();
        log.info("任务 {} 已接受：skill {}，{} 份文档", task.id, skill, docs.size());
        synchronized (task) {
            return new Submission(task.view(true), true);
        }
    }

    /**
     * 这个幂等键是不是已经有任务了。
     *
     * @return 有，而且内容相同：原来的任务；没有：null
     * @throws TaskConflictException 有，但内容不同
     */
    private Submission replay(String key, String fingerprint) {
        if (key == null) {
            return null;
        }
        ExtractionTask existing;
        synchronized (submitLock) {
            existing = tasks.get(byKey.getOrDefault(key, ""));
        }
        if (existing == null) {
            return null;
        }
        if (!fingerprint.equals(existing.fingerprint)) {
            throw new TaskConflictException("幂等键 " + key + " 已经用在任务 " + existing.id
                    + " 上了，而这一次提交的内容（skill、参数或文件）和那一次不一样。是另一个请求的话，换一个幂等键");
        }
        synchronized (existing) {
            return new Submission(existing.view(true), false);
        }
    }

    // ---- 查询、取消、删除 -----------------------------------------------------------------

    /**
     * 一个任务现在的样子：状态、每份文档的情况。
     *
     * @param withResults 要不要带上抽取结果。带的话，已经抽完的文档多一项 {@code result}，内容同 {@link #result}
     * @throws TaskNotFoundException 没有这个任务
     */
    public Map<String, Object> get(String id, boolean withResults) {
        ExtractionTask task = require(id);
        synchronized (task) {
            Map<String, Object> view = task.view(true);
            if (withResults) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> docs = (List<Map<String, Object>>) view.get("documents");
                for (Doc doc : task.documents) {
                    byte[] bytes = doc.status.equals(ExtractionTask.SUCCEEDED)
                            ? store.readFile(id, resultName(doc.index)) : null;
                    docs.get(doc.index).put("result", bytes == null ? null : json.readValue(bytes, Object.class));
                }
            }
            return view;
        }
    }

    /**
     * 任务里一份文档的抽取结果，JSON 文字。内容和命令行 {@code extract} 写出来的文件相同，
     * 另外多三项：{@code skill}（用的是哪个 skill 的哪一版）、{@code routing}（skill 是怎么定的）、
     * {@code files}（这份文档是从哪几个文件读出来的：ID、内容的哈希、各自对应文档的哪几页）；
     * 每个值的出处（{@code locator}）里多 {@code file_id} 和 {@code file_hash}，指明证据在哪个文件上。
     *
     * @param index 文档在任务里的序号，从 0 开始
     * @throws TaskNotFoundException 没有这个任务，或者任务里没有这个序号的文档
     * @throws TaskConflictException 这份文档没有结果：还没做完，或者失败了、被取消了
     */
    public String result(String id, int index) {
        ExtractionTask task = require(id);
        synchronized (task) {
            if (index < 0 || index >= task.documents.size()) {
                throw new TaskNotFoundException("任务 " + id + " 里没有序号是 " + index + " 的文档。它有 "
                        + task.documents.size() + " 份文档，序号从 0 开始");
            }
            Doc doc = task.documents.get(index);
            if (!doc.status.equals(ExtractionTask.SUCCEEDED)) {
                throw new TaskConflictException("文档 " + doc.name + " 现在的状态是 " + doc.status + "，没有结果"
                        + (doc.error == null ? "" : "：" + doc.error));
            }
            byte[] bytes = store.readFile(id, resultName(index));
            if (bytes == null) {
                throw new IllegalStateException("任务 " + id + " 第 " + index + " 份文档的结果文件不见了");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 列出任务，新提交的在前。每个任务只有概况，没有文档的明细。
     *
     * @param status 只要这个状态的；null 表示都要
     * @param limit  最多返回几个
     * @throws IllegalArgumentException 状态写的不是认识的那几种
     */
    public List<Map<String, Object>> list(String status, int limit) {
        if (status != null && !TASK_STATUSES.contains(status)) {
            throw new IllegalArgumentException("不认识的状态：" + status + "。可以写的有：" + String.join("、", TASK_STATUSES));
        }
        List<ExtractionTask> all = new ArrayList<>(tasks.values());
        all.sort(Comparator.comparing((ExtractionTask task) -> task.createdAt).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExtractionTask task : all) {
            if (out.size() >= limit) {
                break;
            }
            synchronized (task) {
                if (status == null || status.equals(task.status())) {
                    out.add(task.view(false));
                }
            }
        }
        return out;
    }

    /**
     * 取消一个任务。排队的文档直接标成已取消；正在处理的会被打断，稍后变成已取消，
     * 所以返回时任务的状态可能还是 running。已经抽完的文档不受影响，结果仍然可以取。
     * 任务已经结束时什么都不做。
     *
     * @return 任务现在的样子
     * @throws TaskNotFoundException 没有这个任务
     */
    public Map<String, Object> cancel(String id) {
        ExtractionTask task = require(id);
        int dropped = 0;
        Map<String, Object> view;
        synchronized (task) {
            if (!task.finished()) {
                task.cancelRequested = true;
                for (Doc doc : task.documents) {
                    if (doc.status.equals(ExtractionTask.QUEUED)) {
                        doc.status = ExtractionTask.CANCELED;
                        doc.finishedAt = now();
                        queued.decrementAndGet();
                        if (doc.queuedWork != null) {
                            pool.remove(doc.queuedWork);
                            doc.queuedWork = null;
                        }
                        dropped++;
                    } else if (doc.status.equals(ExtractionTask.RUNNING) && doc.worker != null) {
                        doc.worker.interrupt();
                    }
                }
                persist(task);
                log.info("任务 {} 被取消", id);
            }
            view = task.view(true);
        }
        for (int i = 0; i < dropped; i++) {
            report(null, ExtractionTask.CANCELED, 0, Usage.ZERO);
        }
        dispatchPending();
        return view;
    }

    /**
     * 删掉一个已经结束的任务：记录、提交上来的文件、结果，都删。删了就找不回来。
     *
     * @throws TaskNotFoundException 没有这个任务
     * @throws TaskConflictException 任务还没结束。先取消，等它停下来再删
     */
    public void delete(String id) {
        synchronized (submitLock) {
            ExtractionTask task = require(id);
            synchronized (task) {
                if (!task.finished()) {
                    throw new TaskConflictException("任务 " + id + " 还没结束（" + task.status() + "）。先取消，等它停下来再删");
                }
                store.delete(id);
                tasks.remove(id);
                if (task.idempotencyKey != null) {
                    byKey.remove(task.idempotencyKey);
                }
            }
        }
        log.info("任务 {} 已删除", id);
    }

    /**
     * 删掉过了保留期的任务：已经结束，而且结束的时间比"现在往前数保留天数"更早。没有配置保留期时什么都不做。
     *
     * @return 删了几个
     */
    public int purgeExpired() {
        if (settings.retentionDays() <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(settings.retentionDays()));
        int purged = 0;
        for (ExtractionTask task : new ArrayList<>(tasks.values())) {
            Instant finishedAt;
            synchronized (task) {
                finishedAt = task.finishedAt();
            }
            if (finishedAt != null && finishedAt.isBefore(cutoff)) {
                try {
                    delete(task.id);
                    purged++;
                } catch (TaskNotFoundException | TaskConflictException e) {
                    // 刚好被别人删了：不用管
                }
            }
        }
        if (purged > 0) {
            log.info("清理了 {} 个过了保留期（{} 天）的任务", purged, settings.retentionDays());
        }
        return purged;
    }

    private void purgeExpiredQuietly() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            log.warn("清理过期任务时出错，下一轮再试：{}", e.toString()); // 抛出去的话定时器就不再执行了
        }
    }

    /** 现在有几份文档在排队。 */
    public int queuedDocuments() {
        return queued.get();
    }

    /** 现在有几份文档正在处理。 */
    public int runningDocuments() {
        return running.get();
    }

    // ---- 处理一份文档 --------------------------------------------------------------------

    /**
     * 按提交时间把仍在排队、尚未交给执行器的文档交进去。执行器的队列有真实容量上限。
     *
     * <p>恢复时历史积压可能超过新的容量，因此不一次性灌进队列。工作结束或取消后再调用，继续装下一批；
     * 积压期间 {@link #queuedDocuments()} 包含尚未调度的文档，新的提交仍受同一个总量上限约束。
     * 锁的顺序固定为提交锁、任务锁，和删除、幂等重放保持一致。
     */
    private void dispatchPending() {
        synchronized (submitLock) {
            if (stopping) {
                return;
            }
            List<ExtractionTask> pending = new ArrayList<>(tasks.values());
            pending.sort(Comparator.comparing((ExtractionTask task) -> task.createdAt).thenComparing(task -> task.id));
            for (ExtractionTask task : pending) {
                synchronized (task) {
                    for (Doc doc : task.documents) {
                        if (!doc.status.equals(ExtractionTask.QUEUED) || doc.queuedWork != null) {
                            continue;
                        }
                        Runnable work = () -> {
                            try {
                                process(task, doc);
                            } finally {
                                dispatchPending();
                            }
                        };
                        doc.queuedWork = work;
                        try {
                            pool.execute(work);
                        } catch (RejectedExecutionException e) {
                            doc.queuedWork = null;
                            return; // 队列满了，已持久化的文档在下一次调度时继续交，不丢任务
                        }
                    }
                }
            }
        }
    }

    /** 停止后不能再接受新任务，避免留下已登记却永远不会在本次进程里执行的提交。 */
    private void checkAccepting() {
        if (stopping) {
            throw new QueueFullException("服务正在停止，暂时不能接受新的任务。服务启动后再提交");
        }
    }

    /**
     * 一份文档处理完的结论。
     *
     * @param status   最终的状态
     * @param error    没成功时的原因
     * @param job      用的那个准备好的抽取；还没定下 skill 就结束了的为 null
     * @param decision 选 skill 的结论；还没选就结束了的为 null
     * @param document 读出来的文档；没读成时为 null
     * @param result   抽取结果；只有成功时有
     */
    private record Outcome(String status, String error, ExtractJob job, SkillRouter.Decision decision,
                           Document document, ExtractionResult result) {
    }

    private void process(ExtractionTask task, Doc doc) {
        synchronized (task) {
            doc.queuedWork = null;
            if (!doc.status.equals(ExtractionTask.QUEUED)) {
                return; // 排队的时候被取消了
            }
            doc.status = ExtractionTask.RUNNING;
            doc.startedAt = now();
            doc.attempts++;
            doc.worker = Thread.currentThread();
            queued.decrementAndGet();
            running.incrementAndGet();
            persistQuietly(task);
        }
        SkillRouter.Decision decision = null;
        ExtractJob job = null;
        ExtractJob.Run run = null;
        Document document = null;
        ExtractionResult result = null;
        String status;
        String error = null;
        try {
            document = load(task, doc);
            decision = task.auto() ? extraction.route(document) : SkillRouter.explicit(task.skill);
            if (decision.routed()) {
                job = job(task, decision.skill());
                run = job.newRun();
                result = run.extract(document);
                status = ExtractionTask.SUCCEEDED;
            } else {
                status = ExtractionTask.UNROUTED;
                error = "没能确定这份文档该用哪个 skill：" + decision.reason() + "。在请求里用 skill 直接指定一个";
            }
        } catch (LlmCallException e) {
            status = ExtractionTask.FAILED;
            error = "模型接口调用失败：" + e.getMessage();
        } catch (IllegalArgumentException e) {
            // 读取器、skill、字段定义的检查抛出的都是这一种，消息是写给人看的
            status = ExtractionTask.FAILED;
            error = e.getMessage();
        } catch (Throwable e) {
            // 连 Error 也接住：不管出了什么事，这份文档都要有一个最终的状态，不能永远停在 running。
            // 被取消、服务停止时线程被打断，各处抛出来的异常不一样，也都落在这里，那不算意外
            if (!stopping && !cancelRequested(task)) {
                log.error("任务 {} 的文档 {}（{}）处理时出了意料之外的错误", task.id, doc.index, doc.name, e);
            }
            status = ExtractionTask.FAILED;
            error = "服务内部出错了，细节在服务的日志里（任务 " + task.id + "，文档 " + doc.index + "）";
        }
        finish(task, doc, new Outcome(status, error, job, decision, document, result),
                run == null ? Usage.ZERO : run.usage());
    }

    private void finish(ExtractionTask task, Doc doc, Outcome outcome, Usage usage) {
        String status = outcome.status();
        String error = outcome.error();
        long elapsed;
        synchronized (task) {
            doc.worker = null;
            // 取消的打断可能在抽取已经做完之后才到。这个线程接下来还要处理别的文档，不能带着打断标记走。
            // 取消只在拿着这把锁、而且 worker 还指着这个线程时才会打断它，所以这里清掉之后不会再来
            Thread.interrupted();
            running.decrementAndGet();
            if (status.equals(ExtractionTask.SUCCEEDED)) {
                try {
                    store.writeFile(task.id, resultName(doc.index), PyFormat.jsonIndented(
                            resultMap(task, doc, outcome), 2).getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    log.error("任务 {} 的文档 {} 的结果存不下来", task.id, doc.index, e);
                    status = ExtractionTask.FAILED;
                    error = "抽取做完了，但结果存不下来，细节在服务的日志里";
                }
            }
            boolean succeeded = status.equals(ExtractionTask.SUCCEEDED);
            if (!succeeded && stopping) {
                // 服务正在停：这份文档不算失败，放回排队，下次启动时接着做。这一次也不算进尝试的次数
                doc.status = ExtractionTask.QUEUED;
                doc.startedAt = null;
                doc.attempts--;
                queued.incrementAndGet();
                persistQuietly(task);
                return;
            }
            if (!succeeded && task.cancelRequested) {
                status = ExtractionTask.CANCELED;
                error = null;
            }
            doc.status = status;
            doc.error = error;
            doc.finishedAt = now();
            doc.usage = usage;
            if (outcome.job() != null) {
                doc.skill = skillMap(outcome.job());
            }
            if (outcome.decision() != null) {
                doc.routing = outcome.decision().toMap();
            }
            if (succeeded) {
                @SuppressWarnings("unchecked")
                Map<String, Object> summary = (Map<String, Object>) outcome.result().toMap().get("summary");
                doc.summary = summary;
            }
            persistQuietly(task);
            elapsed = doc.elapsedMs() == null ? 0 : doc.elapsedMs();
            log.info("任务 {} 文档 {}（{}）：{}，{} 毫秒，模型调用 {} 次，输入 {} token，输出 {} token", task.id,
                    doc.index, doc.name, status, elapsed, usage.calls(), usage.promptTokens(),
                    usage.completionTokens());
            if (task.finished()) {
                task.jobs.clear(); // 用不着了
                log.info("任务 {} 结束：{}", task.id, task.status());
            }
        }
        report(outcome.job() == null ? null : outcome.job().skill(), status, elapsed, usage);
    }

    /** 从存储里把这份文档读出来。每次都从存下来的文件读，这样刚提交的和重启之后恢复的走的是同一条路。 */
    private Document load(ExtractionTask task, Doc doc) {
        ExtractionTask.Group group = task.groups.get(doc.group);
        List<InputGroup.InputFile> files = new ArrayList<>();
        for (ExtractionTask.FileInfo file : group.files()) {
            byte[] content = store.readFile(task.id, inputName(doc.group, file.name()));
            if (content == null) {
                throw new IllegalArgumentException("提交上来的文件 " + file.name() + " 在存储里找不到了");
            }
            // 文件层出处必须指向提交时的那份内容，不能把改过的文件抽出来的值盖上旧哈希。
            if (!sha256(content).equals(file.sha256())) {
                throw new IllegalArgumentException("存储里的文件 " + file.name()
                        + " 内容已改变（SHA-256 和提交时不一致），不能继续这个任务。请重新提交");
            }
            files.add(new InputGroup.InputFile(file.name(), content));
        }
        List<Document> read = documents.read(new InputGroup(group.name(), files));
        if (doc.ordinal >= read.size()) {
            throw new IllegalArgumentException("这一组文件现在读出来只有 " + read.size() + " 份文档，和提交时不一样");
        }
        return read.get(doc.ordinal);
    }

    /** 这个任务按某个 skill 抽取时用的那一个准备好的抽取。第一次用到时准备，之后的文档共用。 */
    private ExtractJob job(ExtractionTask task, String skill) {
        synchronized (task) {
            ExtractJob job = task.jobs.get(skill);
            if (job == null) {
                job = extraction.bySkillName(skill, task.options::applyTo).limitedBy(gate);
                task.jobs.put(skill, job);
            }
            return job;
        }
    }

    /**
     * 结果文件的内容：命令行写出来的那些，加上 skill、routing、files 三项，
     * 每个值的出处里再补上文件那一层（{@code file_id}、{@code file_hash}，见 {@link FileTrace}）。
     */
    private static Map<String, Object> resultMap(ExtractionTask task, Doc doc, Outcome outcome) {
        Map<String, Object> base = outcome.result().toMap();
        List<ExtractionTask.FileInfo> files = task.fileInfosOf(doc);
        FileTrace.stamp(base.get("fields"), files, outcome.document());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", base.get("document"));
        out.put("schema", base.get("schema"));
        out.put("skill", skillMap(outcome.job()));
        out.put("routing", outcome.decision().toMap());
        out.put("files", FileTrace.files(files, outcome.document()));
        out.put("data", base.get("data"));
        out.put("fields", base.get("fields"));
        out.put("summary", base.get("summary"));
        return out;
    }

    private static Map<String, Object> skillMap(ExtractJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", job.skill());
        out.put("version", job.skillVersion());
        return out;
    }

    // ---- 小工具 -------------------------------------------------------------------------

    private ExtractionTask require(String id) {
        ExtractionTask task = id == null ? null : tasks.get(id);
        if (task == null) {
            throw new TaskNotFoundException("没有 ID 是 " + id + " 的任务");
        }
        return task;
    }

    private static boolean cancelRequested(ExtractionTask task) {
        synchronized (task) {
            return task.cancelRequested;
        }
    }

    /** 把任务现在的状态写进存储。调用时要拿着任务的锁。 */
    private void persist(ExtractionTask task) {
        store.writeRecord(task.id, json.writeValueAsString(task.record()));
    }

    /** 同 {@link #persist}，但写不下去时只记日志：后台处理不能因为存储一时的故障停下来，内存里的状态还是对的。 */
    private void persistQuietly(ExtractionTask task) {
        try {
            persist(task);
        } catch (RuntimeException e) {
            log.error("任务 {} 的状态写不进存储。服务重启之后它会回到上一次写成功时的状态", task.id, e);
        }
    }

    private void deleteQuietly(String id) {
        try {
            store.delete(id);
        } catch (RuntimeException e) {
            log.warn("任务 {} 没有建成，它已经存下的文件也没删掉：{}", id, e.toString());
        }
    }

    private void report(String skill, String status, long elapsedMs, Usage usage) {
        try {
            meter.documentFinished(skill, status, elapsedMs, usage);
        } catch (RuntimeException e) {
            log.warn("记指标时出错：{}", e.toString()); // 指标记不上不影响任务
        }
    }

    private static String inputName(int group, String file) {
        return "input/" + group + "/" + file;
    }

    private static String resultName(int index) {
        return "result/" + index + ".json";
    }

    /** 任务 ID。随机的、猜不出来的：现在还没有按调用方隔离，知道 ID 就能取到结果。 */
    private static String newId() {
        return "ex" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 精确到毫秒就够了，写出来也短一些。 */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** 一次提交的指纹：skill、参数、每个文件的名字和内容的哈希。 */
    private static String fingerprint(String skill, ExtractionRequest.Options options,
                                      List<ExtractionTask.Group> groups) {
        StringBuilder text = new StringBuilder(skill).append('\n').append(options.toMap()).append('\n');
        for (ExtractionTask.Group group : groups) {
            text.append("# ").append(group.name()).append('\n');
            for (ExtractionTask.FileInfo file : group.files()) {
                text.append(file.name()).append(' ').append(file.sha256()).append('\n');
            }
        }
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // 每个 Java 运行时都带着 SHA-256
        }
    }
}
