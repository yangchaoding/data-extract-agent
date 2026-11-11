package com.cb.agent.capability.document.table;

import com.cb.agent.capability.document.model.Table;
import com.cb.agent.capability.document.text.TextNorm;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把块内容解析成表格：HTML、OTSL 标记、管道文本三种写法都认。
 *
 * <p>对应 Python 版 {@code tables.py} 里的 {@code parse_html_tables}、{@code parse_otsl}、
 * {@code parse_pipe_table}、{@code tables_from_content}。
 *
 * <p>为什么要把 HTML 变成网格，而不是直接把 HTML 交给模型：HTML 很费 token；更要紧的是大表要按行拆开，
 * 带着合并单元格拆会丢上下文。先把合并单元格展开，每一行都有完整的列，就可以随便拆。
 */
public final class TableParser {

    /** 列合并数的上限。识别出错时可能冒出一个很大的 colspan，不设上限会撑出几千列。 */
    private static final int MAX_COLSPAN = 64;
    /** 合并单元格展开时，不超过这么多字的文字逐格重复，更长的用"同上"符号代替。 */
    private static final int REPEAT_FULL_LEN = 24;
    /** 表头最多算几行。 */
    private static final int MAX_HEADER_ROWS = 4;

    /** OTSL 的标记。PaddleOCR-VL 内部先生成 OTSL 再转成 HTML，转换失败时块内容里留下的就是它。 */
    private static final Pattern OTSL_TAG = Pattern.compile("<(fcel|ecel|lcel|ucel|xcel|ched|rhed|srow|nl)>");
    /** 管道表里的分隔行单元格，例如 {@code ---}、{@code :---:}。 */
    private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-{2,}:?");

    private TableParser() {
    }

    /** HTML 里的一个单元格，还没有展开合并。 */
    private record RawCell(String text, int rowspan, int colspan, boolean header) {
    }

    /**
     * 从块内容里解析表格，依次尝试 HTML、OTSL、管道文本。对应 {@code tables_from_content}。
     *
     * @return 解析出的表格；三种都不是时返回空列表，调用方按普通文字处理
     */
    public static List<Table> fromContent(String content) {
        if (content.toLowerCase(Locale.ROOT).contains("<table")) {
            List<Table> tables = parseHtml(content);
            if (!tables.isEmpty()) {
                return tables;
            }
        }
        if (OTSL_TAG.matcher(content).find()) {
            Table table = parseOtsl(content);
            if (table != null) {
                return List.of(table);
            }
        }
        Table table = parsePipe(content);
        return table == null ? List.of() : List.of(table);
    }

    /**
     * 解析 HTML 表格。对应 {@code parse_html_tables}。
     *
     * <p>识别结果里的 HTML 经常不规范：标签没闭合、带着 style、单元格里有图片。用 jsoup 解析，它会自动补全结构。
     * 嵌套的表格不单独算一张表，它的文字并进外层的单元格。
     */
    public static List<Table> parseHtml(String html) {
        List<Table> out = new ArrayList<>();
        org.jsoup.nodes.Document doc;
        try {
            doc = Jsoup.parseBodyFragment(html);
        } catch (RuntimeException e) {
            // 标签严重损坏时当作没有表格，交给调用方按文字处理
            return out;
        }
        for (Element tableEl : doc.select("table")) {
            if (closestTable(tableEl.parent()) != null) {
                continue; // 嵌套在别的表格里
            }
            List<List<RawCell>> rawRows = new ArrayList<>();
            for (Element tr : tableEl.select("tr")) {
                if (closestTable(tr) != tableEl) {
                    continue; // 这一行属于嵌套的表格
                }
                List<RawCell> cells = new ArrayList<>();
                for (Element cell : tr.children()) {
                    String tag = cell.normalName();
                    if (!tag.equals("td") && !tag.equals("th")) {
                        continue;
                    }
                    cells.add(new RawCell(
                            cellText(cell),
                            span(cell.attr("rowspan")),
                            Math.min(span(cell.attr("colspan")), MAX_COLSPAN),
                            tag.equals("th")));
                }
                // 空行也要留着：它可能整行都被上面单元格的 rowspan 占了
                rawRows.add(cells);
            }
            Table table = build(rawRows);
            if (table != null) {
                out.add(table);
            }
        }
        return out;
    }

    /** 从 {@code node} 往上找最近的 table 元素（包括它自己）。 */
    private static Element closestTable(Node node) {
        for (Node n = node; n != null; n = n.parent()) {
            if (n instanceof Element el && el.normalName().equals("table")) {
                return el;
            }
        }
        return null;
    }

    /** 解析 rowspan / colspan 的值。写错了或者没写都按 1 算。 */
    private static int span(String value) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 取出一个单元格里的文字。
     *
     * <p>{@code <br>} 记成换行。HTML 源码里为了排版加的空白和换行不算内容，压成一个空格。
     * 单元格里嵌套了表格时，嵌套表格的各个单元格之间用空格隔开。
     */
    private static String cellText(Element cell) {
        StringBuilder buf = new StringBuilder();
        int[] nested = {0}; // 现在在第几层嵌套的表格里
        cell.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    buf.append(collapseSpaces(text.getWholeText()));
                } else if (node instanceof Element el) {
                    String tag = el.normalName();
                    if (tag.equals("table")) {
                        nested[0]++;
                    } else if (tag.equals("br")) {
                        buf.append(nested[0] > 0 ? ' ' : '\n');
                    } else if (nested[0] > 0 && (tag.equals("td") || tag.equals("th") || tag.equals("tr"))) {
                        buf.append(' ');
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el && el.normalName().equals("table")) {
                    nested[0]--;
                }
            }
        });
        return cleanCell(buf.toString());
    }

    /** 把连续的空白（包括换行）压成一个空格。 */
    private static String collapseSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (TextNorm.isSpace(cp)) {
                if (!lastSpace) {
                    out.append(' ');
                }
                lastSpace = true;
            } else {
                out.appendCodePoint(cp);
                lastSpace = false;
            }
        }
        return out.toString();
    }

    /**
     * 整理单元格的文字。对应 {@code _clean_cell}。
     *
     * <p>每一行各自压掉多余的空白，空行去掉。竖线换成全角的，免得渲染成管道表之后被当成列分隔符。
     * 和 Python 版不同的地方：Python 版把换行也压成了空格，这里把换行留着（见 {@link Table} 的说明）。
     */
    static String cleanCell(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String cleaned = TextNorm.oneLine(line);
            if (!cleaned.isEmpty()) {
                lines.add(cleaned);
            }
        }
        return String.join("\n", lines).replace("|", "｜");
    }

    /**
     * 判断前几行是表头。对应 {@code _detect_header_rows}。
     *
     * @return {@code [表头行数, 是否由 <th> 明确标出（1 是，0 否）]}
     */
    private static int[] detectHeaderRows(List<List<RawCell>> rawRows) {
        int n = rawRows.size();
        if (n <= 1) {
            return new int[]{0, 0};
        }
        // 开头连续几行全是 <th>：这是识别结果明确标出来的表头
        int thRows = 0;
        for (List<RawCell> cells : rawRows) {
            if (!cells.isEmpty() && cells.stream().allMatch(RawCell::header)) {
                thRows++;
            } else {
                break;
            }
        }
        if (thRows > 0 && thRows < n) {
            return new int[]{Math.min(thRows, MAX_HEADER_ROWS), 1};
        }
        // 没有标出来就猜：第一行是表头；第一行里有跨行的单元格，或者有跨列的（分组表头），表头就不止一行
        List<RawCell> first = rawRows.get(0);
        if (first.isEmpty()) {
            return new int[]{1, 0};
        }
        int rows = first.stream().mapToInt(RawCell::rowspan).max().orElse(1);
        if (first.stream().anyMatch(c -> c.colspan() > 1)) {
            rows = Math.max(rows, 2);
        }
        return new int[]{Math.max(1, Math.min(Math.min(rows, MAX_HEADER_ROWS), n - 1)), 0};
    }

    /** 把 rowspan / colspan 展开成矩形的网格。对应 {@code _expand}。 */
    private static List<List<String>> expand(List<List<RawCell>> rawRows) {
        int nRows = rawRows.size();
        Map<Long, String> occupied = new HashMap<>();
        int nCols = 0;
        for (int r = 0; r < nRows; r++) {
            int c = 0;
            for (RawCell cell : rawRows.get(r)) {
                // 这个位置已经被上面某个跨行的单元格占了，往右找空位
                while (occupied.containsKey(key(r, c))) {
                    c++;
                }
                for (int dr = 0; dr < Math.min(cell.rowspan(), nRows - r); dr++) {
                    for (int dc = 0; dc < cell.colspan(); dc++) {
                        boolean origin = dr == 0 && dc == 0;
                        // 短的文字每一格都重复写，这样拆开之后每一行都看得懂；长的只写一次，其余用"同上"。
                        // 长短按渲染出来的样子算（单元格里的换行渲染成两个字符的标记），因为这条规则
                        // 关心的是给模型看的时候占多宽。这样算也和 Python 版在真实输出上的结果一致。
                        String text = origin || renderedLength(cell.text()) <= REPEAT_FULL_LEN
                                ? cell.text() : Table.DITTO;
                        occupied.putIfAbsent(key(r + dr, c + dc), text);
                    }
                }
                c += cell.colspan();
                nCols = Math.max(nCols, c);
            }
        }
        List<List<String>> grid = new ArrayList<>();
        for (int r = 0; r < nRows; r++) {
            List<String> row = new ArrayList<>(nCols);
            for (int c = 0; c < nCols; c++) {
                row.add(occupied.getOrDefault(key(r, c), ""));
            }
            grid.add(row);
        }
        return grid;
    }

    private static long key(int r, int c) {
        return ((long) r << 32) | (c & 0xffffffffL);
    }

    /** 单元格的文字渲染成管道表之后有多少个字。 */
    private static int renderedLength(String cellText) {
        return TextNorm.length(cellText.replace("\n", Table.CELL_LINE_BREAK));
    }

    /** 从原始单元格建表：判断表头、展开合并、去掉全空的行。对应 {@code _build}。 */
    private static Table build(List<List<RawCell>> rawRows) {
        if (rawRows.stream().allMatch(List::isEmpty)) {
            return null;
        }
        int[] header = detectHeaderRows(rawRows);
        List<List<String>> grid = expand(rawRows);
        List<List<String>> rows = new ArrayList<>();
        int keptInHeader = 0; // 表头范围里留下来的行数：全空的行被去掉之后，表头行数要跟着减
        for (int i = 0; i < grid.size(); i++) {
            boolean keep = grid.get(i).stream().anyMatch(cell -> !cell.isEmpty());
            if (keep) {
                rows.add(grid.get(i));
                if (i < header[0]) {
                    keptInHeader++;
                }
            }
        }
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        // 至少留一行数据：只有一行的表不算有表头
        int headerRows = Math.min(keptInHeader, Math.max(rows.size() - 1, 0));
        return new Table(rows, headerRows, header[1] == 1 && headerRows > 0);
    }

    /**
     * 解析 OTSL 标记，作为 HTML 解析不了时的后备。对应 {@code parse_otsl}。
     *
     * <p>标记的含义：{@code fcel} 有内容的单元格，{@code ecel} 空单元格，{@code lcel} 和左边的单元格合并，
     * {@code ucel}、{@code xcel} 和上面的单元格合并，{@code nl} 换行。
     *
     * @return 表格；内容里没有 OTSL 标记或者解析不出任何内容时返回 null
     */
    public static Table parseOtsl(String text) {
        Matcher m = OTSL_TAG.matcher(text);
        List<String> tags = new ArrayList<>();
        List<String> contents = new ArrayList<>(); // 每个标记后面、下一个标记前面的文字
        int lastEnd = -1;
        while (m.find()) {
            if (lastEnd >= 0) {
                contents.add(text.substring(lastEnd, m.start()));
            }
            tags.add(m.group(1));
            lastEnd = m.end();
        }
        if (tags.isEmpty()) {
            return null;
        }
        contents.add(text.substring(lastEnd));

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            switch (tag) {
                case "nl" -> {
                    rows.add(row);
                    row = new ArrayList<>();
                }
                case "ecel" -> row.add("");
                case "lcel" -> row.add(row.isEmpty() ? "" : row.get(row.size() - 1));
                case "ucel", "xcel" -> {
                    List<String> above = rows.isEmpty() ? List.of() : rows.get(rows.size() - 1);
                    row.add(row.size() < above.size() ? above.get(row.size()) : "");
                }
                // OTSL 里没有 <br>，源文本里的换行只是排版，压成空格
                default -> row.add(cleanCell(contents.get(i).replace('\n', ' ')));
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows = dropEmptyRows(pad(rows));
        if (rows.isEmpty()) {
            return null;
        }
        return new Table(rows, rows.size() > 1 ? 1 : 0, false);
    }

    /**
     * 解析管道表：Markdown 表格，或者每行都是 {@code a | b} 形式的文字（图表识别的结果是这种写法）。
     * 对应 {@code parse_pipe_table}。
     *
     * @return 表格；不是管道表时返回 null
     */
    public static Table parsePipe(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : TextNorm.strip(text).split("\n", -1)) {
            String s = TextNorm.strip(line);
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        if (lines.size() < 2 || !lines.stream().allMatch(l -> l.contains("|"))) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            List<String> cells = new ArrayList<>();
            for (String cell : stripPipes(line).split("\\|", -1)) {
                cells.add(cleanCell(cell));
            }
            boolean anyText = cells.stream().anyMatch(c -> !c.isEmpty());
            boolean allSeparators = cells.stream().filter(c -> !c.isEmpty())
                    .allMatch(c -> SEPARATOR_CELL.matcher(c).matches());
            if (anyText && allSeparators) {
                continue; // 表头下面的那条分隔线
            }
            rows.add(cells);
        }
        rows = dropEmptyRows(pad(rows));
        if (rows.size() < 2) {
            return null;
        }
        return new Table(rows, 1, false);
    }

    /** 去掉一行首尾的竖线。 */
    private static String stripPipes(String line) {
        int start = 0;
        int end = line.length();
        while (start < end && line.charAt(start) == '|') {
            start++;
        }
        while (end > start && line.charAt(end - 1) == '|') {
            end--;
        }
        return line.substring(start, end);
    }

    /** 把各行补到一样宽。 */
    private static List<List<String>> pad(List<List<String>> rows) {
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> padded = new ArrayList<>(row);
            while (padded.size() < width) {
                padded.add("");
            }
            out.add(padded);
        }
        return out;
    }

    private static List<List<String>> dropEmptyRows(List<List<String>> rows) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows) {
            if (row.stream().anyMatch(c -> !c.isEmpty())) {
                out.add(row);
            }
        }
        return out;
    }
}
