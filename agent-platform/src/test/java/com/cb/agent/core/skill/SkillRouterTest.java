package com.cb.agent.core.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按规则选 skill：标题、特征词怎么算命中，得分怎么算，什么时候选、什么时候不选。
 */
class SkillRouterTest {

    private static final Skill FORM = skill("personnel-appointment",
            List.of("干部任免审批表", "任免审批表"), List.of("拟任职务", "拟免职务", "呈报单位", "家庭成员及主要社会关系"), 2);

    @Test
    void 标题和特征词都满足就选中_依据写在结论里() {
        SkillRouter.Decision decision = SkillRouter.route(List.of(FORM), "干部任免审批表",
                "姓名 张三 拟任职务 某局局长 呈报单位 某县委组织部");

        assertTrue(decision.routed());
        assertEquals("personnel-appointment", decision.skill());
        assertEquals(SkillRouter.METHOD_RULE, decision.method());
        assertEquals(0.75, decision.score(), "标题命中占一半，特征词 4 个命中 2 个占四分之一");
        assertEquals("标题里有 干部任免审批表，正文命中特征词 拟任职务、呈报单位", decision.reason());
        SkillRouter.Candidate candidate = decision.candidates().get(0);
        assertTrue(candidate.matched());
        assertEquals("干部任免审批表", candidate.titleHit());
        assertEquals(List.of("拟任职务", "呈报单位"), candidate.keywordHits());
    }

    @Test
    void 两样条件都写了就都要满足() {
        // 标题对，特征词只命中一个
        SkillRouter.Decision fewKeywords = SkillRouter.route(List.of(FORM), "干部任免审批表", "拟任职务 某局局长");
        assertFalse(fewKeywords.routed());
        assertEquals(SkillRouter.METHOD_NONE, fewKeywords.method());
        assertNull(fewKeywords.score());
        assertEquals("没有 skill 的 match 条件被满足", fewKeywords.reason());
        assertEquals(0.63, fewKeywords.candidates().get(0).score(), "没选中的也给出得分，查原因时用");
        assertFalse(fewKeywords.candidates().get(0).matched());

        // 特征词够了，标题不对
        SkillRouter.Decision wrongTitle = SkillRouter.route(List.of(FORM), "软件开发服务合同",
                "拟任职务 拟免职务 呈报单位");
        assertFalse(wrongTitle.routed());
        assertNull(wrongTitle.candidates().get(0).titleHit());
    }

    @Test
    void 比较时不管空白_识别出来的词中间可能有换行() {
        SkillRouter.Decision decision = SkillRouter.route(List.of(FORM), "干 部 任 免\n审批表",
                "拟任\n职务 某局局长；呈报\u00A0单位 某县委组织部");

        assertTrue(decision.routed());
        assertEquals(List.of("拟任职务", "呈报单位"), decision.candidates().get(0).keywordHits());
    }

    @Test
    void 只写了一样条件的_那一样占全部得分() {
        Skill titleOnly = skill("invoice", List.of("增值税专用发票"), List.of(), 0);
        Skill keywordsOnly = skill("report", List.of(), List.of("营业收入", "净利润", "资产总计", "审计意见"), 3);

        SkillRouter.Decision invoice = SkillRouter.route(List.of(titleOnly, keywordsOnly), "增值税专用发票", "金额 税额");
        assertEquals("invoice", invoice.skill());
        assertEquals(1.0, invoice.score());
        assertEquals("标题里有 增值税专用发票", invoice.reason());

        SkillRouter.Decision report = SkillRouter.route(List.of(titleOnly, keywordsOnly), null,
                "营业收入 100 净利润 20 资产总计 500");
        assertEquals("report", report.skill());
        assertEquals(0.75, report.score());
        assertEquals("正文命中特征词 营业收入、净利润、资产总计", report.reason());
    }

    @Test
    void 满足条件的不止一个时_得分拉不开就不选() {
        Skill a = skill("form-a", List.of("审批表"), List.of("拟任职务", "呈报单位"), 1);
        Skill b = skill("form-b", List.of("审批表"), List.of("拟任职务", "审批机关意见"), 1);

        // 两个都是标题命中、特征词命中一半，分不清
        SkillRouter.Decision tie = SkillRouter.route(List.of(b, a), "干部任免审批表", "拟任职务 某局局长");
        assertFalse(tie.routed());
        assertEquals("满足条件的不止一个，而且得分接近（form-a 0.75，form-b 0.75），分不清", tie.reason());
        assertEquals(List.of("form-a", "form-b"), tie.candidates().stream().map(SkillRouter.Candidate::name).toList(),
                "得分相同按名称排，结果不随给的顺序变");

        // 拉开了差距（1.0 对 0.75，超过 0.15）就选高的
        SkillRouter.Decision clear = SkillRouter.route(List.of(b, a), "干部任免审批表", "拟任职务 某局局长 呈报单位 某县委");
        assertEquals("form-a", clear.skill());
        assertEquals(1.0, clear.score());
        assertEquals(2, clear.candidates().size(), "没选中的候选也留在结论里");
    }

    @Test
    void 清单里没写match的skill不参加() {
        Skill noMatch = skill("weekly-report", List.of(), List.of(), 0);

        SkillRouter.Decision decision = SkillRouter.route(List.of(noMatch), "周报", "本周工作");

        assertFalse(decision.routed());
        assertEquals(List.of(), decision.candidates());
        assertEquals("没有哪个 skill 的清单里写了 match 条件，规则选不了", decision.reason());
        assertEquals("没有哪个 skill 的清单里写了 match 条件，规则选不了",
                SkillRouter.route(List.of(), "周报", "本周工作").reason());
    }

    @Test
    void 第一名恰好高出阈值时也能选中() {
        List<String> keywords = List.of("词1", "词2", "词3", "词4", "词5", "词6", "词7", "词8", "词9", "词10");
        Skill a = skill("form-a", List.of("审批表"), keywords, 1);
        Skill b = skill("form-b", List.of("审批表"), List.of("词1", "词2", "词3", "缺4", "缺5"), 1);
        SkillRouter.Decision decision = SkillRouter.route(List.of(a, b), "审批表", String.join(" ", keywords.subList(0, 9)));
        assertEquals("form-a", decision.skill(), "0.95 对 0.80，差距恰好为 0.15，不受浮点误差影响");
        assertEquals(0.95, decision.score());
    }

    @Test
    void 调用方指定的不用选() {
        SkillRouter.Decision decision = SkillRouter.explicit("personnel-appointment");

        assertTrue(decision.routed());
        assertEquals(SkillRouter.METHOD_EXPLICIT, decision.method());
        assertNull(decision.score());
        assertEquals("调用方指定", decision.reason());
    }

    @Test
    void 写进结果里的样子() {
        Map<String, Object> map = SkillRouter.route(List.of(FORM), "干部任免审批表", "拟任职务 呈报单位").toMap();

        assertEquals(List.of("skill", "method", "score", "reason", "candidates"), new ArrayList<>(map.keySet()));
        Map<?, ?> candidate = (Map<?, ?>) ((List<?>) map.get("candidates")).get(0);
        assertEquals(List.of("skill", "matched", "score", "title", "keywords"), new ArrayList<>(candidate.keySet()));
        assertEquals("干部任免审批表", candidate.get("title"));
        assertEquals(List.of("拟任职务", "呈报单位"), candidate.get("keywords"));
    }

    private static Skill skill(String name, List<String> titleAny, List<String> keywords, int minKeywords) {
        return new Skill(name, 1, name, "测试用", new SkillMatch(titleAny, keywords, minKeywords),
                Path.of(name), Path.of(name, "SKILL.md"), null, List.of(), Map.of(), List.of());
    }
}
