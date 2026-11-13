package com.cb.agent.api.http;

import com.cb.agent.app.document.InputGroup;
import com.cb.agent.app.document.UploadLimits;
import com.cb.agent.app.extract.ExtractionRequest;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把 HTTP 请求里的东西读成应用层认识的样子：一组一组的文件、引擎参数。
 *
 * <p>提交抽取、看切片、选 skill 这三个接口收的文档写法相同，都在这里读。文档有两种给法：
 * <ul>
 *   <li>JSON：{@code documents} 是一个列表，每一项是一份文档 {@code {"name": 文档名, "files": [文件…]}}；
 *       每个文件是 {@code {"name": 文件名, "content": base64}}，或者 {@code {"name": 文件名, "json": 识别结果}}
 *       （识别结果直接嵌在请求里，不用先转成 base64）。</li>
 *   <li>multipart 表单：文件用 {@code files} 这个名字上传，可以有多个，它们算一组，相当于命令行里给了一个目录。</li>
 * </ul>
 *
 * <p>不认识的键一律报错，不悄悄忽略：调用方写了 {@code callback} 却没有收到回调，比当场被告知"还不支持"难查得多。
 * 这里抛出的都是 {@link IllegalArgumentException}，消息是写给调用方看的，由 {@link ApiExceptionHandler} 回成 400。
 */
final class ApiRequests {

    private static final Set<String> DOCUMENT_KEYS = Set.of("name", "format", "files");
    private static final Set<String> FILE_KEYS = Set.of("name", "content", "json");
    private static final Set<String> OPTION_KEYS = Set.of("chunkTokens", "contextTokens", "topK", "maxRounds", "route");
    /** 现在唯一能读的格式。{@code format} 可以不写，写了就只能是这两个之一。 */
    private static final Set<String> FORMATS = Set.of("auto", "paddle-ocr-vl-json");

    private ApiRequests() {
    }

    // ---- JSON ---------------------------------------------------------------------------

    /**
     * 确认请求体是一个对象，而且只有认识的键。
     *
     * @param known 这个接口认识的键
     */
    static void object(JsonNode body, Set<String> known) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体应该是一个 JSON 对象");
        }
        onlyKeys(body, known, "请求");
    }

    /** 请求体里的 {@code documents}；先检查组数、文件数，文件在解码时计入大小限额。 */
    static List<InputGroup> documents(JsonNode body, UploadLimits limits) {
        JsonNode list = body.get("documents");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new IllegalArgumentException("documents 应该是一个列表，至少有一份文档");
        }
        limits.checkDocumentCount(list.size());
        long count = 0;
        for (int g = 0; g < list.size(); g++) {
            count += documentFiles(list.get(g), g).size();
            limits.checkFileCount(count);
        }
        UploadBudget budget = new UploadBudget(limits);
        List<InputGroup> groups = new ArrayList<>();
        for (int g = 0; g < list.size(); g++) {
            groups.add(document(list.get(g), g, budget));
        }
        return groups;
    }

    /** 取出一组的文件列表，检查结构；数量预检和实际解码共用，保证报错一致。 */
    private static JsonNode documentFiles(JsonNode node, int g) {
        String where = "documents[" + g + "]";
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + " 应该是一个对象：{\"name\": 文档名, \"files\": [文件…]}");
        }
        onlyKeys(node, DOCUMENT_KEYS, where);
        String format = text(node, "format", where);
        if (format != null && !FORMATS.contains(format)) {
            throw new IllegalArgumentException(where + ".format 写的是 " + format
                    + "。现在只能读 PaddleOCR-VL 的识别结果（JSON），这一项可以不写，或者写 auto");
        }
        JsonNode files = node.get("files");
        if (files == null || !files.isArray() || files.isEmpty()) {
            throw new IllegalArgumentException(where + ".files 应该是一个列表，至少有一个文件");
        }
        return files;
    }

    private static InputGroup document(JsonNode node, int g, UploadBudget budget) {
        JsonNode files = documentFiles(node, g);
        String where = "documents[" + g + "]";
        String name = documentName(text(node, "name", where));
        List<InputGroup.InputFile> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            out.add(file(files.get(i), where + ".files[" + i + "]", fallbackName(g, i, files.size()), budget));
        }
        return new InputGroup(name, out);
    }

    private static InputGroup.InputFile file(JsonNode node, String where, String fallback, UploadBudget budget) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + " 应该是一个对象：{\"name\": 文件名, \"content\": base64}，"
                    + "或者 {\"name\": 文件名, \"json\": 识别结果}");
        }
        onlyKeys(node, FILE_KEYS, where);
        String name = InputGroup.safeName(text(node, "name", where), fallback);
        JsonNode content = node.get("content");
        JsonNode inline = node.get("json");
        boolean hasContent = content != null && !content.isNull();
        boolean hasInline = inline != null && !inline.isNull();
        if (hasContent == hasInline) {
            throw new IllegalArgumentException(where + " 要给出文件的内容：content（base64）和 json（直接嵌进来的识别结果）给一个，"
                    + "不能都给，也不能都不给");
        }
        byte[] bytes;
        if (hasContent) {
            if (!content.isString()) {
                throw new IllegalArgumentException(where + ".content 应该是 base64 编码的字符串");
            }
            // base64 解码后的大小由字符数和尾部的填充算得出，不需要先分配那个字节数组。
            // 编码器可能每 76 个字符换行，先把空白去掉；不带尾部填充的合法写法也能算对。
            String encoded = content.stringValue().replaceAll("\\s", "");
            int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
            long decodedBytes = (long) encoded.length() * 6 / 8 - padding;
            if (encoded.length() % 4 == 1 || decodedBytes < 0) {
                throw new IllegalArgumentException(where + ".content 不是合法的 base64");
            }
            budget.add(name, decodedBytes);
            try {
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".content 不是合法的 base64");
            }
        } else {
            if (!inline.isObject() && !inline.isArray()) {
                throw new IllegalArgumentException(where + ".json 应该是识别结果本身（一个对象或数组），不是字符串");
            }
            bytes = inline.toString().getBytes(StandardCharsets.UTF_8);
            budget.add(name, bytes.length);
        }
        return new InputGroup.InputFile(name, bytes);
    }

    /** 请求体里的 {@code options}；没写时是 {@link ExtractionRequest.Options#NONE}。 */
    static ExtractionRequest.Options options(JsonNode body) {
        JsonNode node = body.get("options");
        if (node == null || node.isNull()) {
            return ExtractionRequest.Options.NONE;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("options 应该是一个对象，例如 {\"topK\": 5}");
        }
        onlyKeys(node, OPTION_KEYS, "options");
        return new ExtractionRequest.Options(integer(node, "chunkTokens", "options"),
                integer(node, "contextTokens", "options"), integer(node, "topK", "options"),
                integer(node, "maxRounds", "options"), bool(node, "route", "options"));
    }

    /** 一个文字值；没写或者是 null 时返回 null。 */
    static String text(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(where + "." + key + " 应该是字符串");
        }
        return value.stringValue();
    }

    /** 一个整数值；没写或者是 null 时返回 null。 */
    static Integer integer(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(where + "." + key + " 应该是整数，现在是 " + value);
        }
        return value.intValue();
    }

    /** 一个布尔值；没写或者是 null 时返回 null。 */
    static Boolean bool(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(where + "." + key + " 应该是 true 或 false，现在是 " + value);
        }
        return value.booleanValue();
    }

    private static void onlyKeys(JsonNode node, Set<String> known, String where) {
        Set<String> unknown = new TreeSet<>(node.propertyNames());
        unknown.removeAll(known);
        if (unknown.isEmpty()) {
            return;
        }
        if (unknown.contains("callback")) {
            throw new IllegalArgumentException("callback（做完之后回调）还没有实现。提交之后用返回的任务 ID 轮询");
        }
        throw new IllegalArgumentException(where + "里有不认识的键：" + String.join("、", unknown)
                + "（认识的有：" + String.join("、", new TreeSet<>(known)) + "）");
    }

    // ---- multipart 表单 ------------------------------------------------------------------

    /**
     * 确认表单里只有认识的项（上传的文件不算在内）。
     */
    static void form(Map<String, String> form, Set<String> known) {
        Set<String> unknown = new TreeSet<>(form.keySet());
        unknown.removeAll(known);
        if (unknown.isEmpty()) {
            return;
        }
        if (unknown.contains("files")) {
            throw new IllegalArgumentException("files 应该是上传的文件，不是一段文字。用 curl 时写成 -F \"files=@文件路径\"");
        }
        throw new IllegalArgumentException("表单里有不认识的项：" + String.join("、", unknown)
                + "（认识的有：files、" + String.join("、", new TreeSet<>(known)) + "）");
    }

    /**
     * 表单上传的文件。它们算一组，相当于命令行里给了一个目录：同一份文档的几页合成一份，不同文档的分开。
     *
     * @param name 文档名；可以为 null
     * @param limits 上传上限；所有文件的元数据先检查一遍，再读取内容
     */
    static List<InputGroup> documents(List<MultipartFile> files, String name, UploadLimits limits) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("没有上传文件。文件用 files 这个名字上传，可以有多个");
        }
        String documentName = documentName(name);
        limits.checkFileCount(files.size());
        UploadBudget budget = new UploadBudget(limits);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String fileName = InputGroup.safeName(file.getOriginalFilename(), fallbackName(0, i, files.size()));
            names.add(fileName);
            budget.add(fileName, file.getSize());
        }
        List<InputGroup.InputFile> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            try {
                out.add(new InputGroup.InputFile(names.get(i), file.getBytes()));
            } catch (IOException e) {
                throw new IllegalArgumentException("上传的第 " + (i + 1) + " 个文件读不了：" + e.getMessage(), e);
            }
        }
        List<InputGroup> groups = List.of(new InputGroup(documentName, out));
        limits.check(groups); // 元数据检查挡住大多数错误，再按实际读到的大小核对，不能信任内容与元数据永远一致
        return groups;
    }

    /** 表单里的引擎参数。 */
    static ExtractionRequest.Options options(Map<String, String> form) {
        return new ExtractionRequest.Options(integer(form, "chunkTokens"), integer(form, "contextTokens"),
                integer(form, "topK"), integer(form, "maxRounds"), bool(form, "route"));
    }

    /** 表单里的一个整数；没写或者是空的返回 null。 */
    static Integer integer(Map<String, String> form, String key) {
        String value = blankToNull(form.get(key));
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 应该是整数，现在是 " + value);
        }
    }

    /** 表单里的一个开关；没写或者是空的返回 null。 */
    static Boolean bool(Map<String, String> form, String key) {
        String value = blankToNull(form.get(key));
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(key + " 应该是 true 或 false，现在是 " + value);
        };
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ---- 两种给法共用 --------------------------------------------------------------------

    /** 调用方给文档起的名字。它会出现在结果里，不会被当成路径，所以只限制长度，不限制用什么字。 */
    private static String documentName(String raw) {
        String name = blankToNull(raw);
        if (name != null && name.length() > 200) {
            throw new IllegalArgumentException("文档名太长了（" + name.length() + " 个字），最多 200 个字");
        }
        return name;
    }

    /** 文件没给名字时用的名字。 */
    private static String fallbackName(int group, int index, int count) {
        return "document" + (group + 1) + (count > 1 ? "_" + index : "") + ".json";
    }

    /** 每个请求的文件大小累计器，JSON 与 multipart 都使用 UploadLimits 的同一套限制。 */
    private static final class UploadBudget {
        private final UploadLimits limits;
        private long total;

        private UploadBudget(UploadLimits limits) {
            this.limits = limits;
        }

        private void add(String name, long bytes) {
            limits.checkFileSize(name, bytes);
            total = limits.addBytes(total, bytes);
        }
    }
}
