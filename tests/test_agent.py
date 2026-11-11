"""agent 主流程的测试。模型用假的：RuleLLM 按正则在给出的片段里找答案，ScriptedLLM 按脚本回复。"""

from extract_agent import ExtractionAgent, load_document, load_schema, schema_from_dict
from extract_agent.loader import document_from_result
from extract_agent.prompts import ROUTE_SYSTEM

from support import (
    CONTRACT_RULES,
    EXAMPLES,
    RuleLLM,
    ScriptedLLM,
    asked_fields,
    block,
    html_table,
    page,
    shown_chunks,
)

EVIDENCE = "3.1 本合同含税总价为人民币1,280,000.00元"


def sample_contract():
    return load_document(EXAMPLES / "ocr" / "sample_contract")


def one_field(**attrs):
    return schema_from_dict({"fields": [{"name": "total_amount", "type": "number", **attrs}]})


def test_small_document_is_extracted_in_as_few_calls_as_possible():
    llm = RuleLLM(CONTRACT_RULES)
    result = ExtractionAgent(llm).run(sample_contract(), load_schema(EXAMPLES / "schemas" / "contract.yaml"))

    assert result.chunks == 1  # 整份合同不到上下文预算，不切片
    assert len(llm.calls) == 2  # 13 个字段，每次调用最多 8 个；不需要定位
    assert {name: r.status for name, r in result.fields.items()} == dict.fromkeys(CONTRACT_RULES, "ok")
    assert result.needs_review == []
    data = result.data
    assert data["party_a"] == "星河智造科技有限公司"
    assert data["sign_date"] == "2024-03-18"
    assert data["total_amount"] == 1280000
    assert (data["term_start"], data["term_end"]) == ("2024-04-01", "2024-12-31")
    assert data["dispute_resolution"] == "仲裁"
    # 付款计划表跨了两页，四期都在，数值已经整理成数字
    assert data["payment_schedule"] == [
        {"stage": "第一期", "condition": "合同签订后10个工作日内", "ratio": 30, "amount": 384000},
        {"stage": "第二期", "condition": "《需求规格说明书》经甲方确认后10个工作日内", "ratio": 20, "amount": 256000},
        {"stage": "第三期", "condition": "系统上线并经甲方验收合格后10个工作日内", "ratio": 40, "amount": 512000},
        {"stage": "第四期", "condition": "免费维护期届满后10个工作日内", "ratio": 10, "amount": 128000},
    ]


def test_every_value_carries_its_source():
    result = ExtractionAgent(RuleLLM(CONTRACT_RULES)).run(
        sample_contract(), load_schema(EXAMPLES / "schemas" / "contract.yaml")
    )
    out = result.to_dict()
    amount = out["fields"]["total_amount"]
    assert amount == {
        "status": "ok",
        "value": 1280000,
        "evidence": "含税总价为人民币1,280,000.00元",
        "chunk": "c1",
        "pages": "2",  # 片段覆盖 1-3 页，出处精确到证据所在的版面块
        "bbox": [120, 210, 1120, 290],
        "section": "第三条 合同价款及支付",
    }
    stages = out["fields"]["payment_schedule"]["items"]
    assert [s["pages"] for s in stages] == ["2-3"] * 4  # 跨页表格
    assert out["fields"]["dispute_venue"]["pages"] == "3"
    assert out["summary"]["ok"] == 13 and out["summary"]["chunks"] == 1


def test_fabricated_evidence_is_flagged_not_trusted():
    made_up = {"total_amount": {"value": 2000000, "evidence": "合同总价为人民币贰佰万元整，分三期支付", "chunk": "c1"}}
    llm = ScriptedLLM([made_up])
    result = ExtractionAgent(llm).run(sample_contract(), one_field(required=True))

    r = result.fields["total_amount"]
    assert r.status == "unverified" and r.value == 2000000  # 值保留，但标记为需要复核
    assert "找不到" in r.items[0].issues[0]
    assert result.needs_review == ["total_amount"]
    assert len(llm.calls) == 2  # 第一次 + 一次带着问题的重问
    assert "没有通过核对" in llm.calls[1][1] and "贰佰万元整" in llm.calls[1][1]


def test_wrong_value_is_caught_and_fixed_on_retry():
    llm = ScriptedLLM([
        {"total_amount": {"value": 1820000, "evidence": EVIDENCE, "chunk": "c1"}},  # 抄错了数字
        {"total_amount": {"value": 1280000, "evidence": EVIDENCE, "chunk": "c1"}},
    ])
    result = ExtractionAgent(llm).run(sample_contract(), one_field())

    assert result.fields["total_amount"].status == "ok"
    assert result.data == {"total_amount": 1280000}
    assert len(llm.calls) == 2
    assert "evidence 里没有 1820000 这个数" in llm.calls[1][1]


def test_wrong_chunk_id_is_corrected():
    llm = ScriptedLLM([{"total_amount": {"value": "1,280,000.00", "evidence": EVIDENCE, "chunk": "c7"}}])
    result = ExtractionAgent(llm).run(sample_contract(), one_field())
    r = result.fields["total_amount"]
    assert r.status == "ok" and r.items[0].chunk.id == "c1" and len(llm.calls) == 1


def test_reply_formats_models_actually_produce():
    answer = '{"total_amount": {"value": 1280000, "evidence": "%s", "chunk": "c1"}}' % EVIDENCE
    for reply in (
        f"```json\n{answer}\n```",
        f"<think>先找合同价款条款……</think>\n{answer}",
        f"抽取结果如下：\n{answer}\n以上。",
        '{"result": %s}' % answer,  # 多包了一层
        '{"total_amount": 1280000}',  # 没按格式给证据：有值，但通不过核对
    ):
        result = ExtractionAgent(ScriptedLLM([reply]), retries=0).run(sample_contract(), one_field())
        assert result.data == {"total_amount": 1280000}, reply
    assert result.fields["total_amount"].status == "unverified"


def test_invalid_json_is_retried_then_reported():
    good = {"total_amount": {"value": 1280000, "evidence": EVIDENCE, "chunk": "c1"}}
    llm = ScriptedLLM(["抱歉，我没有找到。", good])
    assert ExtractionAgent(llm).run(sample_contract(), one_field()).data == {"total_amount": 1280000}
    assert "不是合法的 JSON" in llm.calls[1][1]

    result = ExtractionAgent(ScriptedLLM(["抱歉，我没有找到。"])).run(sample_contract(), one_field())
    r = result.fields["total_amount"]
    assert r.status == "missing" and "不是合法的 JSON" in r.issues[0]  # 不会悄悄当成"文档里没有"


def test_missing_fields_are_reported():
    schema = schema_from_dict({"fields": [
        {"name": "guarantor", "title": "担保人", "required": True},
        {"name": "fax", "title": "传真"},
    ]})
    result = ExtractionAgent(RuleLLM({})).run(sample_contract(), schema)
    assert result.data == {"guarantor": None, "fax": None}
    assert result.fields["guarantor"].issues == ["必填字段没有找到"]
    assert result.fields["fax"].issues == []
    assert result.needs_review == ["guarantor"]


FILLER = "本节内容为一般性约定，双方应当遵守诚实信用原则，按照约定全面履行各自的义务。" * 3


def long_contract():
    """六节，每节一片。金额在第四节，而且用的不是 schema 里预想的说法。"""
    sections = [
        ("第一条 定义", "合同总价的定义见附件一。"),
        ("第二条 服务范围", ""),
        ("第三条 结算", "合同总价的结算方式由双方另行约定。"),
        ("第四条 费用", "本项目费用共计人民币500,000元。"),
        ("第五条 发票", "合同总价对应的发票由乙方开具。"),
        ("第六条 其他", ""),
    ]
    blocks = []
    for title, lead in sections:
        blocks += [block("paragraph_title", title), block("text", lead + FILLER)]
    return document_from_result(page(None, blocks))


AMOUNT_SCHEMA = one_field(title="合同总金额", hints=["合同总价"])
AMOUNT_RULE = {"total_amount": r"费用共计人民币([\d,]+)元"}
SMALL = dict(chunk_tokens=150, context_tokens=320, top_k=2, max_rounds=4)


def test_search_widens_when_the_first_candidates_do_not_have_the_value():
    llm = RuleLLM(AMOUNT_RULE)
    result = ExtractionAgent(llm, route=False, **SMALL).run(long_contract(), AMOUNT_SCHEMA)

    assert result.chunks == 6
    r = result.fields["total_amount"]
    assert r.status == "ok" and r.value == 500000
    assert r.items[0].chunk.section == "第四条 费用"
    # 关键词检索先给出的是三个提到"合同总价"的片段，都没有金额；第三轮才轮到第四节
    assert r.rounds == 3 and len(llm.calls) == 3
    first = shown_chunks(llm.calls[0][1])
    assert all("合同总价" in text for text in first.values()) and len(first) == 2


def test_routing_by_outline_finds_it_in_the_first_round():
    llm = RuleLLM(AMOUNT_RULE, route={"total_amount": ["c4"]})
    result = ExtractionAgent(llm, **SMALL).run(long_contract(), AMOUNT_SCHEMA)

    assert llm.calls[0][0] == ROUTE_SYSTEM
    assert "c4 | 第1页 | 第四条 费用 |" in llm.calls[0][1]  # 模型看到的是目录
    r = result.fields["total_amount"]
    assert r.status == "ok" and r.rounds == 1 and len(llm.extraction_calls) == 1


def test_unusable_routing_reply_falls_back_to_keyword_search():
    llm = RuleLLM(AMOUNT_RULE, route={"total_amount": ["c99", 4], "unknown": ["c1"]})
    result = ExtractionAgent(llm, **SMALL).run(long_contract(), AMOUNT_SCHEMA)
    assert result.fields["total_amount"].status == "ok"


def test_search_stops_after_max_rounds():
    llm = RuleLLM(AMOUNT_RULE)
    result = ExtractionAgent(llm, route=False, **{**SMALL, "max_rounds": 2}).run(long_contract(), AMOUNT_SCHEMA)
    r = result.fields["total_amount"]
    assert r.status == "missing" and r.rounds == 2 and len(llm.calls) == 2


def test_fields_with_the_same_candidates_share_one_call():
    schema = schema_from_dict({"fields": [
        {"name": "total_amount", "type": "number", "hints": ["费用共计"]},
        {"name": "fee_clause", "hints": ["费用共计"]},
        {"name": "invoice_by", "hints": ["发票", "开具"]},
    ]})
    llm = RuleLLM({**AMOUNT_RULE, "fee_clause": r"(本项目费用共计人民币[\d,]+元)", "invoice_by": r"发票由(乙方)开具"})
    result = ExtractionAgent(llm, route=False, **{**SMALL, "top_k": 1}).run(long_contract(), schema)

    assert result.to_dict()["summary"]["ok"] == 3
    calls = [(asked_fields(u), sorted(shown_chunks(u))) for u in llm.extraction_calls]
    assert calls == [(["total_amount", "fee_clause", "invoice_by"], ["c4", "c5"])]


def shareholders_report():
    rows = [["股东名称", "期末持股数量", "持股比例(%)"]]
    rows += [[f"股东{i:02d}有限公司", f"{(60 - i) * 1_000_000:,}", f"{(60 - i) / 10:.2f}"] for i in range(1, 31)]
    return document_from_result([
        page(0, [block("doc_title", "2024年年度报告"), block("paragraph_title", "第一节 公司简介"), block("text", FILLER * 2)]),
        page(1, [
            block("paragraph_title", "第二节 股东情况"),
            block("table_title", "表1 前三十名股东持股情况"),
            block("text", "单位：股"),
            block("table", html_table(rows)),
        ]),
        page(2, [block("paragraph_title", "第三节 其他事项"), block("text", FILLER * 2)]),
    ])


SHAREHOLDERS = schema_from_dict({"fields": [{
    "name": "shareholders", "title": "股东", "type": "list", "hints": ["股东名称", "持股比例"],
    "fields": [
        {"name": "name", "required": True},
        {"name": "shares", "type": "number"},
        {"name": "ratio", "type": "number"},
    ],
}]})
SHAREHOLDER_RULE = {"shareholders": (r"\| (股东\d+有限公司) \| ([\d,]+) \| ([\d.]+) \|", ["name", "shares", "ratio"])}


def test_list_collects_rows_from_every_segment_of_a_split_table():
    llm = RuleLLM(SHAREHOLDER_RULE)
    agent = ExtractionAgent(llm, route=False, chunk_tokens=200, context_tokens=450, top_k=1)
    result = agent.run(shareholders_report(), SHAREHOLDERS)

    r = result.fields["shareholders"]
    assert r.status == "ok"
    assert [it["name"] for it in r.value] == [f"股东{i:02d}有限公司" for i in range(1, 31)]  # 不缺不重，顺序不变
    assert r.value[0] == {"name": "股东01有限公司", "shares": 59000000, "ratio": 5.9}
    assert {it.to_dict()["pages"] for it in r.items} == {"2"}
    assert len({it.chunk.id for it in r.items}) >= 3  # 表格确实被拆成了多片
    # top_k=1 只挑了一片，其余分段是因为同属一张表被带上的
    for user in llm.extraction_calls[:2]:
        assert all("单位：股" in text for text in shown_chunks(user).values())


def test_list_follows_into_neighbouring_chunks():
    stages = [("第一期", "100,000"), ("第二期", "200,000"), ("第三期", "300,000")]
    blocks = [block("paragraph_title", "第一条 定义"), block("text", FILLER)]
    for n, (stage, amount) in enumerate(stages, 2):
        blocks += [block("paragraph_title", f"第{n}条 {stage}付款"), block("text", f"{stage}款项为人民币{amount}元。" + FILLER)]
    blocks += [block("paragraph_title", "第五条 其他"), block("text", FILLER)]
    schema = schema_from_dict({"fields": [{
        "name": "payments", "type": "list", "hints": ["第二期"],  # 关键词只能命中中间那一期
        "fields": [{"name": "stage"}, {"name": "amount", "type": "number"}],
    }]})
    llm = RuleLLM({"payments": (r"(第.期)款项为人民币([\d,]+)元", ["stage", "amount"])})
    agent = ExtractionAgent(llm, route=False, chunk_tokens=150, context_tokens=320, top_k=1)
    result = agent.run(document_from_result(page(None, blocks)), schema)

    r = result.fields["payments"]
    assert r.value == [
        {"stage": "第一期", "amount": 100000},
        {"stage": "第二期", "amount": 200000},
        {"stage": "第三期", "amount": 300000},
    ]
    assert r.status == "ok" and r.rounds == 3  # 命中第二期 → 看两侧 → 再往外一圈没有新条目，停
    assert len(llm.calls) == 3


PAYMENTS = schema_from_dict({"fields": [{
    "name": "payments", "type": "list",
    "fields": [{"name": "stage"}, {"name": "amount", "type": "number"}],
}]})
ROW_1 = "| 第一期 | 合同签订后10个工作日内 | 30% | 384,000.00 |"
ROW_2 = "| 第二期 | 《需求规格说明书》经甲方确认后10个工作日内 | 20% | 256,000.00 |"
ROW_3 = "| 第三期 | 系统上线并经甲方验收合格后10个工作日内 | 40% | 512,000.00 |"


def test_list_items_that_fail_verification_are_marked_individually():
    llm = ScriptedLLM([{"payments": [
        {"value": {"stage": "第一期", "amount": 384000}, "evidence": ROW_1, "chunk": "c1"},
        {"value": {"stage": "第五期", "amount": 99999}, "evidence": "| 第五期 | 质保期满 | 5% | 99,999.00 |", "chunk": "c1"},
        {"value": {"stage": None, "amount": None}, "evidence": "", "chunk": "c1"},
    ]}])
    result = ExtractionAgent(llm).run(sample_contract(), PAYMENTS)
    r = result.fields["payments"]
    assert r.status == "unverified" and len(r.items) == 2  # 空项被丢掉
    assert r.items[0].verified and not r.items[1].verified


def test_retry_that_returns_only_the_fixed_item_keeps_the_verified_ones():
    llm = ScriptedLLM([
        {"payments": [
            {"value": {"stage": "第三期", "amount": 512000}, "evidence": ROW_3, "chunk": "c1"},
            {"value": {"stage": "第二期", "amount": 265000}, "evidence": ROW_2, "chunk": "c1"},  # 抄错了数字
            {"value": {"stage": "第一期", "amount": 384000}, "evidence": ROW_1, "chunk": "c1"},
        ]},
        {"payments": [{"value": {"stage": "第二期", "amount": 256000}, "evidence": ROW_2, "chunk": "c1"}]},
    ])
    r = ExtractionAgent(llm).run(sample_contract(), PAYMENTS).fields["payments"]
    assert r.status == "ok"
    assert r.value == [  # 一项不少，并且按原文的先后排，不管模型是按什么顺序给的
        {"stage": "第一期", "amount": 384000},
        {"stage": "第二期", "amount": 256000},
        {"stage": "第三期", "amount": 512000},
    ]
    assert "完整的列表" in llm.calls[1][1] and "payments 的第 2 项" in llm.calls[1][1]


def test_loosely_shaped_replies_are_understood():
    # 列表的一项没有包 value，子字段直接平铺
    flat = {"payments": [{"stage": "第一期", "amount": "384,000.00", "evidence": ROW_1, "chunk": "c1"}]}
    r = ExtractionAgent(ScriptedLLM([flat])).run(sample_contract(), PAYMENTS).fields["payments"]
    assert r.status == "ok" and r.value == [{"stage": "第一期", "amount": 384000}]
    # 列表字段只给了一个对象
    single = {"payments": {"value": {"stage": "第一期", "amount": 384000}, "evidence": ROW_1, "chunk": "c1"}}
    r = ExtractionAgent(ScriptedLLM([single])).run(sample_contract(), PAYMENTS).fields["payments"]
    assert r.status == "ok" and len(r.items) == 1
    # 单值字段给了一个列表
    listed = {"total_amount": [{"value": 1280000, "evidence": EVIDENCE, "chunk": "c1"}]}
    r = ExtractionAgent(ScriptedLLM([listed])).run(sample_contract(), one_field()).fields["total_amount"]
    assert r.status == "ok" and r.value == 1280000


def test_empty_document():
    result = ExtractionAgent(RuleLLM({})).run(document_from_result(page(None, [])), one_field(required=True))
    assert result.chunks == 0 and result.needs_review == ["total_amount"]


def scattered_dates():
    """日期散落在各节里，关键词检索没法一次找全。"""
    blocks = []
    for n, day in enumerate(("2024年5月1日", "2024年6月15日", "2024年9月30日", "2024年12月31日"), 1):
        blocks += [block("paragraph_title", f"第{n}条 事项{n}"), block("text", f"乙方应于{day}前完成本条约定的工作。" + FILLER)]
    return document_from_result(page(None, blocks))


def test_exhaustive_field_reads_the_whole_document():
    def schema(exhaustive):
        return schema_from_dict({"fields": [{"name": "deadlines", "type": "list", "exhaustive": exhaustive,
                                             "hints": ["事项1"], "fields": [{"name": "date", "type": "date"}]}]})

    rule = {"deadlines": (r"应于(\d+年\d+月\d+日)前", ["date"])}
    small = dict(route=False, chunk_tokens=150, context_tokens=320, top_k=1, max_rounds=2)
    partial = ExtractionAgent(RuleLLM(rule), **small).run(scattered_dates(), schema(False))
    assert len(partial.data["deadlines"]) < 4  # 只看最相关的片段和它的邻居，找不全

    llm = RuleLLM(rule)
    full = ExtractionAgent(llm, **small).run(scattered_dates(), schema(True))
    assert full.data["deadlines"] == [
        {"date": "2024-05-01"}, {"date": "2024-06-15"}, {"date": "2024-09-30"}, {"date": "2024-12-31"},
    ]
    assert full.fields["deadlines"].rounds == 1
    assert sorted(cid for user in llm.calls for cid in shown_chunks(user[1])) == ["c1", "c2", "c3", "c4"]


class TruncatingLLM(RuleLLM):
    """输出装不下太多内容的模型：一次超过 max_fields 个字段或 max_chunks 个片段就被截断。"""

    def __init__(self, rules, max_fields=1, max_chunks=1):
        super().__init__(rules)
        self.max_fields, self.max_chunks = max_fields, max_chunks

    def complete(self, system, user):
        from extract_agent.llm import OutputTruncated

        if len(asked_fields(user)) > self.max_fields or len(shown_chunks(user)) > self.max_chunks:
            self.calls.append((system, user))
            raise OutputTruncated("截断")
        return super().complete(system, user)


def test_truncated_output_splits_the_job_instead_of_losing_it():
    schema = schema_from_dict({"fields": [
        {"name": "total_amount", "type": "number", "hints": ["费用共计"]},
        {"name": "invoice_by", "hints": ["费用共计"]},
    ]})
    rules = {**AMOUNT_RULE, "invoice_by": r"发票由(乙方)开具"}
    agent = ExtractionAgent(TruncatingLLM(rules), route=False, chunk_tokens=150, context_tokens=2000, top_k=6)
    result = agent.run(long_contract(), schema)
    assert result.data == {"total_amount": 500000, "invoice_by": "乙方"}
    assert result.needs_review == []


def test_truncation_that_cannot_be_split_further_is_reported():
    agent = ExtractionAgent(TruncatingLLM(SHAREHOLDER_RULE, max_chunks=0), route=False,
                            chunk_tokens=200, context_tokens=450, top_k=1)
    result = agent.run(shareholders_report(), SHAREHOLDERS)
    r = result.fields["shareholders"]
    assert r.status == "missing" and "被截断" in r.issues[0]


def test_one_bad_segment_marks_a_list_as_incomplete():
    class Flaky(RuleLLM):
        def complete(self, system, user):
            from extract_agent.llm import OutputTruncated

            if "股东30有限公司" in user:  # 最后一个分段总是被截断
                raise OutputTruncated("截断")
            return super().complete(system, user)

    agent = ExtractionAgent(Flaky(SHAREHOLDER_RULE), route=False, chunk_tokens=200, context_tokens=200, top_k=1)
    r = agent.run(shareholders_report(), SHAREHOLDERS).fields["shareholders"]
    assert 0 < len(r.items) < 30
    assert r.status == "unverified" and "被截断" in r.issues[0]  # 拿到的条目都对，但不能当成完整的列表
