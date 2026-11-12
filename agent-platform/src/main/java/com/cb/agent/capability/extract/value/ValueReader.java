package com.cb.agent.capability.extract.value;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.text.SourceText;
import com.cb.agent.capability.document.text.TextMatch;
import com.cb.agent.capability.document.text.TextNorm;
import com.cb.agent.capability.extract.format.PyFormat;
import com.cb.agent.capability.extract.schema.FieldSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把模型给出的值整理成字段的类型，并核对"证据出自原文、值出自证据"。
 *
 * <p>对应 Python 版 {@code values.py} 的"核对"部分：{@code locate_evidence}、{@code read_value}。
 *
 * <p>程序能核对的：
 * <ul>
 *   <li>证据是不是给模型看过的片段里的原文；</li>
 *   <li>数字是不是写在证据里，单位和原文对不对得上；</li>
 *   <li>日期是不是证据里的某个日期；</li>
 *   <li>照抄型的字符串是不是原文里的文字。</li>
 * </ul>
 * 程序核对不了的：枚举、布尔、允许概括的字符串，这些是模型的判断，只能保证它引的证据是真的。
 *
 * <p>发现的问题用中文写成一句话，重问时原样发给模型，所以文字和 Python 版相同。
 */
public final class ValueReader {

    private ValueReader() {
    }

    private static final Set<String> TRUE = Set.of("true", "yes", "y", "1", "是", "有", "对");
    private static final Set<String> FALSE = Set.of("false", "no", "n", "0", "否", "无", "没有", "不是");

    /**
     * 整理和核对的结果。
     *
     * @param value  整理成字段类型之后的值；整理不了时是模型给的原样
     * @param issues 发现的问题；为空表示通过了核对。列表可以修改，调用方会往里面追加
     */
    public record Read(Object value, List<String> issues) {
    }

    /**
     * 证据的定位结果。
     *
     * @param chunk  证据所在的片段；找不到时是最像的那个片段，或者模型声称的那个，也可能为 null
     * @param issues 发现的问题；为空表示证据确实是原文
     */
    public record Located(Chunk chunk, List<String> issues) {
    }

    /**
     * 核对证据是给模型看过的片段里的原文，并找出它在哪个片段里。对应 {@code locate_evidence}。
     *
     * <p>证据可以是摘抄的几段（换行或省略号隔开），每一段都必须逐字出现在某个片段里。
     * 模型偶尔会写错片段的编号，所以先在它声称的片段里找，找不到再到其余的片段里找。
     *
     * @param evidence 模型给的证据
     * @param claimed  模型声称证据所在的片段；没有声称或者编号不对时为 null
     * @param shown    这一次调用给模型看过的全部片段
     */
    public static Located locateEvidence(String evidence, Chunk claimed, List<Chunk> shown) {
        List<String> parts = TextMatch.quoteParts(evidence);
        if (parts.isEmpty()) {
            return new Located(claimed, new ArrayList<>(List.of("没有给出 evidence")));
        }
        List<Chunk> candidates = new ArrayList<>();
        if (claimed != null) {
            candidates.add(claimed);
        }
        for (Chunk c : shown) {
            if (c != claimed) {
                candidates.add(c);
            }
        }
        Chunk home = null;
        int longest = -1;
        for (String part : parts) {
            Chunk chunk = null;
            for (Chunk c : candidates) {
                if (c.source().supports(part)) {
                    chunk = c;
                    break;
                }
            }
            if (chunk == null) {
                // 这一段不是原文。找出最像的片段，方便之后定位和给模型提示；太不像的就不算
                Chunk nearest = null;
                double best = -1;
                for (Chunk c : candidates) {
                    double sim = c.source().similarity(part);
                    if (sim > best) {
                        best = sim;
                        nearest = c;
                    }
                }
                if (nearest != null && best < 0.5) {
                    nearest = null;
                }
                return new Located(nearest != null ? nearest : claimed, new ArrayList<>(List.of(
                        "evidence 必须逐字摘抄原文，一个字、一个数都不能改；片段里找不到这一段：" + TextNorm.oneLine(part, 60))));
            }
            // 引了几段时，以最长的那一段所在的片段为准
            int length = TextNorm.length(TextMatch.matchKey(part));
            if (length > longest) {
                home = chunk;
                longest = length;
            }
        }
        return new Located(home, new ArrayList<>());
    }

    /**
     * 把模型给出的值整理成字段的类型，并核对它确实能从证据里读出来。对应 {@code read_value}。
     *
     * @param field    字段的定义
     * @param raw      模型给的值
     * @param evidence 模型给的证据。为空时只整理类型，不核对（"没有证据"这件事由 {@link #locateEvidence} 报告）
     * @param chunk    证据所在的片段；没有定位到时为 null
     * @param block    证据所在的版面块；没有定位到时为 null
     */
    public static Read readValue(FieldSpec field, Object raw, String evidence, Chunk chunk, Block block) {
        String type = field.type();
        boolean hasEvidence = !TextNorm.strip(evidence).isEmpty();
        if (type.equals("number") || type.equals("integer")) {
            return readNumber(field, raw, evidence, hasEvidence, chunk, block);
        }
        if (type.equals("date")) {
            String date = Dates.parse(raw);
            if (date == null) {
                return new Read(raw, issue(PyFormat.repr(raw) + " 不是日期，日期要写成 YYYY-MM-DD"));
            }
            if (hasEvidence && !Dates.in(date, evidence)) {
                return new Read(date, issue("日期 " + date + " 和 evidence 里的日期对不上"));
            }
            return new Read(date, new ArrayList<>());
        }
        if (type.equals("boolean")) {
            if (raw instanceof Boolean) {
                return new Read(raw, new ArrayList<>());
            }
            String key = TextNorm.squash(PyFormat.str(raw));
            if (TRUE.contains(key)) {
                return new Read(Boolean.TRUE, new ArrayList<>());
            }
            if (FALSE.contains(key)) {
                return new Read(Boolean.FALSE, new ArrayList<>());
            }
            return new Read(raw, issue(PyFormat.repr(raw) + " 不是 true 或 false"));
        }
        if (type.equals("enum")) {
            String key = TextNorm.squash(PyFormat.str(raw));
            for (String option : field.options()) {
                if (TextNorm.squash(option).equals(key)) {
                    return new Read(option, new ArrayList<>()); // 用选项里的标准写法，不用模型的写法
                }
            }
            return new Read(raw, issue(PyFormat.repr(raw) + " 不在可选项 " + PyFormat.repr(field.options()) + " 中"));
        }
        // 字符串
        if (raw instanceof Map<?, ?> || raw instanceof List<?>) {
            return new Read(raw, issue("应该是一个字符串"));
        }
        String value = TextNorm.strip(PyFormat.str(raw));
        if (hasEvidence && field.verbatim()) {
            return new Read(value, checkVerbatim(value, evidence, chunk));
        }
        return new Read(value, new ArrayList<>());
    }

    /**
     * 整理一个数并和证据核对。对应 {@code _read_number_value}。
     * 模型照原文写出数字和单位，换算成字段要求的单位在这里做。
     */
    private static Read readNumber(FieldSpec field, Object raw, String evidence, boolean hasEvidence,
                                   Chunk chunk, Block block) {
        Numbers.Num num = Numbers.readNumber(raw);
        if (num == null) {
            return new Read(raw, issue(PyFormat.repr(raw) + " 不是数字"));
        }
        Long scale = num.scale();
        List<String> issues = new ArrayList<>();
        if (hasEvidence) {
            Numbers.Settled settled = Numbers.settleScale(field, num.value().abs(), scale, evidence, chunk, block);
            scale = settled.scale();
            issues = settled.issues();
            if (num.value().signum() > 0 && Numbers.markedNegative(num.value(), evidence)) {
                issues.add("evidence 里这个数是负数（带负号，或者用括号括着），值也要写成负数");
            }
        }
        // 数量级为 null：原文和模型都没有给单位，认为它已经是字段要求的单位
        Object value = Numbers.convert(num.value(), scale, field.unit());
        if (field.type().equals("integer") && value instanceof Double) {
            issues.add(0, PyFormat.str(value) + " 不是整数");
        }
        return new Read(value, issues);
    }

    /**
     * 照抄型的字符串：值必须是片段里的文字，并且证据引的确实是它所在的地方。对应 {@code _check_verbatim}。
     *
     * <p>两步都要查。只查第一步的话，模型可以给出一个原文里有的词，却引一段不相干的证据。
     */
    private static List<String> checkVerbatim(String value, String evidence, Chunk chunk) {
        SourceText source = chunk != null ? chunk.source() : new SourceText(evidence);
        if (!source.containsValue(value)) {
            return issue("值 " + PyFormat.repr(value) + " 不是原文里的文字，string 的值要照抄原文");
        }
        // 值在证据里；或者反过来，证据是值的一部分（值比证据长，比如证据中间用了省略号）
        if (new SourceText(evidence).containsValue(value) || new SourceText(value).supports(evidence)) {
            return new ArrayList<>();
        }
        return issue("evidence 里没有值 " + PyFormat.repr(value) + "，evidence 要摘抄这个值所在的那句原文");
    }

    private static List<String> issue(String message) {
        List<String> out = new ArrayList<>();
        out.add(message);
        return out;
    }
}
