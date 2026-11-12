package com.cb.agent.capability.extract.engine;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.text.TextNorm;
import com.cb.agent.capability.extract.format.PyFormat;
import com.cb.agent.core.trace.Locator;
import com.cb.agent.core.trace.Region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个抽取到的值和它的出处：标量字段的值，或者列表字段的一项。对应 Python 版 {@code agent.py} 的 {@code Item}。
 *
 * <p>抽取过程中它的值会被整理（字符串变成数字、日期），问题会被追加，所以是可变的。
 */
public final class Item {

    private Object value;
    private final String evidence;
    private final Chunk chunk;
    private final Block block;
    private final List<String> issues;
    private Locator locator;

    /**
     * @param value    模型给的值；整理之后会被换成字段的类型
     * @param evidence 模型给的证据：原文摘抄
     * @param chunk    证据所在的片段；没有定位到时为 null
     * @param block    证据所在的版面块；没有定位到时为 null
     * @param issues   核对时发现的问题。列表会被继续追加
     */
    public Item(Object value, String evidence, Chunk chunk, Block block, List<String> issues) {
        this.value = value;
        this.evidence = evidence;
        this.chunk = chunk;
        this.block = block;
        this.issues = issues;
    }

    public Object value() {
        return value;
    }

    void setValue(Object value) {
        this.value = value;
    }

    public String evidence() {
        return evidence;
    }

    public Chunk chunk() {
        return chunk;
    }

    public Block block() {
        return block;
    }

    public List<String> issues() {
        return issues;
    }

    /** 这个值在原件上的出处；没有定位到时为 null。 */
    public Locator locator() {
        return locator;
    }

    void setLocator(Locator locator) {
        this.locator = locator;
    }

    /** 没有问题就是通过了核对。 */
    public boolean verified() {
        return issues.isEmpty();
    }

    /**
     * 判断两个值是不是同一个值时用的键：把值写成一行 JSON（对象的键排好序），再归一化。
     * 对应 Python 版的 {@code _value_key}。键相同就认为是同一个值，空白和全半角的差别不算。
     */
    static String valueKey(Object value) {
        return TextNorm.squash(PyFormat.jsonSorted(value));
    }

    /**
     * 导出成可以直接序列化的结构。对应 {@code Item.to_dict}，另外多一项 {@code locator}。
     *
     * <p>{@code pages} 和 {@code bbox} 是给人看的简略出处。证据引的是跨页表格的某一行时，
     * 给出的是这一行所在的那一页和那一段表格的框（Python 版给的是整张表的页码范围和第一页的框）。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("evidence", evidence);
        if (chunk != null) {
            out.put("chunk", chunk.id());
        }
        if (block != null) {
            Provenance.Brief brief = Provenance.brief(block, locator);
            if (!brief.pages().isEmpty()) {
                out.put("pages", brief.pages());
            }
            if (brief.bbox() != null) {
                out.put("bbox", brief.bbox().toList());
            }
            if (!block.path().isEmpty()) {
                out.put("section", String.join(" > ", block.path()));
            }
        } else if (chunk != null) {
            if (!chunk.pages().isEmpty()) {
                out.put("pages", chunk.pages());
            }
            if (!chunk.section().isEmpty()) {
                out.put("section", chunk.section());
            }
        }
        if (locator != null) {
            out.put("locator", locatorToMap(locator));
        }
        if (!issues.isEmpty()) {
            out.put("issues", new ArrayList<>(issues));
        }
        return out;
    }

    private static Map<String, Object> locatorToMap(Locator locator) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origin", locator.origin() == null ? null : locator.origin().name());
        m.put("evidence_regions", regions(locator.evidenceRegions()));
        m.put("value_regions", regions(locator.valueRegions()));
        return m;
    }

    private static List<Map<String, Object>> regions(List<Region> regions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Region r : regions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page", r.page());
            m.put("box", r.box() == null ? null : List.of(r.box().x1(), r.box().y1(), r.box().x2(), r.box().y2()));
            m.put("granularity", r.granularity().name());
            out.add(m);
        }
        return out;
    }
}
