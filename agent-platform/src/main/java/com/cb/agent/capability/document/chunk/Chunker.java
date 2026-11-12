package com.cb.agent.capability.document.chunk;

import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.BlockKind;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.model.LeadIn;
import com.cb.agent.capability.document.text.TextNorm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按文档的结构自动切片。对应 Python 版 {@code chunker.py}。
 *
 * <p>规则：
 * <ul>
 *   <li>以版面块为最小单位，尽量在章节标题的地方断开，不在一段话的中间断开；</li>
 *   <li>标题、表格的题注、"单位：万元"这类说明，必须和它后面的内容在同一片里；</li>
 *   <li>表格整体保留。超过上限的大表按行拆开，每一段都重复表头、题注和单位说明；</li>
 *   <li>特别长的段落按句子拆开；</li>
 *   <li>整篇文档很小时不切，全文就是一片（票据、证照、一两页的表）。</li>
 * </ul>
 *
 * <p>切片的边界会影响抽取的结果，所以要和 Python 版完全一致，有差分测试守着。
 */
public final class Chunker {

    /** 表示"不设上限"的一个足够大的数。 */
    private static final int NO_LIMIT = 1_000_000_000;

    /** 句子在这些标点之后结束。按它拆分时标点留在前一句的末尾。 */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;\\n])");

    private Chunker() {
    }

    /**
     * 切片的一个最小单位：一个块，或者一个大块拆出来的一部分。
     *
     * @param glue  标题、题注：要和后面的内容放在一起
     * @param alone 大表拆出来的第二段及以后：另起一片
     */
    private record Piece(Block block, String text, int tokens, boolean glue, boolean alone) {
    }

    /** 用默认的"最小片"设置切片，见 {@link #chunk(Document, int, Integer, int)}。 */
    public static List<Chunk> chunk(Document doc, int maxTokens, int wholeUnder) {
        return chunk(doc, maxTokens, null, wholeUnder);
    }

    /**
     * 把文档切成片。对应 {@code chunk_document}。
     *
     * @param doc        文档
     * @param maxTokens  每一片的大小上限（估算的 token 数）
     * @param minTokens  当前这一片还不到这个大小时，遇到新标题也不断开，免得切出一堆碎片。
     *                   null 表示用 {@code maxTokens / 4}
     * @param wholeUnder 整篇文档不超过这个大小时不切，返回单独一片
     */
    public static List<Chunk> chunk(Document doc, int maxTokens, Integer minTokens, int wholeUnder) {
        int max = maxTokens;
        Integer min = minTokens;
        if (doc.tokens() <= wholeUnder) {
            max = NO_LIMIT;
            min = NO_LIMIT;
        }
        if (min == null) {
            min = Math.floorDiv(max, 4);
        }

        List<Chunk> chunks = new ArrayList<>();
        List<Piece> cur = new ArrayList<>();
        for (Block block : doc.blocks()) {
            if (block.kind() == BlockKind.TOC) {
                continue; // 目录页会命中所有关键词，对定位只有干扰
            }
            for (Piece piece : pieces(block, max)) {
                boolean hasContent = cur.stream().anyMatch(p -> !p.glue());
                int used = cur.stream().mapToInt(Piece::tokens).sum();
                if (hasContent && (piece.alone() || used + piece.tokens() > max
                        || (block.kind() == BlockKind.HEADING && used >= min))) {
                    cur = close(chunks, cur, true);
                } else if (!hasContent && used > max) {
                    // 一长串标题后面一直没有正文（多半是没被认出来的目录页）：不再等了，自成一片
                    cur = close(chunks, cur, false);
                }
                cur.add(piece);
            }
        }
        if (!cur.isEmpty()) {
            chunks.add(makeChunk(chunks.size(), cur));
        }
        return chunks;
    }

    /**
     * 把攒着的这些收成一片。
     *
     * @param keepTail true 时，末尾的标题和题注不收进这一片，留给下一片：标题要跟着它管的内容走
     * @return 留给下一片的那些
     */
    private static List<Piece> close(List<Chunk> chunks, List<Piece> cur, boolean keepTail) {
        List<Piece> carry = new ArrayList<>();
        while (keepTail && !cur.isEmpty() && cur.get(cur.size() - 1).glue()) {
            carry.add(0, cur.remove(cur.size() - 1));
        }
        if (!cur.isEmpty()) {
            chunks.add(makeChunk(chunks.size(), cur));
        }
        return carry;
    }

    /** 把一个块变成一个或几个最小单位。对应 {@code _pieces}。 */
    private static List<Piece> pieces(Block block, int maxTokens) {
        if (block.kind() == BlockKind.HEADING || LeadIn.is(block)) {
            String text = render(block);
            return List.of(new Piece(block, text, TextNorm.estimateTokens(text), true, false));
        }
        if (block.tokens() <= maxTokens) {
            String text = render(block);
            return List.of(new Piece(block, text, TextNorm.estimateTokens(text), false, false));
        }
        if (block.table() != null) {
            List<int[]> ranges = block.table().splitRows(maxTokens);
            if (ranges.size() > 1) {
                List<Piece> out = new ArrayList<>();
                for (int n = 0; n < ranges.size(); n++) {
                    List<String> lines = new ArrayList<>();
                    if (n > 0) {
                        // 第一段前面本来就有题注和单位说明；后面各段单独成片，要把它们重复一遍
                        lines.addAll(block.lead());
                    }
                    lines.add("（表格分段 " + (n + 1) + "/" + ranges.size() + "，表头已重复）");
                    lines.add(block.table().render(ranges.get(n)[0], ranges.get(n)[1]));
                    String text = String.join("\n", lines);
                    out.add(new Piece(block, text, TextNorm.estimateTokens(text), false, n > 0));
                }
                return out;
            }
        }
        List<Piece> out = new ArrayList<>();
        for (String part : splitText(render(block), maxTokens)) {
            out.add(new Piece(block, part, TextNorm.estimateTokens(part), false, false));
        }
        return out;
    }

    /** 一个块给模型看的样子：标题前面加 #，印章和脚注加上标记。对应 {@code _render}。 */
    private static String render(Block block) {
        return switch (block.kind()) {
            case HEADING -> "#".repeat(Math.min((block.level() == null ? 0 : block.level()) + 1, 6)) + " " + block.text();
            case SEAL -> "[印章] " + block.text();
            case FOOTNOTE -> "[注] " + block.text();
            default -> block.text();
        };
    }

    /**
     * 把一段很长的文字按句子拆成不超过上限的几段。对应 {@code _split_text}。
     *
     * <p>先按句末标点拆成句子；单独一句就超限的，再按字数硬切；最后把句子一句一句拼起来，快超限时另起一段。
     */
    private static List<String> splitText(String text, int maxTokens) {
        List<String> parts = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(text, -1)) {
            int tokens = TextNorm.estimateTokens(sentence);
            if (tokens <= maxTokens) {
                parts.add(sentence);
                continue;
            }
            // 这一句自己就超限了：按比例估出每段大概多少个字，按字数切
            int[] cps = sentence.codePoints().toArray();
            int step = Math.max(100, (int) ((double) cps.length * maxTokens / tokens));
            for (int i = 0; i < cps.length; i += step) {
                parts.add(new String(cps, i, Math.min(step, cps.length - i)));
            }
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int used = 0;
        for (String part : parts) {
            int tokens = TextNorm.estimateTokens(part);
            if (cur.length() > 0 && used + tokens > maxTokens) {
                out.add(cur.toString());
                cur = new StringBuilder();
                used = 0;
            }
            cur.append(part);
            used += tokens;
        }
        if (!TextNorm.strip(cur.toString()).isEmpty()) {
            out.add(cur.toString());
        }
        List<String> cleaned = new ArrayList<>();
        for (String p : out) {
            String s = TextNorm.strip(p);
            if (!s.isEmpty()) {
                cleaned.add(s);
            }
        }
        return cleaned;
    }

    /** 把几个最小单位合成一片。对应 {@code _make_chunk}。 */
    private static Chunk makeChunk(int index, List<Piece> pieces) {
        List<Block> blocks = new ArrayList<>();
        for (Piece piece : pieces) {
            // 一个块拆出的几个单位挨在一起时只记一次
            if (blocks.isEmpty() || blocks.get(blocks.size() - 1) != piece.block()) {
                blocks.add(piece.block());
            }
        }
        Integer pageStart = null;
        Integer pageEnd = null;
        for (Block b : blocks) {
            for (Integer p : new Integer[]{b.page(), b.pageEnd()}) {
                if (p != null) {
                    pageStart = pageStart == null ? p : Math.min(pageStart, p);
                    pageEnd = pageEnd == null ? p : Math.max(pageEnd, p);
                }
            }
        }
        List<String> texts = new ArrayList<>();
        int tokens = 0;
        for (Piece piece : pieces) {
            texts.add(piece.text());
            tokens += piece.tokens();
        }
        return new Chunk(index, String.join("\n\n", texts), commonPrefix(blocks), blocks, pageStart, pageEnd, tokens);
    }

    /** 各个块的章节路径的公共部分。片里的块分属不同的小节时，片的章节就是它们共同的上一级。 */
    private static List<String> commonPrefix(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<String> prefix = blocks.get(0).path();
        for (Block b : blocks.subList(1, blocks.size())) {
            List<String> path = b.path();
            int n = 0;
            while (n < Math.min(prefix.size(), path.size()) && prefix.get(n).equals(path.get(n))) {
                n++;
            }
            prefix = prefix.subList(0, n);
        }
        return prefix;
    }
}
