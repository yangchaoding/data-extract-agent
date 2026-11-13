package com.cb.agent.capability.document.reader.paddle;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.BlockKind;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.model.IngestReport;
import com.cb.agent.capability.document.model.LeadIn;
import com.cb.agent.capability.document.model.PageInfo;
import com.cb.agent.capability.document.model.PixelBox;
import com.cb.agent.capability.document.model.Table;
import com.cb.agent.capability.document.reader.DocumentReader;
import com.cb.agent.capability.document.reader.HeadingLeveler;
import com.cb.agent.capability.document.reader.PlainText;
import com.cb.agent.capability.document.table.TableParser;
import com.cb.agent.capability.document.text.TextNorm;
import com.cb.agent.core.trace.Box;
import com.cb.agent.core.trace.Granularity;
import com.cb.agent.core.trace.Region;
import com.cb.agent.core.trace.TextOrigin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 读取 PaddleOCR-VL（v1 / v1.5 / v1.6）的识别结果 JSON，转成统一的 {@link Document}。
 *
 * <p>对应 Python 版 {@code loader.py} 里和 JSON 有关的部分。认这几种输入：
 * <ul>
 *   <li>{@code save_to_json()} 保存的文件，每页一个 {@code {文件名}_{页码}_res.json}；</li>
 *   <li>服务接口的返回：{@code result.layoutParsingResults[].prunedResult}，可以是一个，也可以是每页一个组成的数组；</li>
 *   <li>{@code restructure_pages(concatenate_pages=True)} 的合并结果：所有页合成一个，块上没有页码；</li>
 *   <li>放着上面这些文件的目录。目录里可以有多份文档，按原始文件名分组。</li>
 * </ul>
 *
 * <p>读取时做的事：把版面类别归一化；按编号样式推断标题层级；同样的页眉页脚只留第一次出现；
 * 把跨页的表格拼成一张。
 *
 * <p>和 Python 版不同的三处，都是在真实的 1.6 输出上实测发现问题之后改的：
 * <ol>
 *   <li><b>跨页的表格记住每一行来自哪一页、哪个框。</b>Python 版拼接之后只留第一页的框，
 *       第二页的行也带着第一页的坐标，拿去原件上画框位置是错的。</li>
 *   <li><b>表格单元格里字面的 {@code \n} 换成真正的换行。</b>PaddleOCR-VL 用反斜杠加 n 两个字符
 *       表示单元格里的换行。</li>
 *   <li><b>文档最前面的图题当作文档标题。</b>版面模型有时把文档标题判成图题（{@code figure_title}）。</li>
 * </ol>
 * 后两处都会在读取报告里记一笔。
 */
public final class PaddleOcrVlJsonReader implements DocumentReader {

    public static final String NAME = "paddle-ocr-vl-json";

    // ---- PaddleOCR-VL 的版面类别 → 归一化的类别 -------------------------------------------

    private static final Set<String> HEADING_LABELS =
            Set.of("doc_title", "paragraph_title", "abstract_title", "reference_title", "content_title");
    private static final Set<String> CAPTION_LABELS = Set.of("table_title", "figure_title", "chart_title");
    private static final Set<String> FOOTNOTE_LABELS = Set.of("footnote", "vision_footnote");
    private static final Set<String> FORMULA_LABELS = Set.of("formula", "display_formula", "inline_formula");
    /** 页眉、页脚、侧边文字：同样的内容每页都有，只留第一次出现。 */
    private static final Set<String> MARGIN_LABELS = Set.of("header", "footer", "aside_text");
    /** 页码、公式编号、页眉页脚里的图：对抽取没有用，直接跳过。 */
    private static final Set<String> SKIP_LABELS = Set.of("number", "formula_number", "header_image", "footer_image");

    // ---- 用到的正则 ---------------------------------------------------------------------

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** "续表"字样：下一页表格上方常见的提示。 */
    private static final Pattern CONTINUED = Pattern.compile("续上?表|[（(]\\s*续\\s*[)）]|^续$|continued|cont['’‘]d", FLAGS);
    /** Markdown 的标题写法：开头的一到六个 #。 */
    private static final Pattern HASHES = Pattern.compile("^\\s*(#{1,6})\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DIGITS = Pattern.compile("\\d+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WORD_CHAR = Pattern.compile("\\w", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern RES_SUFFIX = Pattern.compile("_res$");
    private static final Pattern NUMBER_RUN = Pattern.compile("(\\d+)");

    /** 字面的反斜杠加 n：PaddleOCR-VL 在表格单元格里用它表示换行。 */
    private static final String LITERAL_NEWLINE = "\\n";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(Path path) {
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                return files.anyMatch(PaddleOcrVlJsonReader::isJsonFile);
            } catch (IOException e) {
                return false;
            }
        }
        return isJsonFile(path);
    }

    private static boolean isJsonFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /**
     * 读一个文件或一个目录。对应 {@code load_documents}。
     *
     * <p>一个文件算一份文档，文档名取自文件名（去掉末尾的 {@code _res}）。
     * 目录里的文件按原始文件名分组，每组是一份文档，见 {@link #readDirectory}。
     */
    @Override
    public List<Document> read(Path path) {
        if (Files.isDirectory(path)) {
            List<Document> docs = readDirectory(path);
            if (docs.isEmpty()) {
                throw new IllegalArgumentException("目录里没有找到 PaddleOCR-VL 的 JSON 结果: " + path);
            }
            return docs;
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("找不到输入: " + path);
        }
        String fileName = path.getFileName().toString();
        String name = RES_SUFFIX.matcher(stem(fileName)).replaceFirst("");
        List<JsonNode> pages = unwrapPages(parse(path));
        // 这个文件里的每一页都出自它自己
        return List.of(build(pages, Collections.nCopies(pages.size(), fileName), name, path.toString()));
    }

    /**
     * 从已经解析好的识别结果建文档。对应 {@code document_from_result}。
     * 识别结果已经在内存里（比如刚从服务接口拿到）时用它，不用先存成文件。
     *
     * @param data   识别结果，对象或数组
     * @param name   文档名
     * @param source 来源的说明，写进 {@link Document#source()}
     */
    public Document fromResult(JsonNode data, String name, String source) {
        return fromPages(unwrapPages(data), name, source);
    }

    /** 读文件并解析成 JSON。文件开头的 BOM 去掉，Windows 上保存的文件常带着它。 */
    private JsonNode parse(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读不了文件 " + path + "：" + e.getMessage(), e);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        try {
            return mapper.readTree(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不是合法的 JSON：" + path + "（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 读一个目录：里面的 JSON 按原始文件分组，每组是一份文档。对应 {@code _json_documents}。
     *
     * <p>分组的依据是每页里的 {@code input_path}（识别时的原始文件）。没有这个字段时退而用文件名。
     * 组内按 {@code page_index} 排序。目录里别的用途的 JSON 读不出识别结果，跳过。
     */
    private List<Document> readDirectory(Path dir) {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(PaddleOcrVlJsonReader::isJsonFile)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), PaddleOcrVlJsonReader::naturalCompare))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("读不了目录 " + dir + "：" + e.getMessage(), e);
        }
        // 文档名 → 这份文档的各页；每页带着排序用的序号，和它是从哪个文件读出来的
        Map<String, List<SourcedPage>> groups = new LinkedHashMap<>();
        for (Path file : files) {
            List<JsonNode> pages;
            try {
                pages = unwrapPages(parse(file));
            } catch (IllegalArgumentException e) {
                continue; // 不是识别结果
            }
            String fileStem = RES_SUFFIX.matcher(stem(file.getFileName().toString())).replaceFirst("");
            for (JsonNode page : pages) {
                String src = str(page.get("input_path"));
                String name = (src != null && !src.isEmpty()) ? stem(lastSegment(src)) : fileStem;
                List<SourcedPage> group = groups.computeIfAbsent(name, k -> new ArrayList<>());
                Integer idx = integer(page.get("page_index"));
                group.add(new SourcedPage(idx != null ? idx : group.size(), page, file.getFileName().toString()));
            }
        }
        List<Document> docs = new ArrayList<>();
        for (Map.Entry<String, List<SourcedPage>> e : groups.entrySet()) {
            List<SourcedPage> group = e.getValue();
            group.sort(Comparator.comparingInt(SourcedPage::order)); // 稳定排序：序号相同的保持原来的先后
            docs.add(build(group.stream().map(SourcedPage::page).toList(),
                    group.stream().map(SourcedPage::file).toList(), e.getKey(), dir.toString()));
        }
        return docs;
    }

    /**
     * 读目录时的一页。
     *
     * @param order 在这份文档里排第几（{@code page_index}；没有时按读到的先后）
     * @param page  这一页的识别结果
     * @param file  这一页是从哪个文件读出来的
     */
    private record SourcedPage(int order, JsonNode page, String file) {
    }

    /**
     * 把各种外层包装统一成"每页一个对象（里面有 parsing_res_list）"的列表。对应 {@code _unwrap_pages}。
     *
     * @throws IllegalArgumentException 找不到 {@code parsing_res_list}，说明这不是 PaddleOCR-VL 的识别结果
     */
    public List<JsonNode> unwrapPages(JsonNode data) {
        if (data != null && data.isArray()) {
            List<JsonNode> pages = new ArrayList<>();
            for (JsonNode item : data.values()) {
                pages.addAll(unwrapPages(item));
            }
            return pages;
        }
        if (data != null && data.isObject()) {
            if (isObject(data.get("result"))) {
                data = data.get("result"); // 服务接口的返回最外面包着一层 result
            }
            if (isArray(data.get("layoutParsingResults"))) {
                List<JsonNode> pages = new ArrayList<>();
                for (JsonNode item : data.get("layoutParsingResults").values()) {
                    if (item.isObject() && isObject(item.get("prunedResult"))) {
                        pages.add(item.get("prunedResult"));
                    }
                }
                return pages;
            }
            if (isObject(data.get("prunedResult"))) {
                data = data.get("prunedResult");
            }
            if (isObject(data.get("res"))) {
                data = data.get("res"); // save_to_json 的旧写法包着一层 res
            }
            if (isArray(data.get("parsing_res_list"))) {
                return List.of(data);
            }
        }
        throw new IllegalArgumentException("不是 PaddleOCR-VL 的识别结果：找不到 parsing_res_list");
    }

    /**
     * 从各页的识别结果建文档。对应 {@code _from_pages}。整个读取的主流程在这里。
     */
    public Document fromPages(List<JsonNode> pages, String name, String source) {
        return build(pages, null, name, source);
    }

    /**
     * @param files 和 {@code pages} 一一对应：每一页是从哪个文件读出来的。识别结果不是从文件来的时候传 null
     */
    private Document build(List<JsonNode> pages, List<String> files, String name, String source) {
        JsonNode first = pages.isEmpty() ? null : pages.get(0);
        Integer declared = first == null ? null : integer(first.get("page_count"));
        // 合并成一页的结果：只有一个对象，没有 page_index，却声明了不止一页。这种结果里块上没有页码
        boolean concatenated = pages.size() == 1 && integer(first.get("page_index")) == null
                && declared != null && declared > 1;

        Builder builder = new Builder();
        List<String> warnings = new ArrayList<>();
        Map<String, Draft> byGlobal = new HashMap<>();   // global_block_id → 块，用来处理已经合并过的跨页表格
        Set<String> seenMargins = new HashSet<>();        // 已经出现过的页眉页脚
        Map<Integer, PageInfo> pageInfos = new TreeMap<>();
        int lastPage = 0;
        Draft prevTail = null;     // 上一页的最后一个正文块
        boolean sawBody = false;   // 是否已经遇到过正文块（页眉页脚、脚注、印章不算）

        for (int i = 0; i < pages.size(); i++) {
            JsonNode page = pages.get(i);
            Integer idx = integer(page.get("page_index"));
            Integer pageNo = concatenated ? null : (idx != null ? idx + 1 : i + 1);
            if (pageNo != null) {
                lastPage = Math.max(lastPage, pageNo);
                pageInfos.put(pageNo, new PageInfo(pageNo, integer(page.get("width")), integer(page.get("height")),
                        files == null ? null : files.get(i)));
            }
            boolean atHead = true;                     // 还没有遇到本页的正文
            List<Draft> headNotes = new ArrayList<>(); // 页首、正文之前的题注和说明
            Draft tail = null;

            JsonNode list = page.get("parsing_res_list");
            if (!isArray(list)) {
                prevTail = null;
                continue;
            }
            for (JsonNode raw : list.values()) {
                if (!raw.isObject()) {
                    continue;
                }
                String label = label(raw.get("block_label"));
                String content = str(raw.get("block_content"));
                if (content == null) {
                    content = "";
                }
                PixelBox bbox = bbox(raw.get("block_bbox"));
                String gid = key(raw.get("global_block_id"));
                String group = key(raw.get("global_group_id"));

                if (SKIP_LABELS.contains(label)) {
                    continue;
                }
                if (label.equals("table") && TextNorm.strip(content).isEmpty()) {
                    // restructure_pages 合并跨页表格之后，后半段的内容是空的，用 global_group_id 指向前半段。
                    // 这种情况下各行具体在哪一页已经无从知道，只能把表格的页码范围延长。
                    Draft target = (group != null && !group.equals(gid)) ? byGlobal.get(group) : null;
                    if (target != null && pageNo != null) {
                        target.pageEnd = Math.max(target.pageEnd == null ? 0 : target.pageEnd, pageNo);
                        target.fragments.add(new Fragment(pageNo, bbox));
                    }
                    continue;
                }
                if (MARGIN_LABELS.contains(label)) {
                    // 页眉页脚：去掉数字之后内容相同的只留第一次出现（"第 1 页"和"第 2 页"算相同）；只有页码的不要
                    String marginKey = TextNorm.squash(DIGITS.matcher(PlainText.of(content)).replaceAll(""));
                    if (WORD_CHAR.matcher(marginKey).find() && seenMargins.add(marginKey)) {
                        addBlock(builder, label, content, pageNo, bbox, warnings);
                    }
                    continue;
                }
                if (FOOTNOTE_LABELS.contains(label) || label.equals("seal")) {
                    // 脚注和印章夹在两页的表格之间时，不影响表格的衔接，所以不改 atHead 和 tail
                    addBlock(builder, label, content, pageNo, bbox, warnings);
                    continue;
                }

                // 上一页以表格结尾，本页以表格开头，宽度差不多，中间只隔着重复的题注：很可能是同一张表
                if (atHead && label.equals("table") && prevTail != null
                        && prevTail.label.equals("table") && prevTail.table != null
                        && similarWidth(prevTail.bbox, bbox)
                        && allRepeat(headNotes, prevTail)) {
                    List<Table> parts = TableParser.fromContent(tableContent(content, warnings));
                    if (parts.size() == 1) {
                        Table part = parts.get(0);
                        part.markSource(new Table.RowSource(pageNo, bbox));
                        if (prevTail.table.absorb(part)) {
                            prevTail.text = prevTail.table.render();
                            prevTail.tokens = TextNorm.estimateTokens(prevTail.text);
                            prevTail.pageEnd = pageNo;
                            if (pageNo != null) {
                                prevTail.fragments.add(new Fragment(pageNo, bbox));
                            }
                            for (Draft note : headNotes) {
                                builder.remove(note); // 续表上方重复的题注不再需要
                            }
                            if (gid != null) {
                                byGlobal.put(gid, prevTail);
                            }
                            atHead = false;
                            tail = prevTail;
                            continue;
                        }
                    }
                }

                List<Draft> added;
                if (!sawBody && i == 0 && builder.title == null && CAPTION_LABELS.contains(label)
                        && looksLikeTitle(content)) {
                    // 文档的第一个正文块被判成了图题或表题，而且不像表号、单位说明：把它当作文档标题
                    Draft title = builder.add(BlockKind.HEADING, label, TextNorm.oneLine(PlainText.of(content)),
                            pageNo, bbox, null, 0);
                    added = title == null ? List.of() : List.of(title);
                    warnings.add("文档最前面的块类别是 " + label + "，已当作文档标题：" + TextNorm.oneLine(PlainText.of(content), 40));
                } else {
                    added = addBlock(builder, label, content, pageNo, bbox, warnings);
                }
                sawBody = true;

                if (atHead && !label.equals("table") && !added.isEmpty() && isHeadNote(added.get(0))) {
                    headNotes.addAll(added); // 先记着，等看到后面是不是续表再决定去留
                    continue;
                }
                atHead = false;
                // 没有内容的块（没识别出来的图片等）也算把表格隔开了，所以这里置空
                tail = added.isEmpty() ? null : added.get(added.size() - 1);
                if (!added.isEmpty() && gid != null) {
                    byGlobal.put(gid, added.get(added.size() - 1));
                }
            }
            prevTail = tail;
        }

        Integer pageCount = concatenated ? declared : (lastPage > 0 ? Integer.valueOf(lastPage) : null);
        List<Block> blocks = new ArrayList<>();
        for (Draft draft : builder.blocks) {
            blocks.add(draft.toBlock(pageInfos));
        }
        return new Document(name, source, blocks, builder.title, pageCount,
                List.copyOf(pageInfos.values()), new IngestReport(NAME, warnings));
    }

    // ---- 一个块怎么变成 Block ------------------------------------------------------------

    /**
     * 按版面类别把一个原始块加进文档。对应 {@code _add_block}。
     *
     * @return 加进去的块。一个原始块里解析出几张表时会有几个；内容为空时是空列表
     */
    private List<Draft> addBlock(Builder builder, String label, String content, Integer page, PixelBox bbox,
                                 List<String> warnings) {
        List<Draft> added = new ArrayList<>();
        if (HEADING_LABELS.contains(label)) {
            Integer level = null;
            Matcher m = HASHES.matcher(content);
            if (m.find()) {
                // 开了 format_block_content 时标题前面带着 #，层级已经写明了，不用再猜
                level = m.group(1).length() - 1;
                content = content.substring(m.end());
            }
            addIfPresent(added, builder.add(BlockKind.HEADING, label, TextNorm.oneLine(PlainText.of(content)),
                    page, bbox, null, level));
        } else if (label.equals("table") || label.equals("chart")) {
            List<Table> tables = TableParser.fromContent(tableContent(content, warnings));
            for (Table table : tables) {
                table.markSource(new Table.RowSource(page, bbox));
                addIfPresent(added, builder.add(BlockKind.TABLE, label, table.render(), page, bbox, table, null));
            }
            if (tables.isEmpty()) {
                // 解析不出表格就按文字留下来，不能整块丢掉
                addIfPresent(added, builder.add(BlockKind.TEXT, label, PlainText.of(content), page, bbox, null, null));
            }
        } else {
            BlockKind kind;
            if (CAPTION_LABELS.contains(label)) {
                kind = BlockKind.CAPTION;
            } else if (FOOTNOTE_LABELS.contains(label)) {
                kind = BlockKind.FOOTNOTE;
            } else if (FORMULA_LABELS.contains(label)) {
                kind = BlockKind.FORMULA;
            } else if (label.equals("seal")) {
                kind = BlockKind.SEAL;
            } else if (label.equals("content")) {
                kind = BlockKind.TOC; // PaddleOCR-VL 里 content 这个类别指的是目录
            } else {
                kind = BlockKind.TEXT;
            }
            addIfPresent(added, builder.add(kind, label, PlainText.of(content), page, bbox, null, null));
        }
        return added;
    }

    private static void addIfPresent(List<Draft> list, Draft draft) {
        if (draft != null) {
            list.add(draft);
        }
    }

    /**
     * 解析表格之前先处理内容：HTML 表格里字面的 {@code \n} 换成 {@code <br>}，这样解析出来是真正的换行。
     *
     * <p>只处理 HTML 表格。OTSL 和管道文本里 {@code <br>} 不会被当成换行，换了反而多出一串字符。
     */
    private static String tableContent(String content, List<String> warnings) {
        if (content.contains(LITERAL_NEWLINE) && content.toLowerCase(Locale.ROOT).contains("<table")) {
            String note = "表格单元格里字面的 \\n 已按换行处理";
            if (!warnings.contains(note)) {
                warnings.add(note);
            }
            return content.replace(LITERAL_NEWLINE, "<br>");
        }
        return content;
    }

    /**
     * 文档最前面被判成题注的这个块，看起来像不像文档标题。
     *
     * <p>真正的表题、图题一般带着表号（"表1 …"），或者是单位说明、"续表"字样，这些不能当标题。
     * 标题一般不长。
     */
    private static boolean looksLikeTitle(String content) {
        String text = TextNorm.oneLine(PlainText.of(content));
        return !text.isEmpty() && TextNorm.length(text) <= 60
                && !LeadIn.TABLE_NO.matcher(text).find()
                && !LeadIn.UNIT_LINE.matcher(text).find()
                && !CONTINUED.matcher(text).find();
    }

    /**
     * 是不是"引子"：题注，或者单独成行的单位说明、表号。对应 {@code is_lead_in}。
     * 它们说的是紧跟在后面的那个块，要和后面的内容放在一起。
     */
    private static boolean isLeadIn(Draft block) {
        return LeadIn.is(block.kind, block.tokens, block.text);
    }

    /** 页首、表格之前的题注和说明，不算正文。对应 {@code _is_head_note}。 */
    private static boolean isHeadNote(Draft block) {
        if (block.kind != BlockKind.CAPTION && block.kind != BlockKind.TEXT) {
            return false;
        }
        return isLeadIn(block) || (TextNorm.length(block.text) <= 30 && CONTINUED.matcher(block.text).find());
    }

    /** 页首的这些说明是不是都在重复上一页那张表的题注（或者只是"续表"字样）。对应 {@code _repeats}。 */
    private static boolean allRepeat(List<Draft> notes, Draft table) {
        Set<String> leads = new HashSet<>();
        for (String lead : table.lead) {
            leads.add(TextNorm.squash(lead));
        }
        for (Draft note : notes) {
            boolean continued = TextNorm.length(note.text) <= 30 && CONTINUED.matcher(note.text).find();
            if (!continued && !leads.contains(TextNorm.squash(note.text))) {
                return false;
            }
        }
        return true;
    }

    /** 两个框的宽度是不是差不多（相差不到一成）。缺了框就不拿宽度当理由。对应 {@code _similar_width}。 */
    private static boolean similarWidth(PixelBox a, PixelBox b) {
        if (a == null || b == null) {
            return true;
        }
        int wa = a.width();
        int wb = b.width();
        if (wa <= 0 || wb <= 0) {
            return true;
        }
        return Math.abs(wa - wb) / (double) Math.min(wa, wb) < 0.1;
    }

    // ---- 读 JSON 的小工具 ----------------------------------------------------------------

    private static boolean isObject(JsonNode node) {
        return node != null && node.isObject();
    }

    private static boolean isArray(JsonNode node) {
        return node != null && node.isArray();
    }

    /** 是字符串就返回它，否则返回 null。 */
    private static String str(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    /** 是整数就返回它，否则返回 null。 */
    private static Integer integer(JsonNode node) {
        return node != null && node.isIntegralNumber() ? Integer.valueOf(node.intValue()) : null;
    }

    /** 版面类别。没写或者是空的按普通文字处理。 */
    private static String label(JsonNode node) {
        if (node == null || node.isNull()) {
            return "text";
        }
        String s = node.isString() ? node.stringValue() : node.toString();
        return s.isEmpty() ? "text" : s;
    }

    /** 把 global_block_id 这类标识变成可以比较的字符串。没有时返回 null。 */
    private static String key(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    /** 读框：必须是四个数。小数直接去掉小数部分。对应 {@code _bbox}。 */
    private static PixelBox bbox(JsonNode node) {
        if (!isArray(node) || node.size() != 4) {
            return null;
        }
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            JsonNode n = node.get(i);
            if (n == null || !n.isNumber()) {
                return null;
            }
            v[i] = (int) n.doubleValue();
        }
        return new PixelBox(v[0], v[1], v[2], v[3]);
    }

    /** 文件名去掉最后一个扩展名。 */
    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 路径的最后一段。{@code input_path} 可能是 Windows 的写法，也可能是 Linux 的。 */
    private static String lastSegment(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /**
     * 文件名的自然排序：数字按大小比，不按字符比。这样 {@code page_10} 排在 {@code page_2} 后面。
     * 对应 {@code _natural_key}。
     */
    static int naturalCompare(String a, String b) {
        List<String> ta = splitNumbers(a);
        List<String> tb = splitNumbers(b);
        for (int i = 0; i < Math.min(ta.size(), tb.size()); i++) {
            String x = ta.get(i);
            String y = tb.get(i);
            // 拆出来的片段是"文字、数字、文字、数字…"交替的，所以同一个位置上要么都是数字，要么都是文字
            int c = (i % 2 == 1)
                    ? new BigInteger(x).compareTo(new BigInteger(y))
                    : x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(ta.size(), tb.size());
    }

    /** 按数字串把名字拆开：{@code "a12b"} → {@code ["a", "12", "b"]}。偶数位是文字，奇数位是数字。 */
    private static List<String> splitNumbers(String name) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER_RUN.matcher(name);
        int last = 0;
        while (m.find()) {
            out.add(name.substring(last, m.start()));
            out.add(m.group(1));
            last = m.end();
        }
        out.add(name.substring(last));
        return out;
    }

    // ---- 读取过程中的中间状态 -------------------------------------------------------------

    /** 一个块在某一页上的那一部分。跨页的表格每页一个。 */
    private record Fragment(int page, PixelBox box) {
    }

    /**
     * 还在读取过程中的块。读取时块的内容会变（拼接跨页表格时要改文字和页码），所以先用可变的对象，
     * 全部读完再变成不可变的 {@link Block}。
     */
    private static final class Draft {
        int id;
        BlockKind kind;
        String label;
        String text;
        Integer page;
        Integer pageEnd;
        PixelBox bbox;
        Table table;
        Integer level;
        List<String> path;
        List<String> lead;
        int tokens;
        final List<Fragment> fragments = new ArrayList<>();

        Block toBlock(Map<Integer, PageInfo> pageInfos) {
            // 每页一个位置。知道框和页面尺寸就给出相对比例的框，否则只给页码
            Set<Region> regions = new LinkedHashSet<>();
            for (Fragment f : fragments) {
                Box box = f.box() == null ? null : f.box().toBox(pageInfos.get(f.page()));
                regions.add(box == null ? Region.ofPage(f.page()) : Region.ofBox(f.page(), box, Granularity.BLOCK));
            }
            return new Block(id, kind, label, text, page, pageEnd, bbox, table, level, path, lead, tokens,
                    List.copyOf(regions), TextOrigin.OCR);
        }
    }

    /** 按阅读顺序收集块，同时维护标题的层级和章节路径。对应 {@code _Builder}。 */
    private static final class Builder {
        final List<Draft> blocks = new ArrayList<>();
        String title;
        private int nextId = 0;
        private final HeadingLeveler leveler = new HeadingLeveler();
        /** 当前所在的各级标题，从外到内。每一项是 {层级, 标题}。 */
        private final List<Map.Entry<Integer, String>> stack = new ArrayList<>();
        /** 紧挨在下一个块前面的题注和单位说明。 */
        private List<String> lead = new ArrayList<>();

        /**
         * 加一个块。对应 {@code _Builder.add}。
         *
         * @param level 标题的层级；0 表示文档标题；null 表示按编号样式推断。不是标题时忽略
         * @return 加进去的块；文字为空时不加，返回 null
         */
        Draft add(BlockKind kind, String label, String text, Integer page, PixelBox bbox, Table table, Integer level) {
            text = TextNorm.strip(text);
            if (text.isEmpty()) {
                return null;
            }
            if (kind == BlockKind.HEADING) {
                String heading = TextNorm.oneLine(text, 80);
                boolean isDocTitle = label.equals("doc_title") || (level != null && level == 0);
                if (isDocTitle && title == null && stack.isEmpty()) {
                    level = 0;
                    title = heading;
                } else {
                    if (isDocTitle) {
                        level = 1; // 文档中途再出现的"文档标题"（附件、分册）按一级标题处理
                    } else if (level == null) {
                        level = leveler.level(heading);
                    }
                    level = Math.max(1, Math.min(level, 6));
                    // 新标题的层级不比栈顶低时，栈顶的那些章节到此结束
                    while (!stack.isEmpty() && stack.get(stack.size() - 1).getKey() >= level) {
                        stack.remove(stack.size() - 1);
                    }
                    stack.add(Map.entry(level, heading));
                }
            } else {
                level = null;
            }
            Draft block = new Draft();
            block.id = nextId++;
            block.kind = kind;
            block.label = label;
            block.text = text;
            block.page = page;
            block.bbox = bbox;
            block.table = table;
            block.level = level;
            block.path = stack.stream().map(Map.Entry::getValue).toList();
            block.lead = List.copyOf(lead);
            block.tokens = TextNorm.estimateTokens(text);
            if (page != null) {
                block.fragments.add(new Fragment(page, bbox));
            }
            blocks.add(block);
            // 题注和单位说明攒起来，交给它后面的那个块；遇到别的内容就清空
            if (isLeadIn(block)) {
                List<String> next = new ArrayList<>(lead);
                next.add(text);
                lead = next;
            } else {
                lead = new ArrayList<>();
            }
            return block;
        }

        /** 去掉一个已经加进去的块（续表上方重复的题注）。对应 {@code _Builder.remove}。 */
        void remove(Draft block) {
            blocks.removeIf(b -> b == block);
            lead = new ArrayList<>();
        }
    }
}
