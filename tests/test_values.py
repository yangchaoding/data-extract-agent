"""原文比对、取值整理和核对的测试。"""

from decimal import Decimal

import pytest

from extract_agent.chunker import Chunk, chunk_document
from extract_agent.loader import document_from_result
from extract_agent.schema import Field
from extract_agent.textutil import SourceText, estimate_tokens, lexical_tokens, match_key, quote_parts, squash
from extract_agent.values import (
    arabic_numerals,
    date_in,
    locate_evidence,
    parse_cn_number,
    parse_date,
    read_number,
    read_value,
    unit_scale,
)

from support import block, html_table, page


def chunk(index: int, text: str) -> Chunk:
    return Chunk(index=index, text=text, path=(), blocks=[], page_start=None, page_end=None,
                 tokens=estimate_tokens(text))


SRC = SourceText(
    "3.1 本合同含税总价为人民币1,280,000.00元（大写：壹佰贰拾捌万元整），增值税税率为6%。\n"
    "| 项目 | 本期 | 上期 |\n| --- | --- | --- |\n| 营业收入 | 1,234,567.89 | 987,654.32 |"
)


def test_squash_ignores_width_spacing_quotes_and_invisible_characters():
    assert squash("“ＡＢＣ”  —  １２３") == '"abc"-123'
    assert squash("| 营业收入 | 1,234 |") == "营业收入1,234"
    assert squash("a​b ﻿c­|d") == "abcd"  # 零宽字符、BOM、软连字符


def test_match_key_drops_punctuation_but_keeps_numbers_whole():
    assert match_key("１，２３４.５０ 元（含税）") == "1234.50元含税"
    assert match_key("| 30% | 384,000.00 |") == "30 384000.00"  # 相邻单元格的数字不会粘在一起
    assert match_key("第3.1条：A-b") == "第3.1条ab"
    assert match_key("账号 6222 0212 3456") == "账号6222 0212 3456"
    assert match_key("账号 6222 0212 3456", glue_digit_groups=True) == "账号622202123456"
    assert match_key("| 6222 | 0212 |", glue_digit_groups=True) == "6222 0212"  # 隔着竖线的不连


@pytest.mark.parametrize(
    "quote",
    [
        "本合同含税总价为人民币 1,280,000.00 元",  # 多了空格
        "本合同含税总价为人民币1280000.00元(大写:壹佰贰拾捌万元整)",  # 没有千分位，半角标点
        "营业收入 1234567.89 987654.32",  # 表格的一行，去掉了竖线
        "| 项目 | 本期 | 上期 |\n| 营业收入 | 1,234,567.89 | 987,654.32 |",  # 表头加一行
        "本合同含税总价……增值税税率为6%",  # 中间用省略号略过
        "增值税税率为6%。",
    ],
)
def test_supports_verbatim_quotes(quote):
    assert SRC.supports(quote)


@pytest.mark.parametrize(
    "quote",
    [
        "本合同含税总价为人民币1,820,000.00元",  # 两位数字对调
        "本合同含税总价是人民币1,280,000.00元",  # 改了一个字
        "本合同含税总价为人民币1,280,000.00元……付款期限为30日",  # 后半段是编的
        "280,000.00元",  # 从一个数的中间截出来的
        "1,234,567.8",
        "本合同总价为人民币贰佰万元整",
        "",
        "| --- | --- |",  # 没有实际内容
    ],
)
def test_does_not_support_altered_quotes(quote):
    assert not SRC.supports(quote)


def test_similarity_is_only_a_hint():
    altered = "本合同含税总价为人民币1,820,000.00元"
    assert SRC.similarity(altered) >= 0.8 and not SRC.supports(altered)  # 很像，但不是原文
    assert SRC.similarity("乙方应于三十日内完成交付") < 0.2
    assert SRC.position("增值税税率") > SRC.position("本合同") >= 0 and SRC.position("没有的话") == -1


def test_quote_parts():
    assert quote_parts("| 项目 | 本期 |\n| --- | --- |\n| 营业收入 | 1 |") == ["| 项目 | 本期 |", "| 营业收入 | 1 |"]
    assert quote_parts("甲……乙...丙") == ["甲", "乙", "丙"]
    assert quote_parts("  \n ") == []


def test_grouped_numbers_can_be_written_together_in_values_only():
    src = SourceText("统一社会信用代码 9133 0100 MA2X YZ12 3A，账号 6222 0212 3456 7890")
    assert src.contains_value("91330100MA2XYZ123A") and src.contains_value("6222021234567890")
    assert not src.contains_value("6222021234567891")
    assert not src.supports("6222021234567890")  # 摘抄原文时不能改动分组


def test_token_estimate_and_lexical_tokens():
    assert estimate_tokens("") == 0
    assert 8 <= estimate_tokens("合同总金额为人民币") <= 12
    assert estimate_tokens("contract amount in RMB") < 12
    assert lexical_tokens("合同金额 Total-Amount 2024") == ["合同", "同金", "金额", "total-amount", "2024"]


@pytest.mark.parametrize(
    "raw, written, scale",
    [
        ("1,280,000.00", "1280000.00", None),
        (12.5, "12.5", None),
        (7, "7", None),
        ("人民币1,234.50元", "1234.50", 1),
        ("12.5%", "12.5", 1),
        ("(1,234)", "-1234", None),  # 会计写法的负数
        ("(1,234.56)万元", "-1234.56", 10**4),
        ("-56.7", "-56.7", None),
        ("3.5亿元", "3.5", 10**8),
        ("约1,200万元", "1200", 10**4),
        ("1,234.56（万元）", "1234.56", 10**4),
        ("5千元", "5", 10**3),
        ("5千克", "5", 1),  # 千克是单位，不是数量级
        ("2.5 million", "2.5", 10**6),
        ("0.52元/股", "0.52", 1),
        ("壹佰贰拾捌万元整", "1280000", 1),
        ("一亿二千万", "120000000", 1),
        ("三十日", "30", 1),
        ("二〇二四", "2024", None),
        ("百分之六", "6", None),
    ],
)
def test_read_number(raw, written, scale):
    assert read_number(raw) == (Decimal(written), scale)


@pytest.mark.parametrize("raw", ["若干", "", True, float("nan"), None])
def test_read_number_rejects_non_numbers(raw):
    assert read_number(raw) is None


def test_unit_scale():
    assert [unit_scale(u) for u in ("元", "万元", "人民币万元", "百万元", "亿元", "万股", "%", "千克", "元/股")] == [
        1, 10**4, 10**4, 10**6, 10**8, 10**4, 1, 1, 1,
    ]
    assert unit_scale("") is None and unit_scale("（万元）") is None
    assert parse_cn_number("壹佰贰拾捌万") == 1280000 and parse_cn_number("二〇二四") is None


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("2024年3月18日", "2024-03-18"),
        ("2024-3-8", "2024-03-08"),
        ("2024/03/18", "2024-03-18"),
        ("2024.03.18", "2024-03-18"),
        ("20240318", "2024-03-18"),
        ("2024-03-18T00:00:00", "2024-03-18"),
        ("二〇二四年三月十八日", "2024-03-18"),
        ("二○二四年十二月三十一日", "2024-12-31"),  # 用圆圈代替〇
        ("二O二四年一月五日", "2024-01-05"),  # 识别成了字母 O
        ("18/03/2024", "2024-03-18"),
        ("03/18/2024", "2024-03-18"),
        ("03/04/2024", None),  # 分不清是 3 月 4 日还是 4 月 3 日
        ("2024年3月", "2024-03"),
        ("2024年", "2024"),
        ("2024-13-01", None),
        ("三月十八日", None),
    ],
)
def test_parse_date(raw, expected):
    assert parse_date(raw) == expected


@pytest.mark.parametrize(
    "value, evidence, expected",
    [
        ("2024-03-18", "签订日期：2024年3月18日", True),
        ("2024-03-18", "签订日期：二〇二四年三月十八日", True),
        ("2024-03-18", "开票日期 20240318", True),
        ("2024-03-18", "签订于24年3月18日", True),
        ("2024-03-18", "March 18, 2024", True),
        ("2024-03-18", "18/03/2024", True),
        ("2024-12-31", "自2024年4月1日起至2024年12月31日止", True),
        ("2024-12-31", "自2024年4月1日起至12月31日止", True),  # 后一个日期没有再写年份
        ("2024-03", "2024年3月18日", True),
        ("2024", "2024年度报告", True),
        ("2024-03-19", "签订日期：2024年3月18日", False),
        ("2024-12-01", "自2024年4月1日起至2024年12月31日止", False),  # 两个日期各取一部分
        ("2024-04-31", "自2024年4月1日起至2024年12月31日止", False),
        ("2024-01-04", "2024年4月1日", False),  # 月和日对调
        ("2023-12-31", "2024年12月31日", False),
    ],
)
def test_date_in(value, evidence, expected):
    assert date_in(value, evidence) is expected


def test_arabic_numerals():
    assert arabic_numerals("二〇二四年三月十五日") == "2024年3月15日"
    assert arabic_numerals("共三十页，第十页，二十三份") == "共30页,第10页,23份"  # 顺带做了全半角归一


def test_locate_evidence_requires_verbatim_text_and_corrects_a_wrong_chunk_id():
    c1 = chunk(0, "甲方：星河智造科技有限公司\n合同总价为人民币1,280,000.00元。")
    c2 = chunk(1, "乙方：青岚软件技术有限公司")
    assert locate_evidence("乙方：青岚软件技术有限公司", c1, [c1, c2]) == (c2, [])  # 片段 id 写错了
    assert locate_evidence("乙方：青岚软件技术有限公司", None, [c1, c2]) == (c2, [])
    assert locate_evidence("甲方：星河智造科技有限公司……人民币1,280,000.00元", c1, [c1, c2]) == (c1, [])

    found, issues = locate_evidence("合同总价为人民币1,820,000.00元", c1, [c1, c2])
    assert found is c1 and "逐字摘抄" in issues[0] and "1,820,000.00" in issues[0]
    found, issues = locate_evidence("丙方：一家不存在的公司", c2, [c1, c2])
    assert found is c2 and "找不到" in issues[0]
    assert locate_evidence(" ", c1, [c1, c2])[1] == ["没有给出 evidence"]


def test_numbers_must_be_written_in_the_evidence():
    f = Field("amount", "number")
    ev = "本合同含税总价为人民币1,280,000.00元"
    assert read_value(f, "1,280,000.00元", ev) == (1280000, [])
    assert read_value(f, 1280000, ev) == (1280000, [])
    value, issues = read_value(f, 1820000, ev)  # 抄错了
    assert value == 1820000 and "evidence 里没有 1820000 这个数" in issues[0]
    assert read_value(f, 1280000, "合同总价（大写）：壹佰贰拾捌万元整") == (1280000, [])  # 原文只有大写金额
    assert read_value(Field("r", "number", unit="%"), "6%", "增值税税率为百分之六") == (6, [])
    assert read_value(Field("n", "integer"), 30, "收到发票后三十日内付款") == (30, [])
    assert read_value(Field("n", "integer"), "4份", "本合同一式四份") == (4, [])
    assert read_value(Field("n", "integer"), "3.5", "")[1] == ["3.5 不是整数"]
    assert read_value(f, "若干", "")[1] == ["'若干' 不是数字"]
    assert read_value(f, "1,280,000.00") == (1280000, [])  # 没有 evidence 时只整理类型


YUAN = Field("revenue", "number", unit="元")
WAN = Field("revenue", "number", unit="万元")
ROW = "| 营业收入 | 1,234.56 | 1,100.00 |"


def report(*extra_rows):
    """一张以万元为单位的表（单位写在表格上方），和一段正文。返回 (片段, 表格块, 正文块)。"""
    rows = [["项目", "本期", "上期"], ["营业收入", "1,234.56", "1,100.00"], ["净利润", "(56.78)", "20.00"],
            ["基本每股收益（元/股）", "0.52", "0.41"], ["净资产收益率", "12.35%", "11.02%"], *extra_rows]
    doc = document_from_result(page(0, [
        block("paragraph_title", "一、主要会计数据"),
        block("table_title", "表1 主要会计数据"),
        block("text", "单位：万元"),
        block("table", html_table(rows)),
        block("text", "公司注册资本为人民币5,000万元，总投资1.28亿元。"),
    ]))
    (c,) = chunk_document(doc, 5000)
    return c, doc.blocks[3], doc.blocks[4]


def test_the_unit_written_after_a_number_is_applied_by_the_program():
    c, _, para = report()
    ev = "公司注册资本为人民币5,000万元"
    assert read_value(YUAN, "5,000万元", ev, c, para) == (50000000, [])
    assert read_value(YUAN, "5,000", ev, c, para) == (50000000, [])  # 模型没带单位：以原文为准
    assert read_value(YUAN, 50000000, ev, c, para) == (50000000, [])  # 模型自己换算了，而且算对了
    assert read_value(WAN, "5,000万元", ev, c, para) == (5000, [])
    assert read_value(YUAN, "1.28亿元", "总投资1.28亿元", c, para) == (128000000, [])
    assert read_value(Field("n", "number"), "1.28", "总投资1.28亿元", c, para) == (128000000, [])  # 没指定单位：按基本单位
    value, issues = read_value(YUAN, "5,000亿元", ev, c, para)  # 单位抄错了
    assert value == 50000000 and "单位和原文注明的不一样" in issues[0]
    assert read_value(YUAN, "5千万元", ev, c, para) == (50000000, [])  # 换了一种写法，数值相同
    assert "evidence 里没有 5 这个数" in read_value(YUAN, "5百万元", ev, c, para)[1][0]  # 数值不同


def test_the_unit_stated_above_a_table_is_applied_by_the_program():
    c, table, _ = report()
    assert table.lead == ("表1 主要会计数据", "单位：万元")
    assert read_value(YUAN, "1,234.56万元", ROW, c, table) == (12345600, [])
    assert read_value(YUAN, "1,234.56", ROW, c, table) == (12345600, [])  # 没带单位：用表格上方注明的
    assert read_value(YUAN, 12345600, ROW, c, table) == (12345600, [])  # 模型自己换算了，而且算对了
    assert read_value(WAN, "1,234.56", ROW, c, table) == (1234.56, [])  # 字段要的就是万元
    assert read_value(WAN, 12345600, ROW, c, table) == (1234.56, [])
    value, issues = read_value(YUAN, "1,234.56亿元", ROW, c, table)
    assert value == 12345600 and "单位和原文注明的不一样" in issues[0]
    assert "evidence 里没有 1243.56 这个数" in read_value(YUAN, "1,243.56万元", ROW, c, table)[1][0]
    assert "evidence 里没有 123456 这个数" in read_value(YUAN, 123456, ROW, c, table)[1][0]  # 换算错了
    # 同一张表里别的单位的数不受"单位：万元"影响
    eps = Field("eps", "number", unit="元/股")
    assert read_value(eps, "0.52", "| 基本每股收益（元/股） | 0.52 | 0.41 |", c, table) == (0.52, [])
    roe = Field("roe", "number", unit="%")
    assert read_value(roe, "12.35%", "| 净资产收益率 | 12.35% | 11.02% |", c, table) == (12.35, [])
    assert read_value(roe, "12.35", "| 净资产收益率 | 12.35% | 11.02% |", c, table) == (12.35, [])
    # 字段没有指定单位：不知道它是不是金额，不套用表格的单位
    assert read_value(Field("n", "number"), "1,234.56", ROW, c, table) == (1234.56, [])


def test_a_unit_noted_in_the_row_itself_wins_over_the_table_unit():
    c, table, _ = report(["注册资本（元）", "50,000,000", "50,000,000"])
    own = "| 注册资本（元） | 50,000,000 | 50,000,000 |"
    assert read_value(YUAN, "50,000,000", own, c, table) == (50000000, [])
    # 这张表里注明了两种单位，别的行没带单位就分不清，要模型写明
    value, issues = read_value(YUAN, "1,234.56", ROW, c, table)
    assert "单位不止一种" in issues[0]
    assert read_value(YUAN, "1,234.56万元", ROW, c, table) == (12345600, [])


def test_a_unit_with_no_basis_in_the_text_is_flagged():
    c = chunk(0, "设备费用合计 128，人工费用合计 64。")
    ev = "设备费用合计 128"
    assert read_value(YUAN, "128", ev, c) == (128, [])
    value, issues = read_value(YUAN, "128万元", ev, c)
    assert value == 1280000 and "找不到这个单位的依据" in issues[0]


def test_negative_numbers_in_accounting_notation():
    c, table, _ = report()
    row = "| 净利润 | (56.78) | 20.00 |"
    assert read_value(YUAN, "(56.78)万元", row, c, table) == (-567800, [])
    assert read_value(YUAN, "-56.78", row, c, table) == (-567800, [])
    value, issues = read_value(YUAN, "56.78万元", row, c, table)  # 丢了负号
    assert value == 567800 and "负数" in issues[0]
    assert read_value(YUAN, "20.00", row, c, table) == (200000, [])
    assert read_value(Field("n", "number"), "6", "增值税税率（6）") == (6, [])  # 正文里的括号不是负号


def test_dates_must_be_written_in_the_evidence():
    f = Field("d", "date")
    assert read_value(f, "2024年3月18日", "签订日期：2024年3月18日") == ("2024-03-18", [])
    value, issues = read_value(f, "2024-03-19", "签订日期：2024年3月18日")
    assert value == "2024-03-19" and "对不上" in issues[0]
    assert "不是日期" in read_value(f, "明年春天", "")[1][0]


def test_verbatim_strings_must_be_the_documents_own_words():
    c = chunk(0, "甲方（委托方）：星河智造科技有限公司，统一社会信用代码 9133 0100 MA2X YZ12 3A\n开户账号：6222 0212 3456 7890")
    name = Field("party_a")
    ev = "甲方（委托方）：星河智造科技有限公司"
    assert read_value(name, " 星河智造科技有限公司 ", ev, c) == ("星河智造科技有限公司", [])
    assert "不是原文里的文字" in read_value(name, "星河智能科技有限公司", ev, c)[1][0]  # 改了一个字
    # 值是原文，但 evidence 引的是别处
    assert "evidence 里没有值" in read_value(name, "星河智造科技有限公司", "开户账号：6222 0212 3456 7890", c)[1][0]
    # 分组书写的号码可以连着写
    account = Field("account")
    assert read_value(account, "6222021234567890", "开户账号：6222 0212 3456 7890", c)[1] == []
    assert read_value(account, "6222021234567891", "开户账号：6222 0212 3456 7890", c)[1] != []
    # 允许概括的字段不要求是原文
    assert read_value(Field("s", verbatim=False), "甲方是一家科技公司", ev, c) == ("甲方是一家科技公司", [])
    assert read_value(name, {"x": 1}, ev, c)[1] == ["应该是一个字符串"]


def test_enum_and_boolean():
    way = Field("way", "enum", options=["诉讼", "仲裁"])
    assert read_value(way, " 仲裁 ", "提交杭州仲裁委员会") == ("仲裁", [])
    assert "不在可选项" in read_value(way, "调解", "")[1][0]
    flag = Field("flag", "boolean")
    assert read_value(flag, "是") == (True, []) and read_value(flag, False) == (False, [])
    assert "不是 true 或 false" in read_value(flag, "也许")[1][0]
