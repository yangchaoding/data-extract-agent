package com.cb.agent.core.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按规则给一份文档选 skill。对应方案第 9.4 节三级路由里的第一级。
 *
 * <p>规则写在每个 skill 清单的 {@code match} 里（{@link SkillMatch}）：标题里出现某个词，
 * 正文里的特征词至少命中几个。两样都写了就都要满足。这里不调用模型，结果是确定的，也不花钱。
 *
 * <p>怎么算选中：
 * <ol>
 *   <li>满足自己 {@code match} 条件的 skill 才是"命中"的；清单里没写 {@code match} 的不参加；</li>
 *   <li>每个 skill 有一个得分（0 到 1）：标题命中占一半，特征词命中的比例占一半；只写了其中一样的，那一样占全部；</li>
 *   <li>命中的只有一个，就是它；有几个时，第一名的得分要比第二名高出 {@link #MARGIN} 才算，
 *       否则认为分不清，不选。</li>
 * </ol>
 * 不选的时候返回"未能确定"和候选，由调用方决定怎么办（方案里的后两级：问模型、交给人，还没有做）。
 *
 * <p>现在特征词是按个数算的，没有按区分度加权（方案 9.4 的设计要点之一）。skill 只有一两个时没有差别；
 * skill 多起来、出现几个 skill 共用的词时，要和路由回归（9.5）一起加上。
 *
 * <p>这个类不知道"文档"是什么，只要标题和正文两段文字，所以放在 core 里。
 */
public final class SkillRouter {

    /** 命中的 skill 不止一个时，第一名要比第二名高出这么多才选它。 */
    public static final double MARGIN = 0.15;

    /** 调用方指定的。 */
    public static final String METHOD_EXPLICIT = "explicit";
    /** 规则选中的。 */
    public static final String METHOD_RULE = "rule";
    /** 没能确定。 */
    public static final String METHOD_NONE = "none";

    private SkillRouter() {
    }

    /**
     * 一个候选 skill 的匹配情况。
     *
     * @param name        skill 的名称
     * @param matched     是否满足它清单里的条件
     * @param score       得分，0 到 1
     * @param titleHit    标题里命中的那个词；没命中或者没有这个条件时为 null
     * @param keywordHits 正文里命中的特征词
     */
    public record Candidate(String name, boolean matched, double score, String titleHit, List<String> keywordHits) {

        public Candidate {
            keywordHits = List.copyOf(keywordHits);
        }

        /**
         * 写进结果里的样子。键都是一个词：结果里别的部分沿用 Python 版的下划线写法，接口的外层用驼峰，
         * 一个词的键放在哪边都不别扭。{@code title} 是标题里命中的词，{@code keywords} 是正文里命中的特征词。
         */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("skill", name);
            out.put("matched", matched);
            out.put("score", score);
            out.put("title", titleHit);
            out.put("keywords", keywordHits);
            return out;
        }
    }

    /**
     * 一次路由的结论，连同依据。选错了的时候靠它查是哪里的问题。
     *
     * @param skill      选中的 skill 的名称；没能确定时为 null
     * @param method     哪一级定的：{@link #METHOD_EXPLICIT}、{@link #METHOD_RULE}、{@link #METHOD_NONE}
     * @param score      选中的 skill 的得分；调用方指定的、没能确定的为 null
     * @param reason     一句话说明为什么选它，或者为什么没选
     * @param candidates 参加比较的 skill，按得分从高到低
     */
    public record Decision(String skill, String method, Double score, String reason, List<Candidate> candidates) {

        public Decision {
            candidates = List.copyOf(candidates);
        }

        /** 有没有选出来。 */
        public boolean routed() {
            return skill != null;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("skill", skill);
            out.put("method", method);
            out.put("score", score);
            out.put("reason", reason);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Candidate candidate : candidates) {
                list.add(candidate.toMap());
            }
            out.put("candidates", list);
            return out;
        }
    }

    /** 调用方直接指定了 skill，不用选。 */
    public static Decision explicit(String skill) {
        return new Decision(skill, METHOD_EXPLICIT, null, "调用方指定", List.of());
    }

    /**
     * 按规则选。
     *
     * @param skills 可以选的 skill。清单里没写 {@code match} 的不参加比较
     * @param title  文档的标题；没有时传 null 或空串
     * @param text   文档的正文
     */
    public static Decision route(List<Skill> skills, String title, String text) {
        String head = squeeze(title);
        String body = squeeze(text);
        List<Candidate> candidates = new ArrayList<>();
        for (Skill skill : skills) {
            if (!skill.match().isEmpty()) {
                candidates.add(score(skill, head, body));
            }
        }
        // 得分高的在前；得分相同按名称排，结果不随目录的遍历顺序变
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::name));

        List<Candidate> matched = candidates.stream().filter(Candidate::matched).toList();
        if (matched.isEmpty()) {
            String reason = candidates.isEmpty()
                    ? "没有哪个 skill 的清单里写了 match 条件，规则选不了"
                    : "没有 skill 的 match 条件被满足";
            return new Decision(null, METHOD_NONE, null, reason, candidates);
        }
        Candidate best = matched.get(0);
        // 得分保留两位小数，按百分制整数比较；0.95 - 0.80 的浮点结果会略小于 0.15，不能误拒绝边界上的命中。
        if (matched.size() > 1 && Math.round(best.score() * 100) - Math.round(matched.get(1).score() * 100)
                < Math.round(MARGIN * 100)) {
            return new Decision(null, METHOD_NONE, null, "满足条件的不止一个，而且得分接近（"
                    + best.name() + " " + best.score() + "，" + matched.get(1).name() + " "
                    + matched.get(1).score() + "），分不清", candidates);
        }
        return new Decision(best.name(), METHOD_RULE, best.score(), reason(best), candidates);
    }

    private static Candidate score(Skill skill, String head, String body) {
        SkillMatch match = skill.match();
        String titleHit = null;
        for (String word : match.titleAny()) {
            if (head.contains(squeeze(word))) {
                titleHit = word;
                break;
            }
        }
        List<String> hits = new ArrayList<>();
        for (String word : match.keywords()) {
            if (body.contains(squeeze(word))) {
                hits.add(word);
            }
        }
        boolean hasTitle = !match.titleAny().isEmpty();
        boolean hasKeywords = !match.keywords().isEmpty();
        boolean matched = (!hasTitle || titleHit != null) && (!hasKeywords || hits.size() >= match.minKeywords());
        double titleScore = titleHit != null ? 1.0 : 0.0;
        double keywordScore = hasKeywords ? (double) hits.size() / match.keywords().size() : 0.0;
        double score = hasTitle && hasKeywords ? 0.5 * titleScore + 0.5 * keywordScore
                : hasTitle ? titleScore : keywordScore;
        // 留两位小数：得分是给人看的依据，不需要一长串小数
        return new Candidate(skill.name(), matched, Math.round(score * 100) / 100.0, titleHit, hits);
    }

    private static String reason(Candidate best) {
        List<String> parts = new ArrayList<>();
        if (best.titleHit() != null) {
            parts.add("标题里有 " + best.titleHit());
        }
        if (!best.keywordHits().isEmpty()) {
            parts.add("正文命中特征词 " + String.join("、", best.keywordHits()));
        }
        return String.join("，", parts);
    }

    /**
     * 去掉所有空白再比较。识别出来的文字里，一个词中间可能被换行或空格隔开（表格的栏目名常常分两行写），
     * 清单里的词则是连着写的。
     */
    private static String squeeze(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // isWhitespace 不认不换行空格，单独去掉
            if (!Character.isWhitespace(c) && c != '\u00A0') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
