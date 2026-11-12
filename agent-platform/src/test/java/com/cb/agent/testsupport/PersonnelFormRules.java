package com.cb.agent.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例任免表（{@code samples/personnel_form_1.6.json}）各栏的找法，给假模型 {@link RuleLlm} 用。
 *
 * <p>示例表是虚构的。这里的规则照着它切片之后的文字写：每一栏是"| 栏目名 | 值 |"的形式。
 * 表里空着的栏（专业技术职务、拟免职务）和表里没有的栏（审批机关意见、行政任免机关意见）不给规则，
 * 结果里它们应该是"没找到"。
 *
 * <p>教程里演示用的假模型服务（{@code tools/demo_model.py}）用的是同一套规则。
 */
public final class PersonnelFormRules {

    private PersonnelFormRules() {
    }

    public static Map<String, RuleLlm.Rule> rules() {
        Map<String, RuleLlm.Rule> rules = new LinkedHashMap<>();
        rules.put("姓名", RuleLlm.Rule.value("\\| 姓名 \\| (\\S+) \\| 性别"));
        rules.put("性别", RuleLlm.Rule.value("性别 \\| (\\S+) \\|"));
        rules.put("出生年月（岁）", RuleLlm.Rule.value("出生年月（岁） \\| (\\S+) \\|"));
        rules.put("民族", RuleLlm.Rule.value("\\| 民族 \\| (\\S+) \\|"));
        rules.put("籍贯", RuleLlm.Rule.value("籍贯 \\| (\\S+) \\|"));
        rules.put("出生地", RuleLlm.Rule.value("出生地 \\| (\\S+) \\|"));
        rules.put("入党时间", RuleLlm.Rule.value("入党时间 \\| (\\S+) \\|"));
        rules.put("参加工作时间", RuleLlm.Rule.value("参加工作时间 \\| (\\S+) \\|"));
        rules.put("健康状况", RuleLlm.Rule.value("健康状况 \\| (\\S+) \\|"));
        rules.put("熟悉专业有何专长", RuleLlm.Rule.value("熟悉专业有何专长 \\| (\\S+) \\|"));
        rules.put("全日制教育", RuleLlm.Rule.value("全日制教育 \\| (\\S+) \\|"));
        rules.put("全日制教育毕业院校系及专业",
                RuleLlm.Rule.value("全日制教育 \\| \\S+ \\| \\S+ \\| 毕业院校系及专业 \\| (\\S+) \\|"));
        rules.put("在职教育", RuleLlm.Rule.value("在职教育 \\| (\\S+) \\|"));
        rules.put("在职教育毕业院校系及专业",
                RuleLlm.Rule.value("在职教育 \\| \\S+ \\| \\S+ \\| 毕业院校系及专业 \\| (\\S+) \\|"));
        rules.put("现任职务", RuleLlm.Rule.value("\\| 现任职务 \\| (\\S+) \\|"));
        rules.put("拟任职务", RuleLlm.Rule.value("\\| 拟任职务 \\| (\\S+) \\|"));
        rules.put("简历", RuleLlm.Rule.value("\\| 简历 \\| (.+?) \\| 〃"));
        rules.put("奖惩情况", RuleLlm.Rule.value("\\| 奖惩情况 \\| (\\S+) \\|"));
        rules.put("年度考核结果", RuleLlm.Rule.value("\\| 年度考核结果 \\| (.+?) \\| 〃"));
        rules.put("任免理由", RuleLlm.Rule.value("\\| 任免理由 \\| (\\S+) \\|"));
        rules.put("呈报单位", RuleLlm.Rule.value("\\| 呈报单位 \\| (\\S+) \\|"));
        // 年龄那一格要是数字，这样表头那一行（称谓 | 姓名 | 年龄 …）不会被当成一个人
        rules.put("家庭成员及主要社会关系", RuleLlm.Rule.items(
                "\\| 家庭主要成员及重要社会关系 \\| (\\S+) \\| (\\S+) \\| (\\d+) \\| (\\S+) \\| (\\S+) \\|",
                "称谓", "姓名", "年龄", "政治面貌", "工作单位及职务"));
        return rules;
    }
}
