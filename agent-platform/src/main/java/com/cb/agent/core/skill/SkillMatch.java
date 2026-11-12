package com.cb.agent.core.skill;

import java.util.List;

/**
 * 用规则判断这份文档是不是这个 skill 管的（方案第 9.4 节的规则路由）。对应清单里的 {@code match}。
 *
 * <p>三条件任意组合：标题命中 {@link #titleAny()} 里的一个，正文里的 {@link #keywords()}
 * 命中至少 {@link #minKeywords()} 个。都不写就是这个 skill 不做规则匹配，只能由人指定或由模型来选。
 *
 * @param titleAny    文档标题里出现其中任意一个就算命中
 * @param keywords    正文里的特征词，例如"拟任职务"、"呈报单位"
 * @param minKeywords 特征词至少要命中几个
 */
public record SkillMatch(List<String> titleAny, List<String> keywords, int minKeywords) {

    public static final SkillMatch NONE = new SkillMatch(List.of(), List.of(), 0);

    public SkillMatch {
        titleAny = List.copyOf(titleAny);
        keywords = List.copyOf(keywords);
        if (minKeywords < 0) {
            throw new IllegalArgumentException("minKeywords 不能是负数：" + minKeywords);
        }
    }

    /** 清单里一个匹配条件都没写。 */
    public boolean isEmpty() {
        return titleAny.isEmpty() && keywords.isEmpty();
    }
}
