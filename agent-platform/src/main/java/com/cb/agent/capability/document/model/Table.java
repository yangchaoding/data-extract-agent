package com.cb.agent.capability.document.model;

import com.cb.agent.capability.document.text.TextNorm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 表格：一张规整的二维网格。
 *
 * <p>对应 Python 版 {@code tables.py} 的 {@code Table}。识别结果里的表格是 HTML，带合并单元格。
 * 解析时把合并单元格展开，让每一行都有完整的列，这样大表可以按行拆开，每一段重复表头也不会丢信息。
 *
 * <p>和 Python 版相比多了一样东西：<b>每一行记着自己来自哪一页、哪个框</b>（{@link RowSource}）。
 * 跨页的表格会被拼成一张，如果只在表上记一个页码和一个框，第二页的行就会带着第一页的坐标，
 * 拿去原件上画框位置是错的。
 *
 * <p>单元格里的换行用 {@code \n} 保存。给模型看的时候换成 {@link #CELL_LINE_BREAK}，见 {@link #render}。
 */
public final class Table {

    /** 合并单元格展开时，较长的文字不逐格重复，用这个"同上"符号代替。 */
    public static final String DITTO = "〃";

    /**
     * 渲染成文字时，单元格里的换行写成什么。
     *
     * <p>管道表的一行不能包含真正的换行，所以要换成一个标记。这里用反斜杠加 n 两个字符，
     * 因为 PaddleOCR-VL 的原始输出就是这样写的，实测模型能看懂：简历这种一格多行的内容，
     * 带着这个标记时模型能把每段经历分开；标记丢掉之后（Xiaomi-OCR 的输出）各段就连成了一片。
     */
    public static final String CELL_LINE_BREAK = "\\n";

    /**
     * 一行的来源。
     *
     * @param page 这一行在第几页，从 1 开始；不知道时为 null
     * @param box  这一行所在的那一段表格的框（像素）；识别结果只给到整张表的框，所以同一页的各行是同一个框
     */
    public record RowSource(Integer page, PixelBox box) {
    }

    private final List<List<String>> rows;
    private final List<RowSource> sources;
    private final int headerRows;
    private final boolean explicitHeader;

    /**
     * @param rows           网格，每一行的列数必须相同
     * @param headerRows     前几行是表头
     * @param explicitHeader 表头是识别结果里明确标出来的（{@code <th>}），不是猜的
     */
    public Table(List<List<String>> rows, int headerRows, boolean explicitHeader) {
        this.rows = new ArrayList<>();
        for (List<String> row : rows) {
            this.rows.add(new ArrayList<>(row));
        }
        this.sources = new ArrayList<>(Collections.nCopies(this.rows.size(), null));
        this.headerRows = headerRows;
        this.explicitHeader = explicitHeader;
    }

    /** 列数。 */
    public int nCols() {
        return rows.isEmpty() ? 0 : rows.get(0).size();
    }

    /** 全部的行，包括表头。只读。 */
    public List<List<String>> rows() {
        return Collections.unmodifiableList(rows);
    }

    public int headerRows() {
        return headerRows;
    }

    public boolean explicitHeader() {
        return explicitHeader;
    }

    /** 数据行（不含表头）。只读。 */
    public List<List<String>> body() {
        return Collections.unmodifiableList(rows.subList(Math.min(headerRows, rows.size()), rows.size()));
    }

    /**
     * 第 {@code rowIndex} 行的来源。
     *
     * @param rowIndex 行号，从 0 开始，包括表头在内
     * @return 这一行的来源；读取器没有提供时为 null
     */
    public RowSource rowSource(int rowIndex) {
        return sources.get(rowIndex);
    }

    /**
     * 把现有的每一行都标成来自同一个地方。读取器解析出一页上的一张表之后调用。
     */
    public void markSource(RowSource source) {
        Collections.fill(sources, source);
    }

    /** 这张表跨了哪些页，按出现的先后排列。 */
    public List<Integer> pages() {
        List<Integer> out = new ArrayList<>();
        for (RowSource s : sources) {
            if (s != null && s.page() != null && !out.contains(s.page())) {
                out.add(s.page());
            }
        }
        return out;
    }

    /**
     * 把下一页的续表接到本表后面。对应 Python 版的 {@code Table.absorb}。
     *
     * <p>判断 {@code other} 是不是本表的续表：
     * <ul>
     *   <li>列数必须相同；</li>
     *   <li>续表的开头如果重复了本表的表头，重复的那几行去掉；</li>
     *   <li>续表有自己明确标出的表头，而且和本表的不一样，那它是另一张表，不接。</li>
     * </ul>
     * 接上去的行保留它们自己的来源（页码和框），这是和 Python 版不同的地方。
     *
     * @return 接上了返回 true；{@code other} 不像是续表时不做任何改动，返回 false
     */
    public boolean absorb(Table other) {
        if (other.nCols() != nCols()) {
            return false;
        }
        Set<List<String>> head = new HashSet<>();
        for (List<String> row : rows.subList(0, Math.min(headerRows, rows.size()))) {
            head.add(squashRow(row));
        }
        int repeated = 0; // 续表开头重复了几行表头
        for (List<String> row : other.rows) {
            if (!head.contains(squashRow(row))) {
                break;
            }
            repeated++;
        }
        if (other.explicitHeader && repeated == 0) {
            return false;
        }
        for (int i = repeated; i < other.rows.size(); i++) {
            rows.add(new ArrayList<>(other.rows.get(i)));
            sources.add(other.sources.get(i));
        }
        return true;
    }

    private static List<String> squashRow(List<String> row) {
        List<String> out = new ArrayList<>(row.size());
        for (String cell : row) {
            out.add(TextNorm.squash(cell));
        }
        return out;
    }

    /** 渲染整张表。 */
    public String render() {
        return render(0, null);
    }

    /**
     * 渲染成管道表（Markdown 表格的写法），给模型看。对应 Python 版的 {@code Table.render}。
     *
     * <p>表头总是保留。{@code start} 和 {@code end} 是数据行的范围，大表拆成几段时每一段只带自己的数据行。
     *
     * @param start 从第几个数据行开始（不含表头，从 0 开始）
     * @param end   到第几个数据行为止（不含）；null 表示到最后
     */
    public String render(int start, Integer end) {
        List<String> lines = new ArrayList<>();
        int head = Math.min(headerRows, rows.size());
        for (int i = 0; i < head; i++) {
            lines.add(formatRow(rows.get(i)));
        }
        if (head > 0) {
            lines.add("| " + String.join(" | ", Collections.nCopies(nCols(), "---")) + " |");
        }
        List<List<String>> body = body();
        int to = end == null ? body.size() : Math.min(end, body.size());
        for (int i = Math.max(start, 0); i < to; i++) {
            lines.add(formatRow(body.get(i)));
        }
        return String.join("\n", lines);
    }

    /**
     * 把数据行分成几段，让"表头加上每一段"都不超过 {@code maxTokens}。对应 {@code Table.split_rows}。
     *
     * <p>每一段至少有一行，所以单独一行就超限时这一段仍然会超。
     *
     * @return 每一段在数据行里的范围 {@code [start, end)}
     */
    public List<int[]> splitRows(int maxTokens) {
        int headTokens = 0;
        for (int i = 0; i < Math.min(headerRows, rows.size()); i++) {
            headTokens += TextNorm.estimateTokens(formatRow(rows.get(i)));
        }
        headTokens += nCols() * 2; // 表头下面那条分隔线
        // 表头本身就很大时，至少给数据行留三分之一的空间，否则一行都放不下
        int budget = Math.max(maxTokens - headTokens, maxTokens / 3);
        List<int[]> ranges = new ArrayList<>();
        List<List<String>> body = body();
        int start = 0;
        int used = 0;
        for (int i = 0; i < body.size(); i++) {
            int t = TextNorm.estimateTokens(formatRow(body.get(i)));
            if (i > start && used + t > budget) {
                ranges.add(new int[]{start, i});
                start = i;
                used = 0;
            }
            used += t;
        }
        if (start < body.size() || ranges.isEmpty()) {
            ranges.add(new int[]{start, body.size()});
        }
        return ranges;
    }

    /**
     * 第 {@code rowIndex} 行渲染成管道表的一行。用来判断一段证据引的是表格的哪一行。
     *
     * @param rowIndex 行号，从 0 开始，包括表头在内
     */
    public String renderRow(int rowIndex) {
        return formatRow(rows.get(rowIndex));
    }

    /** 一行渲染成 {@code | a | b |}。空单元格写一个空格，免得两条竖线贴在一起。 */
    private static String formatRow(List<String> row) {
        List<String> cells = new ArrayList<>(row.size());
        for (String cell : row) {
            cells.add(cell.isEmpty() ? " " : cell.replace("\n", CELL_LINE_BREAK));
        }
        return "| " + String.join(" | ", cells) + " |";
    }
}
