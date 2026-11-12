package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.model.PixelBox;
import com.cb.agent.capability.document.model.Table;
import com.cb.agent.capability.document.text.SourceText;
import com.cb.agent.capability.document.text.TextMatch;
import com.cb.agent.core.trace.Granularity;
import com.cb.agent.core.trace.Locator;
import com.cb.agent.core.trace.Region;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 出处：一个值的证据在原件的什么位置。
 *
 * <p>现在能做到的粒度是版面块（{@link Granularity#BLOCK}），因为 PaddleOCR-VL 的输出只给到版面块的框。
 * 在这个粒度上有一处比 Python 版准：<b>证据引的是跨页表格的某一行时，给出的是这一行所在的那一页</b>，
 * 而不是整张表的页码范围加第一页的框。
 *
 * <p>值本身在证据里占的那一小块（{@code valueRegions}）现在给不出来，要等读取器能提供
 * 行级、单元格级的位置之后再做，所以先是空的。
 */
final class Provenance {

    private Provenance() {
    }

    /**
     * 给人看的简略出处。
     *
     * @param pages 页码的文字写法，例如 {@code "2"}、{@code "1-2"}；没有页码时是空串
     * @param bbox  像素框；没有时为 null
     */
    record Brief(String pages, PixelBox bbox) {
    }

    /**
     * 算出证据的出处。
     *
     * @param chunk    证据所在的片段；可以为 null
     * @param block    证据所在的版面块；可以为 null
     * @param evidence 证据
     * @return 出处；片段和块都没有定位到时返回 null
     */
    static Locator locate(Chunk chunk, Block block, String evidence) {
        if (block == null) {
            return null;
        }
        List<Region> regions = block.table() != null ? rowRegions(block, evidence) : List.of();
        if (regions.isEmpty()) {
            regions = block.regions(); // 不是表格，或者认不出是哪一行：给出整个块的位置
        }
        return new Locator(null, null, block.origin(), regions, List.of());
    }

    /**
     * 证据引的是表格的哪几行，这几行各在哪一页。
     *
     * <p>证据可以是几段（表头那一行加上数据那一行）。逐段去找它出自表格的哪一行；
     * 表头行每一页的续表都可能重复，所以只在找到了数据行时才用数据行来定位。
     *
     * @return 这几行所在的位置，每页一个；认不出是哪一行时返回空列表
     */
    private static List<Region> rowRegions(Block block, String evidence) {
        Table table = block.table();
        List<String> parts = TextMatch.quoteParts(evidence);
        Set<Integer> bodyPages = new LinkedHashSet<>();
        Set<Integer> headerPages = new LinkedHashSet<>();
        for (int r = 0; r < table.rows().size(); r++) {
            Table.RowSource source = table.rowSource(r);
            if (source == null || source.page() == null) {
                continue;
            }
            SourceText row = new SourceText(table.renderRow(r));
            for (String part : parts) {
                // 两个方向都算：证据是这一行的一部分；或者这一行是证据的一部分（证据引了好几行）
                if (row.supports(part) || new SourceText(part).supports(table.renderRow(r))) {
                    (r < table.headerRows() ? headerPages : bodyPages).add(source.page());
                    break;
                }
            }
        }
        Set<Integer> pages = bodyPages.isEmpty() ? headerPages : bodyPages;
        List<Region> out = new ArrayList<>();
        for (Region region : block.regions()) {
            if (region.page() != null && pages.contains(region.page())) {
                out.add(region);
            }
        }
        return out;
    }

    /**
     * 简略的出处：页码和像素框。
     *
     * <p>出处落在块的某一页上时，给出那一页和那一页上的框；否则给出整个块的页码范围和它的第一个框。
     */
    static Brief brief(Block block, Locator locator) {
        if (locator != null && block.table() != null && !locator.evidenceRegions().isEmpty()
                && locator.evidenceRegions().size() < block.regions().size()) {
            // 证据只落在这张跨页表格的一部分页上
            List<Integer> pages = new ArrayList<>();
            for (Region r : locator.evidenceRegions()) {
                if (r.page() != null && !pages.contains(r.page())) {
                    pages.add(r.page());
                }
            }
            if (!pages.isEmpty()) {
                int first = pages.get(0);
                int last = pages.get(pages.size() - 1);
                return new Brief(first == last ? String.valueOf(first) : first + "-" + last, boxOnPage(block, first));
            }
        }
        return new Brief(block.pages(), block.bbox());
    }

    /** 这张表在某一页上那一段的像素框。 */
    private static PixelBox boxOnPage(Block block, int page) {
        Table table = block.table();
        for (int r = 0; r < table.rows().size(); r++) {
            Table.RowSource source = table.rowSource(r);
            if (source != null && source.page() != null && source.page() == page && source.box() != null) {
                return source.box();
            }
        }
        return block.bbox();
    }
}
