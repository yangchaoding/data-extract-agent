package com.cb.agent.app.extract;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.document.InputGroup;
import com.cb.agent.capability.document.DocumentLoader;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import com.cb.agent.core.llm.Usage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务这份数据本身：状态怎么由文档的状态算出来，存下来再读回来是不是一样；
 * 以及出处的"文件"那一层（{@link FileTrace}）在一份文档由几个文件组成时怎么分。
 */
class ExtractionTaskTest {

    private static final Path CONTRACT = Path.of("src/test/resources/fixtures/paddle/sample_contract");

    // ---- 任务的状态 ----------------------------------------------------------------------

    @Test
    void 任务的状态由文档的状态算出来() {
        assertEquals("queued", task("queued", "queued").status());
        assertEquals("running", task("running", "queued").status());
        assertEquals("running", task("succeeded", "queued").status(), "做完了一部分，还有在排队的：还在进行");
        assertEquals("succeeded", task("succeeded", "succeeded").status());
        assertEquals("partial", task("succeeded", "failed").status());
        assertEquals("partial", task("succeeded", "unrouted").status());
        assertEquals("failed", task("failed", "failed").status());
        assertEquals("failed", task("failed", "unrouted").status(), "一份都没成功，而且不全是没选出 skill");
        assertEquals("unrouted", task("unrouted", "unrouted").status());
        assertEquals("canceled", task("succeeded", "canceled").status(), "有被取消的就是已取消，已经抽完的结果仍然可以取");
        assertEquals("canceled", task("failed", "canceled").status());
    }

    @Test
    void 结束时间是最后一份文档结束的时间_没全部结束时没有() {
        ExtractionTask running = task("succeeded", "running");
        running.documents.get(0).finishedAt = Instant.parse("2026-11-13T01:00:00Z");
        assertFalse(running.finished());
        assertNull(running.finishedAt());
        assertNull(running.view(false).get("finishedAt"));

        ExtractionTask done = task("succeeded", "failed");
        done.documents.get(0).finishedAt = Instant.parse("2026-11-13T01:00:00Z");
        done.documents.get(1).finishedAt = Instant.parse("2026-11-13T01:00:05Z");
        assertTrue(done.finished());
        assertEquals(Instant.parse("2026-11-13T01:00:05Z"), done.finishedAt());
    }

    // ---- 存下来和读回来 ------------------------------------------------------------------

    @Test
    void 存下来再读回来是一样的() {
        ExtractionTask task = task("succeeded", "failed");
        task.cancelRequested = true;
        ExtractionTask.Doc first = task.documents.get(0);
        first.skill = new LinkedHashMap<>(Map.of("name", "personnel-appointment", "version", 1));
        first.routing = new LinkedHashMap<>(Map.of("method", "rule"));
        first.summary = new LinkedHashMap<>(Map.of("ok", 22));
        first.usage = new Usage(4, 400, 40);
        first.startedAt = Instant.parse("2026-11-13T01:00:00Z");
        first.finishedAt = Instant.parse("2026-11-13T01:00:03.250Z");
        first.attempts = 2;
        task.documents.get(1).error = "模型接口调用失败：连不上";

        ExtractionTask back = ExtractionTask.fromRecord(task.record());

        assertEquals(task.record(), back.record());
        assertEquals(task.view(true), back.view(true));
        assertEquals("fp", back.fingerprint);
        assertEquals(2, back.documents.get(0).attempts);
        assertEquals(3250L, (long) back.documents.get(0).elapsedMs());
        assertEquals(List.of("a.json"), back.documents.get(0).files);
        assertEquals(new ExtractionRequest.Options(null, null, 3, null, false), back.options);
        assertTrue(back.jobs.isEmpty(), "准备好的抽取不存下来，重启之后重新准备");
    }

    @Test
    void 没记文件的旧记录也读得进来_整组文件都算作这份文档的() {
        ExtractionTask task = task("succeeded", "succeeded");
        Map<String, Object> record = task.record();
        for (Object doc : (List<?>) record.get("documents")) {
            ((Map<?, ?>) doc).remove("files");
        }

        ExtractionTask back = ExtractionTask.fromRecord(record);

        assertEquals(List.of(), back.documents.get(0).files);
        assertEquals(List.of("a.json", "b.json"),
                back.fileInfosOf(back.documents.get(0)).stream().map(ExtractionTask.FileInfo::name).toList());
    }

    @Test
    void 记录不对的读不进来() {
        Map<String, Object> noDocs = task("queued").record();
        noDocs.put("documents", List.of());
        assertEquals("记录不完整",
                assertThrows(IllegalArgumentException.class, () -> ExtractionTask.fromRecord(noDocs)).getMessage());

        Map<String, Object> badStatus = task("queued").record();
        ((Map<String, Object>) ((List<?>) badStatus.get("documents")).get(0)).put("status", "done");
        assertEquals("第 0 份文档的记录不对",
                assertThrows(IllegalArgumentException.class, () -> ExtractionTask.fromRecord(badStatus)).getMessage());

        assertThrows(RuntimeException.class, () -> ExtractionTask.fromRecord(Map.of("id", "ex1")));
    }

    @Test
    void 文件的ID由内容算出来() {
        ExtractionTask.FileInfo file = new ExtractionTask.FileInfo("a.json", 10, "3b68ed9e38b4646181e8160f1070432010");
        assertEquals("f_3b68ed9e38b46461", file.id());
        assertEquals(List.of("id", "name", "bytes", "sha256"), new ArrayList<>(file.toMap().keySet()));
        assertEquals(file, ExtractionTask.FileInfo.fromMap(file.toMap()));
    }

    // ---- 出处的文件那一层 -----------------------------------------------------------------

    @Test
    void 一份文档有几个文件时_按证据所在的页找到文件() {
        Document contract = contract();
        List<ExtractionTask.FileInfo> files = List.of(
                new ExtractionTask.FileInfo("sample_contract_0_res.json", 100, "aaaaaaaaaaaaaaaaaaaa"),
                new ExtractionTask.FileInfo("sample_contract_1_res.json", 200, "bbbbbbbbbbbbbbbbbbbb"),
                new ExtractionTask.FileInfo("sample_contract_2_res.json", 300, "cccccccccccccccccccc"));

        assertEquals(List.of("sample_contract_0_res.json", "sample_contract_1_res.json", "sample_contract_2_res.json"),
                FileTrace.fileNames(contract));
        List<Map<String, Object>> listed = FileTrace.files(files, contract);
        assertEquals(List.of(List.of(1), List.of(2), List.of(3)), listed.stream().map(f -> f.get("pages")).toList());
        assertEquals("f_bbbbbbbbbbbbbbbb", listed.get(1).get("id"));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("甲方", field(locator(1)));                       // 标量字段：出处直接在字段下面
        fields.put("付款", new LinkedHashMap<>(Map.of(                // 列表字段：每一项各有一个
                "items", List.of(field(locator(2)), field(locator(3))))));
        fields.put("金额", new LinkedHashMap<>(Map.of("locator", locator(3),
                "alternatives", List.of(field(locator(1))))));        // 别的可能的值也有出处
        fields.put("跨页", field(locator(2, 3)));                     // 证据跨了两页：算开头那一页的文件
        fields.put("没找到", new LinkedHashMap<>(Map.of("status", "missing"))); // 没有出处的不动
        fields.put("没有页码", field(new LinkedHashMap<>(Map.of("origin", "OCR", "evidence_regions", List.of()))));

        FileTrace.stamp(fields, files, contract);

        assertEquals("f_aaaaaaaaaaaaaaaa", locatorOf(fields.get("甲方")).get("file_id"));
        assertEquals("sha256:aaaaaaaaaaaaaaaaaaaa", locatorOf(fields.get("甲方")).get("file_hash"));
        assertEquals(List.of("file_id", "file_hash", "origin", "evidence_regions"),
                new ArrayList<>(locatorOf(fields.get("甲方")).keySet()), "文件那一层放在最前面，由粗到细");
        List<?> items = (List<?>) ((Map<?, ?>) fields.get("付款")).get("items");
        assertEquals("f_bbbbbbbbbbbbbbbb", locatorOf(items.get(0)).get("file_id"));
        assertEquals("f_cccccccccccccccc", locatorOf(items.get(1)).get("file_id"));
        assertEquals("f_cccccccccccccccc", locatorOf(fields.get("金额")).get("file_id"));
        List<?> alternatives = (List<?>) ((Map<?, ?>) fields.get("金额")).get("alternatives");
        assertEquals("f_aaaaaaaaaaaaaaaa", locatorOf(alternatives.get(0)).get("file_id"));
        assertEquals("f_bbbbbbbbbbbbbbbb", locatorOf(fields.get("跨页")).get("file_id"));
        assertEquals(Map.of("status", "missing"), fields.get("没找到"));
        // 分不出是哪个文件：两项都在，值是 null，不乱猜
        assertTrue(locatorOf(fields.get("没有页码")).containsKey("file_id"));
        assertNull(locatorOf(fields.get("没有页码")).get("file_id"));
        assertNull(locatorOf(fields.get("没有页码")).get("file_hash"));
    }

    @Test
    void 只有一个文件时不用看页码() {
        List<ExtractionTask.FileInfo> one = List.of(new ExtractionTask.FileInfo("x.json", 1, "dddddddddddddddddddd"));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("甲方", field(new LinkedHashMap<>(Map.of("origin", "OCR", "evidence_regions", List.of()))));

        FileTrace.stamp(fields, one, contract());

        assertEquals("f_dddddddddddddddd", locatorOf(fields.get("甲方")).get("file_id"));
        FileTrace.stamp(null, one, contract()); // 结果里没有 fields 时什么都不做，也不报错
    }

    // ---- 小工具 -------------------------------------------------------------------------

    /** 一个任务：一组文件（a.json、b.json），几份文档，状态依次是给的这些。 */
    private static ExtractionTask task(String... statuses) {
        List<ExtractionTask.Doc> docs = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            ExtractionTask.Doc doc = new ExtractionTask.Doc(i, 0, i, "文档" + i, 2, List.of(i == 0 ? "a.json" : "b.json"));
            doc.status = statuses[i];
            docs.add(doc);
        }
        ExtractionTask.Group group = new ExtractionTask.Group("一组", List.of(
                new ExtractionTask.FileInfo("a.json", 10, "1111111111111111aaaa"),
                new ExtractionTask.FileInfo("b.json", 20, "2222222222222222bbbb")));
        return new ExtractionTask("ex1", "auto", new ExtractionRequest.Options(null, null, 3, null, false), "key-1",
                "fp", Instant.parse("2026-11-13T00:59:00Z"), List.of(group), docs);
    }

    /** 测试用的合同：三页各一个文件，用真的读取器读出来。 */
    private static Document contract() {
        try (Stream<Path> paths = Files.list(CONTRACT)) {
            List<InputGroup.InputFile> files = new ArrayList<>();
            for (Path path : paths.sorted().toList()) {
                files.add(new InputGroup.InputFile(path.getFileName().toString(), Files.readAllBytes(path)));
            }
            return new DocumentReadService(new DocumentLoader(List.of(new PaddleOcrVlJsonReader())))
                    .read(new InputGroup(null, files)).get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一个出处：证据在这几页上。 */
    private static Map<String, Object> locator(Integer... pages) {
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Integer page : Arrays.asList(pages)) {
            regions.add(new LinkedHashMap<>(Map.of("page", page, "granularity", "BLOCK")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("origin", "OCR");
        out.put("evidence_regions", regions);
        return out;
    }

    private static Map<String, Object> field(Map<String, Object> locator) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("locator", locator);
        return out;
    }

    private static Map<?, ?> locatorOf(Object holder) {
        return (Map<?, ?>) ((Map<?, ?>) holder).get("locator");
    }
}
