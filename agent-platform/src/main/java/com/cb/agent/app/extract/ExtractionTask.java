package com.cb.agent.app.extract;

import com.cb.agent.core.llm.Usage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个抽取任务：一次提交，里面有一份或几份文档，各自排队、各自有状态。对应方案第 14 节。
 *
 * <p>文档的状态：
 * <pre>
 *   queued ──▶ running ──▶ succeeded   抽完了，有结果
 *      │          ├──────▶ failed      出错了，error 里是原因
 *      │          ├──────▶ unrouted    没能确定用哪个 skill（skill 写的是 auto 时）
 *      └──────────┴──────▶ canceled    被取消了
 * </pre>
 * 任务的状态由文档的状态算出来，见 {@link #status()}。
 *
 * <p>这个类只是数据，不做事：怎么排队、怎么执行在 {@link ExtractionTaskService} 里。
 * 它会被几个线程同时读写（处理它的几份文档的线程、查询它的请求），<b>所有读写都要先拿到这个对象的锁</b>。
 */
final class ExtractionTask {

    static final String QUEUED = "queued";
    static final String RUNNING = "running";
    static final String SUCCEEDED = "succeeded";
    static final String FAILED = "failed";
    static final String UNROUTED = "unrouted";
    static final String CANCELED = "canceled";
    /** 只用于任务：一部分文档成功，其余的失败或没能确定 skill。 */
    static final String PARTIAL = "partial";

    /** 文档可能的状态，按一份文档经历的先后。 */
    static final List<String> DOCUMENT_STATUSES = List.of(QUEUED, RUNNING, SUCCEEDED, FAILED, UNROUTED, CANCELED);

    /**
     * 提交上来的一个文件。内容在存储里，这里只记它是谁。
     *
     * @param name   文件名
     * @param bytes  大小
     * @param sha256 内容的哈希。以后拿着结果回查"当时用的是不是这个文件"时用
     */
    record FileInfo(String name, long bytes, String sha256) {

        /**
         * 文件在平台里的 ID。由内容算出来（哈希的前 16 位），所以同一个文件不管提交几次、放在哪个任务里，ID 都相同；
         * 内容变了，ID 就变。出处里用它指明是哪一个文件。
         */
        String id() {
            return "f_" + sha256.substring(0, Math.min(16, sha256.length()));
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id());
            out.put("name", name);
            out.put("bytes", bytes);
            out.put("sha256", sha256);
            return out;
        }

        static FileInfo fromMap(Map<?, ?> map) {
            return new FileInfo(String.valueOf(map.get("name")), ((Number) map.get("bytes")).longValue(),
                    String.valueOf(map.get("sha256")));
        }
    }

    /**
     * 提交上来的一组文件。
     *
     * @param name  调用方给这份文档起的名字；没给时为 null
     * @param files 这一组里的文件
     */
    record Group(String name, List<FileInfo> files) {
    }

    /** 任务里的一份文档。 */
    static final class Doc {
        /** 在任务里的序号，从 0 开始。结果按它存取。 */
        final int index;
        /** 来自第几组文件。 */
        final int group;
        /** 是那一组文件读出来的第几份文档。一组文件通常只有一份。 */
        final int ordinal;
        final String name;
        final Integer pages;
        /**
         * 这份文档出自它那一组里的哪几个文件（文件名）。一组文件读出几份文档时，各份的文件不同。
         * 空的表示分不出来，这时把整组文件都算作它的。
         */
        final List<String> files;

        String status = QUEUED;
        /** 实际用的 skill：{@code {name, version}}。还没定下来时为 null。 */
        Map<String, Object> skill;
        /** 选 skill 的结论和依据。 */
        Map<String, Object> routing;
        /** 失败的原因。 */
        String error;
        /** 结果的概况（就是结果里的 summary）。只有成功的文档有。 */
        Map<String, Object> summary;
        /** 这份文档用了多少次模型调用、多少 token。失败、被取消的文档也记，花掉的就是花掉了。 */
        Usage usage;
        Instant startedAt;
        Instant finishedAt;
        /** 开始处理过几次。正常是 1；服务在处理中途重启过，重新排队之后再开始就会加一。 */
        int attempts;
        /** 正在处理这份文档的线程，取消时用来打断它。只在执行期间有值，不存下来。 */
        Thread worker;
        /** 已交给执行器的工作；取消排队文档时从队列移除，避免反复提交、取消后仍占着内存。 */
        Runnable queuedWork;

        Doc(int index, int group, int ordinal, String name, Integer pages, List<String> files) {
            this.index = index;
            this.group = group;
            this.ordinal = ordinal;
            this.name = name;
            this.pages = pages;
            this.files = List.copyOf(files);
        }

        Long elapsedMs() {
            return startedAt == null || finishedAt == null ? null
                    : finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }

    final String id;
    /** 提交时写的 skill：一个名称，或者 auto。 */
    final String skill;
    final ExtractionRequest.Options options;
    final String idempotencyKey;
    /** 提交内容的指纹：同一个幂等键再来一次时，用它判断是不是同一个请求。 */
    final String fingerprint;
    final Instant createdAt;
    final List<Group> groups;
    final List<Doc> documents;
    /** 有人要求取消。还在排队的文档不再执行，正在执行的被打断。 */
    boolean cancelRequested;
    /**
     * 这个任务用到的、已经准备好的抽取：skill 名称 → 抽取。同一个任务的几份文档共用一个，
     * 所以它们用的是同一版字段定义，也共用一个并发上限。不存下来，服务重启之后重新准备。
     */
    final Map<String, ExtractJob> jobs = new HashMap<>();

    ExtractionTask(String id, String skill, ExtractionRequest.Options options, String idempotencyKey,
                   String fingerprint, Instant createdAt, List<Group> groups, List<Doc> documents) {
        this.id = id;
        this.skill = skill;
        this.options = options;
        this.idempotencyKey = idempotencyKey;
        this.fingerprint = fingerprint;
        this.createdAt = createdAt;
        this.groups = List.copyOf(groups);
        this.documents = List.copyOf(documents);
    }

    boolean auto() {
        return ExtractionRequest.AUTO.equalsIgnoreCase(skill);
    }

    /** 状态是 {@code status} 的文档有几份。 */
    int count(String status) {
        int n = 0;
        for (Doc doc : documents) {
            if (doc.status.equals(status)) {
                n++;
            }
        }
        return n;
    }

    /** 每份文档都有了最终的状态。 */
    boolean finished() {
        return count(QUEUED) == 0 && count(RUNNING) == 0;
    }

    /**
     * 任务的状态，由文档的状态算出来：
     * <ul>
     *   <li>都还在排队是 {@code queued}；有在执行的，或者做完了一部分，是 {@code running}；</li>
     *   <li>全部结束之后：全成功是 {@code succeeded}；有被取消的是 {@code canceled}（取消之前已经抽完的文档，结果仍然可以取）；
     *       成功了一部分是 {@code partial}；一份都没成功时，全是没能确定 skill 的是 {@code unrouted}，否则是 {@code failed}。</li>
     * </ul>
     */
    String status() {
        int total = documents.size();
        int queued = count(QUEUED);
        if (queued == total) {
            return QUEUED;
        }
        if (queued > 0 || count(RUNNING) > 0) {
            return RUNNING;
        }
        int succeeded = count(SUCCEEDED);
        if (succeeded == total) {
            return SUCCEEDED;
        }
        if (count(CANCELED) > 0) {
            return CANCELED;
        }
        if (succeeded > 0) {
            return PARTIAL;
        }
        return count(UNROUTED) == total ? UNROUTED : FAILED;
    }

    /** 最后一份文档结束的时间；还没全部结束时为 null。 */
    Instant finishedAt() {
        if (!finished()) {
            return null;
        }
        Instant last = createdAt;
        for (Doc doc : documents) {
            if (doc.finishedAt != null && doc.finishedAt.isAfter(last)) {
                last = doc.finishedAt;
            }
        }
        return last;
    }

    private Instant startedAt() {
        Instant first = null;
        for (Doc doc : documents) {
            if (doc.startedAt != null && (first == null || doc.startedAt.isBefore(first))) {
                first = doc.startedAt;
            }
        }
        return first;
    }

    // ---- 给调用方看的样子 ----------------------------------------------------------------

    /**
     * 给调用方看的样子（HTTP 接口返回的就是它）。不含抽取结果，结果另外按文档的序号取。
     *
     * @param withDocuments 要不要带上每份文档的情况。列任务清单时不带
     */
    Map<String, Object> view(boolean withDocuments) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status());
        out.put("skill", skill);
        out.put("options", options.toMap());
        out.put("idempotencyKey", idempotencyKey);
        out.put("createdAt", text(createdAt));
        out.put("startedAt", text(startedAt()));
        out.put("finishedAt", text(finishedAt()));
        // 取消不是立刻完成的：正在处理的文档要等它停下来。这一项是 true 而状态还是 running，说明正在停
        out.put("cancelRequested", cancelRequested);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", documents.size());
        for (String status : DOCUMENT_STATUSES) {
            counts.put(status, count(status));
        }
        out.put("counts", counts);
        out.put("usage", usageMap(totalUsage()));
        if (!withDocuments) {
            return out;
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Doc doc : documents) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("index", doc.index);
            d.put("name", doc.name);
            d.put("status", doc.status);
            d.put("pageCount", doc.pages);
            d.put("files", filesOf(doc));
            d.put("skill", doc.skill);
            d.put("routing", doc.routing);
            d.put("error", doc.error);
            d.put("startedAt", text(doc.startedAt));
            d.put("finishedAt", text(doc.finishedAt));
            d.put("elapsedMs", doc.elapsedMs());
            d.put("usage", doc.usage == null ? null : usageMap(doc.usage));
            d.put("summary", doc.summary);
            docs.add(d);
        }
        out.put("documents", docs);
        return out;
    }

    /**
     * 一份文档是从哪几个文件读出来的。一组文件读出几份文档时，只算它自己的那几个；
     * 分不出来时（读取器没有记下每一页出自哪个文件），把整组文件都算作它的。
     */
    List<FileInfo> fileInfosOf(Doc doc) {
        List<FileInfo> all = groups.get(doc.group).files();
        if (doc.files.isEmpty()) {
            return all;
        }
        List<FileInfo> own = new ArrayList<>();
        for (FileInfo file : all) {
            if (doc.files.contains(file.name())) {
                own.add(file);
            }
        }
        return own.isEmpty() ? all : own;
    }

    /** {@link #fileInfosOf} 给调用方看的样子：每个文件的 ID、文件名、大小、内容的哈希。 */
    List<Map<String, Object>> filesOf(Doc doc) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (FileInfo file : fileInfosOf(doc)) {
            files.add(file.toMap());
        }
        return files;
    }

    /** 各份文档的模型用量加在一起。 */
    private Usage totalUsage() {
        long calls = 0;
        long prompt = 0;
        long completion = 0;
        for (Doc doc : documents) {
            if (doc.usage != null) {
                calls += doc.usage.calls();
                prompt += doc.usage.promptTokens();
                completion += doc.usage.completionTokens();
            }
        }
        return new Usage(calls, prompt, completion);
    }

    private static Map<String, Object> usageMap(Usage usage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llmCalls", usage.calls());
        out.put("promptTokens", usage.promptTokens());
        out.put("completionTokens", usage.completionTokens());
        return out;
    }

    // ---- 存下来和读回来 ------------------------------------------------------------------

    /** 存进任务记录里的样子。和 {@link #view} 的区别：没有算出来的东西，多了内部要用的几项。 */
    Map<String, Object> record() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("skill", skill);
        out.put("options", options.toMap());
        out.put("idempotencyKey", idempotencyKey);
        out.put("fingerprint", fingerprint);
        out.put("createdAt", text(createdAt));
        out.put("cancelRequested", cancelRequested);
        List<Map<String, Object>> groupMaps = new ArrayList<>();
        for (Group group : groups) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", group.name());
            List<Map<String, Object>> files = new ArrayList<>();
            for (FileInfo file : group.files()) {
                files.add(file.toMap());
            }
            g.put("files", files);
            groupMaps.add(g);
        }
        out.put("groups", groupMaps);
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Doc doc : documents) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("index", doc.index);
            d.put("group", doc.group);
            d.put("ordinal", doc.ordinal);
            d.put("name", doc.name);
            d.put("pages", doc.pages);
            d.put("files", doc.files);
            d.put("status", doc.status);
            d.put("skill", doc.skill);
            d.put("routing", doc.routing);
            d.put("error", doc.error);
            d.put("summary", doc.summary);
            d.put("usage", doc.usage == null ? null : usageMap(doc.usage));
            d.put("startedAt", text(doc.startedAt));
            d.put("finishedAt", text(doc.finishedAt));
            d.put("attempts", doc.attempts);
            docs.add(d);
        }
        out.put("documents", docs);
        return out;
    }

    /**
     * {@link #record()} 的逆过程。
     *
     * @throws RuntimeException 记录不是这个样子的（文件被改过，或者是别的版本写的）
     */
    static ExtractionTask fromRecord(Map<?, ?> map) {
        List<Group> groups = new ArrayList<>();
        for (Object raw : (List<?>) map.get("groups")) {
            Map<?, ?> g = (Map<?, ?>) raw;
            List<FileInfo> files = new ArrayList<>();
            for (Object file : (List<?>) g.get("files")) {
                files.add(FileInfo.fromMap((Map<?, ?>) file));
            }
            groups.add(new Group((String) g.get("name"), files));
        }
        List<Doc> docs = new ArrayList<>();
        for (Object raw : (List<?>) map.get("documents")) {
            Map<?, ?> d = (Map<?, ?>) raw;
            List<String> fileNames = new ArrayList<>();
            if (d.get("files") instanceof List<?> names) {
                for (Object name : names) {
                    fileNames.add(String.valueOf(name));
                }
            }
            Doc doc = new Doc(integer(d.get("index")), integer(d.get("group")), integer(d.get("ordinal")),
                    (String) d.get("name"), d.get("pages") == null ? null : integer(d.get("pages")), fileNames);
            doc.status = String.valueOf(d.get("status"));
            doc.skill = stringMap(d.get("skill"));
            doc.routing = stringMap(d.get("routing"));
            doc.error = (String) d.get("error");
            doc.summary = stringMap(d.get("summary"));
            if (d.get("usage") instanceof Map<?, ?> usage) {
                doc.usage = new Usage(number(usage.get("llmCalls")), number(usage.get("promptTokens")),
                        number(usage.get("completionTokens")));
            }
            doc.startedAt = instant(d.get("startedAt"));
            doc.finishedAt = instant(d.get("finishedAt"));
            doc.attempts = (int) number(d.get("attempts"));
            if (doc.group < 0 || doc.group >= groups.size() || doc.index != docs.size()
                    || !DOCUMENT_STATUSES.contains(doc.status)) {
                throw new IllegalArgumentException("第 " + docs.size() + " 份文档的记录不对");
            }
            docs.add(doc);
        }
        ExtractionTask task = new ExtractionTask((String) map.get("id"), (String) map.get("skill"),
                ExtractionRequest.Options.fromMap((Map<?, ?>) map.get("options")),
                (String) map.get("idempotencyKey"), (String) map.get("fingerprint"),
                instant(map.get("createdAt")), groups, docs);
        task.cancelRequested = Boolean.TRUE.equals(map.get("cancelRequested"));
        if (task.id == null || task.skill == null || task.createdAt == null || docs.isEmpty()) {
            throw new IllegalArgumentException("记录不完整");
        }
        return task;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(String.valueOf(value));
    }

    private static int integer(Object value) {
        return ((Number) value).intValue();
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object value) {
        return value == null ? null : (Map<String, Object>) value;
    }
}
