package com.cb.agent.app.extract;

import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.model.PageInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出处的"文件"那一层：一个值的证据在哪个文件上。对应方案第 8.2 节三层出处里最外面的一层。
 *
 * <p>抽取引擎给出的出处只有后两层（证据在第几页的哪个框）。它不知道文件是谁：文件的 ID 和哈希是存储那一层的事，
 * 引擎在命令行下也要能用。所以这一层在这里补：任务服务知道每个文件的哈希，文档知道每一页出自哪个文件
 * （{@link PageInfo#file()}），两样合起来就知道一个位置落在哪个文件上。
 *
 * <p>补上之后结果里是这样：
 * <pre>
 *   "files": [{"id": "f_3b1c…", "name": "form_0_res.json", "bytes": 15029, "sha256": "3b1c…", "pages": [1]}, …]
 *   "fields": {"姓名": {…, "locator": {"file_id": "f_3b1c…", "file_hash": "sha256:3b1c…", "origin": "OCR", …}}}
 * </pre>
 *
 * <p><b>这里的"文件"是提交上来的识别结果，不是扫描件或 PDF 原件。</b>原件现在还不经过平台
 * （要到读取 PDF、图片的阶段），所以哈希能回答的是"这个值是从哪一份识别结果里抽出来的"。
 *
 * <p>一条证据跨了页、而且两页各是一个文件时，{@code file_id} 给的是证据开头那一页的文件；
 * 其余各页的文件可以按 {@code evidence_regions} 里的页码到 {@code files[].pages} 里查。
 */
final class FileTrace {

    private FileTrace() {
    }

    /**
     * 一份文档出自哪几个文件（文件名），按页的先后。
     *
     * @return 读取器没有记下来时（识别结果里没有页码）是空列表
     */
    static List<String> fileNames(Document doc) {
        Set<String> names = new LinkedHashSet<>();
        for (PageInfo page : doc.pages()) {
            if (page.file() != null) {
                names.add(page.file());
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * 结果里的文件清单：每个文件的 ID、文件名、大小、哈希，以及文档的哪几页出自它。
     *
     * @param files 这份文档的文件
     * @param doc   读出来的文档
     */
    static List<Map<String, Object>> files(List<ExtractionTask.FileInfo> files, Document doc) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExtractionTask.FileInfo file : files) {
            Map<String, Object> map = file.toMap();
            List<Integer> pages = new ArrayList<>();
            for (PageInfo page : doc.pages()) {
                if (file.name().equals(page.file())) {
                    pages.add(page.number());
                }
            }
            map.put("pages", pages);
            out.add(map);
        }
        return out;
    }

    /**
     * 给结果里每个值的出处补上 {@code file_id} 和 {@code file_hash}。直接改传进来的结构。
     *
     * @param fields 结果里的 {@code fields}：字段名 → 这个字段的记录
     * @param files  这份文档的文件
     * @param doc    读出来的文档
     */
    static void stamp(Object fields, List<ExtractionTask.FileInfo> files, Document doc) {
        if (!(fields instanceof Map<?, ?> byName)) {
            return;
        }
        for (Object field : byName.values()) {
            if (!(field instanceof Map<?, ?> record)) {
                continue;
            }
            // 标量字段的出处直接摊在字段下面；列表字段每一项各有一个；另外还有"别的可能的值"
            stampOne(record, files, doc);
            stampEach(record.get("items"), files, doc);
            stampEach(record.get("alternatives"), files, doc);
        }
    }

    private static void stampEach(Object items, List<ExtractionTask.FileInfo> files, Document doc) {
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    stampOne(map, files, doc);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void stampOne(Map<?, ?> holder, List<ExtractionTask.FileInfo> files, Document doc) {
        if (!(holder.get("locator") instanceof Map<?, ?> locator)) {
            return;
        }
        ExtractionTask.FileInfo file = fileOf(locator, files, doc);
        // 文件那一层放在最前面：由粗到细，先说是哪个文件，再说在文件的哪里
        Map<String, Object> stamped = new LinkedHashMap<>();
        stamped.put("file_id", file == null ? null : file.id());
        stamped.put("file_hash", file == null ? null : "sha256:" + file.sha256());
        locator.forEach((key, value) -> stamped.put(String.valueOf(key), value));
        ((Map<String, Object>) holder).put("locator", stamped);
    }

    /** 这个出处落在哪个文件上；分不出来时返回 null。 */
    private static ExtractionTask.FileInfo fileOf(Map<?, ?> locator, List<ExtractionTask.FileInfo> files,
                                                  Document doc) {
        if (files.size() == 1) {
            return files.get(0); // 只有一个文件，不用看页码
        }
        if (locator.get("evidence_regions") instanceof List<?> regions) {
            for (Object region : regions) {
                if (region instanceof Map<?, ?> map && map.get("page") instanceof Integer number) {
                    PageInfo page = doc.page(number);
                    if (page != null && page.file() != null) {
                        for (ExtractionTask.FileInfo file : files) {
                            if (file.name().equals(page.file())) {
                                return file;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
