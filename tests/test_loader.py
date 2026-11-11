import json

import pytest

from extract_agent.loader import (
    document_from_result,
    heading_style,
    load_document,
    load_documents,
    plain_text,
)

from support import EXAMPLES, block, html_table, page

HEADER = ["期次", "金额"]


def test_blocks_are_classified_and_page_numbers_start_at_one():
    doc = document_from_result(
        [
            page(0, [
                block("doc_title", "采购合同"),
                block("text", "甲方：某某公司"),
                block("paragraph_title", "第一条 标的"),
                block("table_title", "表1 清单"),
                block("table", html_table([["名称", "数量"], ["螺栓", "10"]])),
                block("number", "1"),
                block("image", ""),
            ]),
            page(1, [block("footnote", "注：以上价格含税"), block("seal", "某某公司合同专用章")]),
        ],
        name="c",
    )
    assert doc.title == "采购合同" and doc.page_count == 2
    assert [(b.kind, b.page) for b in doc.blocks] == [
        ("heading", 1), ("text", 1), ("heading", 1), ("caption", 1), ("table", 1),
        ("footnote", 2), ("seal", 2),
    ]
    table = doc.blocks[4]
    assert table.table is not None and table.text.startswith("| 名称 | 数量 |")
    assert table.path == ("第一条 标的",)


@pytest.mark.parametrize(
    "title, style",
    [
        ("第一章 总则", "chapter"),
        ("第二节 定义", "section"),
        ("第十二条 违约责任", "article"),
        ("三、付款方式", "cn"),
        ("（二）验收", "cn_paren"),
        ("1.2 范围", "num2"),
        ("3 交付", "num1"),
        ("(1) 首付款", "num_paren"),
        ("Chapter 2 Scope", "word"),
        ("2024年度报告", None),
        ("概述", None),
    ],
)
def test_heading_style(title, style):
    assert heading_style(title) == style


def test_heading_levels_follow_numbering_styles():
    titles = ["第一章 总则", "第一节 定义", "一、术语", "二、解释", "第二节 适用", "第二章 价款", "一、总价"]
    doc = document_from_result(page(None, [block("paragraph_title", t) for t in titles] + [block("text", "正文")]))
    assert [b.level for b in doc.blocks[:-1]] == [1, 2, 3, 3, 2, 1, 2]
    assert doc.blocks[-1].path == ("第二章 价款", "一、总价")


def test_cross_page_table_is_merged_when_result_was_not_restructured():
    doc = document_from_result([
        page(0, [
            block("header", "某某公司 合同"),
            block("text", "付款计划如下："),
            block("table", html_table([HEADER, ["第一期", "100"]])),
            block("number", "1"),
        ]),
        page(1, [
            block("header", "某某公司 合同"),
            block("table_title", "续表"),
            block("table", html_table([HEADER, ["第二期", "200"]])),
            block("text", "以上金额均含税。"),
        ]),
    ])
    tables = [b for b in doc.blocks if b.kind == "table"]
    assert len(tables) == 1
    assert tables[0].table.rows == [HEADER, ["第一期", "100"], ["第二期", "200"]]
    assert (tables[0].page, tables[0].page_end) == (1, 2)
    assert "续表" not in [b.text for b in doc.blocks]
    assert [b.text for b in doc.blocks].count("某某公司 合同") == 1  # 重复的页眉只留一次


def test_tables_are_not_merged_when_they_are_not_a_continuation():
    first = block("table", html_table([HEADER, ["第一期", "100"]]))
    # 下一页的表格前面有正文
    doc = document_from_result([
        page(0, [first]),
        page(1, [block("text", "另有一张表："), block("table", html_table([HEADER, ["甲", "1"]]))]),
    ])
    assert len([b for b in doc.blocks if b.kind == "table"]) == 2
    # 列数不同
    doc = document_from_result([
        page(0, [first]),
        page(1, [block("table", html_table([["a", "b", "c"], ["1", "2", "3"]]))]),
    ])
    assert len([b for b in doc.blocks if b.kind == "table"]) == 2
    # 宽度相差很多
    doc = document_from_result([
        page(0, [first]),
        page(1, [block("table", html_table([HEADER, ["甲", "1"]]), bbox=(100, 100, 500, 300))]),
    ])
    assert len([b for b in doc.blocks if b.kind == "table"]) == 2


def test_restructured_result_with_merged_table_keeps_page_range():
    merged = html_table([HEADER, ["第一期", "100"], ["第二期", "200"]])
    doc = document_from_result([
        page(0, [block("table", merged, global_block_id=0, global_group_id=0)]),
        page(1, [
            block("table", "", global_block_id=1, global_group_id=0),
            block("text", "后续正文", global_block_id=2, global_group_id=2),
        ]),
    ])
    assert [b.kind for b in doc.blocks] == ["table", "text"]
    assert (doc.blocks[0].page, doc.blocks[0].page_end) == (1, 2)


def test_format_block_content_markup_is_stripped():
    doc = document_from_result(page(None, [
        block("doc_title", "# 年度报告"),
        block("paragraph_title", "### 1.1 主要会计数据"),
        block("figure_title", '<div style="text-align: center;">图1 收入趋势</div>\n'),
        block("image", '<div style="text-align: center;"><img src="imgs/a.jpg" alt="Image" width="50%" /></div>\n'),
        block("table", "\n<table border=1 style='margin: auto;'><tr><td style='text-align: center;'>项目</td>"
                       "<td>金额</td></tr><tr><td>收入</td><td>1</td></tr></table>"),
    ]))
    assert [(b.kind, b.text.splitlines()[0]) for b in doc.blocks] == [
        ("heading", "年度报告"),
        ("heading", "1.1 主要会计数据"),
        ("caption", "图1 收入趋势"),
        ("table", "| 项目 | 金额 |"),
    ]
    assert doc.blocks[1].level == 2
    assert plain_text("第一行<br>第二行 ![](imgs/x.png)") == "第一行\n第二行"


def test_service_response_and_concatenated_result():
    pruned = {"page_count": None, "parsing_res_list": [block("text", "第一页")]}
    response = {"logId": "x", "errorCode": 0, "result": {"layoutParsingResults": [
        {"prunedResult": pruned, "markdown": {"text": "第一页"}},
        {"prunedResult": {"parsing_res_list": [block("text", "第二页")]}, "markdown": {"text": "第二页"}},
    ]}}
    doc = document_from_result(response)
    assert [(b.text, b.page) for b in doc.blocks] == [("第一页", 1), ("第二页", 2)]

    # restructure_pages(concatenate_pages=True)：所有页合成一个结果，块上没有页码
    doc = document_from_result({"res": page(None, [block("text", "甲"), block("text", "乙")], count=5)})
    assert doc.page_count == 5 and [b.page for b in doc.blocks] == [None, None]

    with pytest.raises(ValueError):
        document_from_result({"foo": 1})


def test_markdown_input(tmp_path):
    md = tmp_path / "report.md"
    md.write_text(
        "# 年度报告\n\n## 一、公司简介\n\n公司名称：某某股份有限公司\n\n"
        '<div style="text-align: center;"><img src="imgs/a.jpg" alt="Image" width="40%" /></div>\n\n'
        "<table border=1><tr><td>项目</td><td>金额</td></tr>\n<tr><td>收入</td><td>1</td></tr></table>\n\n"
        "| 年份 | 收入 |\n| --- | --- |\n| 2024 | 12 |\n",
        encoding="utf-8",
    )
    doc = load_document(md)
    assert doc.title == "年度报告"
    assert [b.kind for b in doc.blocks] == ["heading", "heading", "text", "table", "table"]
    assert doc.blocks[2].path == ("一、公司简介",)
    assert doc.blocks[4].table.rows == [["年份", "收入"], ["2024", "12"]]


def test_directory_with_several_documents(tmp_path):
    for name, n_pages in (("a", 2), ("b", 11)):
        for i in range(n_pages):
            data = page(i, [block("text", f"{name} 第{i + 1}页")], count=n_pages, input_path=f"/in/{name}.pdf")
            (tmp_path / f"{name}_{i}_res.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "notes.json").write_text('{"unrelated": true}', encoding="utf-8")
    docs = {d.name: d for d in load_documents(tmp_path)}
    assert set(docs) == {"a", "b"}
    assert [b.text for b in docs["b"].blocks] == [f"b 第{i}页" for i in range(1, 12)]  # 第 10 页排在第 2 页之后
    with pytest.raises(ValueError):
        load_document(tmp_path)


def test_markdown_pages_in_a_directory(tmp_path):
    (tmp_path / "doc_0.md").write_text("# 标题\n\n第一页", encoding="utf-8")
    (tmp_path / "doc_1.md").write_text("第二页", encoding="utf-8")
    (tmp_path / "report_2023.md").write_text("另一份文档", encoding="utf-8")
    docs = {d.name: d for d in load_documents(tmp_path)}
    assert set(docs) == {"doc", "report_2023"}
    assert [(b.text, b.page) for b in docs["doc"].blocks] == [("标题", 1), ("第一页", 1), ("第二页", 2)]


def test_sample_contract_loads():
    doc = load_document(EXAMPLES / "ocr" / "sample_contract")
    assert doc.name == "sample_contract" and doc.page_count == 3
    (table,) = [b for b in doc.blocks if b.kind == "table"]
    assert [row[0] for row in table.table.rows] == ["期次", "第一期", "第二期", "第三期", "第四期"]
    assert (table.page, table.page_end) == (2, 3)
