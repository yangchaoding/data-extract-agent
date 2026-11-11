"""生成差分测试用的基准数据：用 Python 版（参考实现）处理一批输入，把结果存下来。

Java 版的测试读同样的输入，结果必须和这里存下来的逐项相同。不一样就是移植出了问题。

用法（在仓库根目录，用装了 extract-agent 的 Python）：

    python agent-platform/tools/make_golden.py

生成的文件都在 agent-platform/src/test/resources/ 下：

    fixtures/paddle/<用例名>.json      输入：PaddleOCR-VL 格式的识别结果
    fixtures/paddle/sample_contract/   输入：从 examples/ocr/sample_contract 复制来的三页
    golden/paddle/<用例名>.json        Python 版读出来的文档结构
    golden/textnorm.json               文本工具（token 估算、归一化）的输入和结果
    golden/tables.json                 表格解析的输入和结果

什么时候要重新生成：改了 Python 版的读取、表格、文本工具，或者想加新的用例。
加用例就是在下面的 paddle_cases() 里加一项。

有三种情况是 Java 版故意和 Python 版不一样的，所以这里的用例都避开了它们，它们在 Java 的单元测试里单独验证：
  1. 表格单元格里的 <br> 和字面的 \\n（Java 版保留成换行）；
  2. 文档最前面的块是图题或表题（Java 版把它当作文档标题）；
  3. 跨页表格每一行的页码（Python 版没有这项信息）。
"""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # 仓库根目录
sys.path.insert(0, str(ROOT / "src"))

from extract_agent.loader import document_from_result, load_documents  # noqa: E402
from extract_agent.tables import tables_from_content  # noqa: E402
from extract_agent.textutil import estimate_tokens, normalize_chars, one_line, squash  # noqa: E402

RES = ROOT / "agent-platform" / "src" / "test" / "resources"
FIXTURES = RES / "fixtures" / "paddle"
GOLDEN = RES / "golden"


# ---------------------------------------------------------------------------
# 导出：Python 版的 Document → 普通的 dict。字段名和 Java 版 DocumentDump 的相同
# ---------------------------------------------------------------------------


def dump_table(table) -> dict:
    return {"rows": table.rows, "header_rows": table.header_rows, "explicit_header": table.explicit_header}


def dump_document(doc) -> dict:
    return {
        "name": doc.name,
        "title": doc.title,
        "page_count": doc.page_count,
        "blocks": [
            {
                "id": b.id,
                "kind": b.kind,
                "label": b.label,
                "text": b.text,
                "page": b.page,
                "page_end": b.page_end,
                "bbox": b.bbox,
                "level": b.level,
                "path": list(b.path),
                "lead": list(b.lead),
                "tokens": b.tokens,
                "table": None if b.table is None else dump_table(b.table),
            }
            for b in doc.blocks
        ],
    }


def write(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


# ---------------------------------------------------------------------------
# 拼 PaddleOCR-VL 格式的识别结果
# ---------------------------------------------------------------------------


def block(label, content, bbox=(100, 100, 1100, 160), **extra) -> dict:
    return {"block_label": label, "block_content": content, "block_bbox": list(bbox), **extra}


def page(index, blocks, count=None, input_path="doc.pdf") -> dict:
    """一页的识别结果，结构和 save_to_json() 保存的文件相同。"""
    return {
        "input_path": input_path,
        "page_index": index,
        "page_count": count,
        "width": 1200,
        "height": 1700,
        "parsing_res_list": [{"block_id": i, "block_order": i + 1, "group_id": i, **b} for i, b in enumerate(blocks)],
    }


def html_table(rows, tag="td") -> str:
    body = "".join("<tr>" + "".join(f"<{tag}>{c}</{tag}>" for c in row) + "</tr>" for row in rows)
    return f"<table>{body}</table>"


def th_table(header, rows) -> str:
    """表头用 <th> 明确标出的表格。"""
    head = "<tr>" + "".join(f"<th>{c}</th>" for c in header) + "</tr>"
    body = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in row) + "</tr>" for row in rows)
    return f"<table>{head}{body}</table>"


def paddle_cases() -> dict:
    """用例名 → 识别结果。每个用例针对读取时的一种处理。"""
    cases = {}

    # 标题层级：按编号样式推断，先出现的样式层级高；回到见过的样式时层级跟着回去
    cases["headings"] = [
        page(0, [
            block("doc_title", "某某股份有限公司 2024 年年度报告"),
            block("paragraph_title", "第一章 公司简介"),
            block("text", "本公司成立于 2001 年。"),
            block("paragraph_title", "一、基本情况"),
            block("text", "注册资本 5,000 万元。"),
            block("paragraph_title", "（一）注册信息"),
            block("text", "统一社会信用代码：91330100MA2XXXXXXX"),
            block("paragraph_title", "（二）股东情况"),
            block("text", "控股股东为某某集团。"),
            block("paragraph_title", "二、经营范围"),
            block("text", "软件开发、技术服务。"),
        ], count=2),
        page(1, [
            block("paragraph_title", "第二章 财务数据"),
            block("paragraph_title", "1 资产"),
            block("paragraph_title", "1.1 流动资产"),
            block("text", "货币资金 1,234.56 万元。"),
            block("paragraph_title", "1.2 非流动资产"),
            block("text", "固定资产 789.00 万元。"),
            block("paragraph_title", "重要提示"),
            block("text", "以上数据未经审计。"),
            block("paragraph_title", "2 负债"),
            block("text", "短期借款 100.00 万元。"),
            block("doc_title", "附件一 审计报告"),
            block("text", "我们审计了后附的财务报表。"),
            block("paragraph_title", "2024年度工作总结"),
            block("text", "这个标题以年份开头，不是编号。"),
        ], count=2),
    ]

    # 页眉页脚：同样的内容只留第一次出现（数字不同也算相同）；只有页码的不要；图和页码块跳过
    cases["margins"] = [
        page(n, [
            block("header", "某某公司 2024 年度报告"),
            block("header_image", ""),
            block("text", f"第 {n + 1} 页的正文。"),
            block("aside_text", "内部资料 注意保密"),
            block("number", str(n + 1)),
            block("footer", f"第 {n + 1} 页 共 3 页"),
            block("footer", str(n + 1)),
        ], count=3)
        for n in range(3)
    ]

    # 跨页表格：续表上方只有"续表"字样和重复的题注、单位说明，表头在下一页重复了一遍
    head = ["项目", "本期", "上期"]
    cases["cross_page_table"] = [
        page(0, [
            block("doc_title", "财务报告"),
            block("paragraph_title", "一、主要财务指标"),
            block("table_title", "表1 主要财务指标"),
            block("text", "单位：万元"),
            block("table", th_table(head, [["营业收入", "12,345.67", "10,000.00"], ["营业成本", "8,000.00", "7,000.00"]]),
                  bbox=(100, 300, 1100, 900)),
            block("footer", "第 1 页"),
        ], count=3),
        page(1, [
            block("header", "财务报告"),
            block("text", "续表"),
            block("table_title", "表1 主要财务指标"),
            block("text", "单位：万元"),
            block("table", th_table(head, [["净利润", "1,234.00", "1,000.00"], ["每股收益", "0.56", "0.45"]]),
                  bbox=(100, 200, 1100, 700)),
            block("text", "上表数据经审计。"),
            block("paragraph_title", "二、分部信息"),
            block("table", html_table([["分部", "收入"], ["华东", "100"], ["华北", "200"]]), bbox=(100, 900, 1100, 1500)),
        ], count=3),
        page(2, [
            # 上一页以表格结尾，这一页以表格开头，列数相同，没有重复表头：接上去
            block("table", html_table([["华南", "300"], ["西部", "400"]]), bbox=(100, 150, 1100, 500)),
            block("text", "分部数据未经审计。"),
        ], count=3),
    ]

    # 不该拼的情况：列数不同；宽度差得多；中间隔着别的正文；下一页的表有自己不同的表头
    cases["cross_page_no_merge"] = [
        page(0, [
            block("doc_title", "表格集"),
            block("table", html_table([["a", "b", "c"], ["1", "2", "3"]]), bbox=(100, 300, 1100, 900)),
        ], count=4),
        page(1, [
            block("table", html_table([["a", "b", "c", "d"], ["1", "2", "3", "4"]]), bbox=(100, 100, 1100, 500)),
        ], count=4),
        page(2, [
            block("table", html_table([["x", "y", "z", "w"], ["5", "6", "7", "8"]]), bbox=(100, 100, 600, 500)),
            block("text", "这段文字把两张表隔开了。"),
            block("table", th_table(["甲", "乙"], [["1", "2"]]), bbox=(100, 900, 1100, 1500)),
        ], count=4),
        page(3, [
            block("table", th_table(["丙", "丁"], [["3", "4"]]), bbox=(100, 100, 1100, 500)),
        ], count=4),
    ]

    # restructure_pages 已经合并过的跨页表格：后半段内容为空，用 global_group_id 指向前半段
    cases["restructured"] = [
        page(0, [
            block("doc_title", "合并过的结果"),
            block("table", html_table([["序号", "名称"], ["1", "甲"], ["2", "乙"], ["3", "丙"]]),
                  bbox=(100, 300, 1100, 1500), global_block_id=5, global_group_id=5),
        ], count=2),
        page(1, [
            block("table", "", bbox=(100, 100, 1100, 400), global_block_id=9, global_group_id=5),
            block("text", "表格之后的文字。", global_block_id=10, global_group_id=10),
        ], count=2),
    ]

    # 合并成一页的结果：没有 page_index，却声明了不止一页，块上没有页码
    cases["concatenated"] = page(None, [
        block("doc_title", "合并成一页的文档"),
        block("paragraph_title", "第一条 总则"),
        block("text", "本合同自签订之日起生效。"),
        block("paragraph_title", "第二条 价款"),
        block("table", html_table([["期次", "金额"], ["第一期", "100"]])),
    ], count=3)

    # 各种版面类别
    cases["misc_labels"] = [
        page(0, [
            block("doc_title", "各种类别"),
            block("abstract_title", "摘要"),
            block("abstract", "这是摘要的内容。"),
            block("content", "第一章 …… 1\n第二章 …… 5"),
            block("paragraph_title", "1 正文"),
            block("text", "带脚注的正文。"),
            block("footnote", "注：数据来源于年报。"),
            block("vision_footnote", "图注：示意图。"),
            block("formula", "E = mc^2"),
            block("formula_number", "(1)"),
            block("display_formula", "a^2 + b^2 = c^2"),
            block("seal", "某某公司合同专用章"),
            block("figure_title", "图1 组织结构"),
            block("image", ""),
            block("chart", "类别 | 数值\n甲 | 10\n乙 | 20"),
            block("table", "<fcel>姓名<fcel>年龄<nl><fcel>张三<fcel>30<nl><fcel>李四<ecel><nl>"),
            block("table", "这块内容识别不出表格"),
            block("text", None),
            block("reference_title", "参考文献"),
            block("reference", "[1] 某某. 某书. 2020."),
            block("unknown_label", "不认识的类别按正文处理。"),
        ], count=1),
    ]

    # 开了 format_block_content：标题前面带 #，正文里带排版标签和图片
    cases["format_block_content"] = [
        page(0, [
            block("doc_title", "# 带格式的文档"),
            block("paragraph_title", "## 第一节 概述"),
            block("text", '<div style="text-align: center;">居中的一段文字</div>'),
            block("paragraph_title", "### 1.1 背景"),
            block("text", "图片之前 ![](imgs/a.png) 图片之后"),
            block("text", "第一行<br>第二行<br/>第三行"),
            block("paragraph_title", "## 第二节 范围"),
            block("text", "<p>段落一</p><p>段落二</p>"),
            block("text", "a < b 且 c > d，这不是标签。"),
        ], count=1),
    ]

    # 服务接口的返回：最外面包着 result.layoutParsingResults[].prunedResult
    p0 = page(0, [block("doc_title", "服务接口的返回"), block("text", "第一页。")], count=2)
    p1 = page(1, [block("text", "第二页。")], count=2)
    cases["service_response"] = {
        "logId": "abc",
        "result": {"layoutParsingResults": [{"prunedResult": p0, "markdown": {"text": "…"}}, {"prunedResult": p1}],
                   "dataInfo": {"numPages": 2}},
        "errorCode": 0,
    }

    # 每页单独调一次服务，结果是数组；每个响应里没有 page_index 和 page_count（真实的 1.6 输出就是这样）
    def single(blocks):
        p = page(None, blocks)
        del p["input_path"]
        return {"logId": "x", "result": {"layoutParsingResults": [{"prunedResult": p}]}, "errorCode": 0}

    cases["response_list"] = [
        single([block("doc_title", "逐页调用的结果"),
                block("table", html_table([["栏目", "内容"], ["姓名", "张三"]]), bbox=(104, 257, 1083, 1453))]),
        single([block("table", html_table([["民族", "汉族"], ["籍贯", "云南"]]), bbox=(126, 189, 1105, 1454)),
                block("vision_footnote", "填表人：某某单位")]),
    ]

    # 旧的外层包装：{"res": {...}}
    cases["res_wrapper"] = {"res": page(0, [block("doc_title", "旧写法"), block("text", "内容。")], count=1)}

    # 表格的各种写法：合并单元格、分组表头、长文字用"同上"代替、嵌套表格、标签没闭合、全空的行
    long_text = "这是一段超过二十四个字的很长的说明文字，合并单元格展开时不会逐格重复"
    cases["table_shapes"] = [
        page(0, [
            block("doc_title", "各种形状的表格"),
            block("table", '<table><tr><td rowspan="2">项目</td><td colspan="2">金额</td></tr>'
                           "<tr><td>本期</td><td>上期</td></tr><tr><td>收入</td><td>100</td><td>90</td></tr></table>"),
            block("table", f'<table><tr><td>名称</td><td>说明</td><td>备注</td></tr>'
                           f'<tr><td>甲</td><td colspan="2">{long_text}</td></tr>'
                           f'<tr><td rowspan="2">乙</td><td>短说明</td><td>无</td></tr><tr><td>另一行</td><td>无</td></tr></table>'),
            block("table", "<table><tr><td>外层<table><tr><td>内1</td><td>内2</td></tr></table></td><td>右边</td></tr>"
                           "<tr><td>第二行</td><td>值</td></tr></table>"),
            block("table", "<table><tr><td>没有闭合<td>的标签<tr><td>第二行<td>也一样</table>"),
            block("table", "<table><tr><td>a</td><td>b</td></tr><tr><td></td><td></td></tr><tr><td>c</td><td>d</td></tr></table>"),
            block("table", '<table><tr><td style="text-align: center;">带 | 竖线</td><td>  多个   空格  </td></tr>'
                           "<tr><td>x</td><td>y</td></tr></table>"),
            block("table", "<table><tr><th>全是表头</th><th>没有数据</th></tr></table>"),
        ], count=1),
    ]
    return cases


# ---------------------------------------------------------------------------
# 文本工具和表格解析的基准
# ---------------------------------------------------------------------------

TEXTS = [
    "",
    "合同总金额",
    "含税总价为人民币1,280,000.00元",
    "Hello, World! 123",
    "  前后有空白\t中间\n有换行  ",
    "全角（括号）和，标点：ＡＢＣ１２３",
    "\u201c弯引号\u201d和\u2018单引号\u2019、\u300c直角引号\u300d\u300e双直角\u300f\u201e低位",
    "破折号\u2014连接号\u2013减号\u2212连字符\u2010不换行连字符\u2011横线\u2015",
    "表格 | 竖线 | 分隔",
    "不换行空格\u00a0窄空格\u202f全角空格\u3000宽空格\u2003下一行符\u0085结束",
    "零宽\u200b字符\u200c在\u200d这里\ufeff软连字符\u00ad结束",
    "生僻字\U00020000和表情\U0001f600各占两个 char",
    "ｈａｌｆ ｗｉｄｔｈ 与 ㈱ ① ㎡ 这些兼容字符",
    "日本語のひらがなとカタカナ、한국어",
    "MiXeD CaSe \u0130stanbul \u01c5",
    "2011.07-2015.07 昆明学院文学专业学习毕业",
    "| 姓名 | 张三 | 性别 | 男 |",
    "一" * 100,
    "a" * 100,
    "1" * 100,
    "，。！？" * 10,
    "这是一段比较长的文字，用来测试压成一行之后按字数截断的行为，超过限制的部分会被去掉并加上省略号。",
]

TABLE_CONTENTS = [
    html_table([["姓名", "年龄"], ["张三", "30"]]),
    th_table(["项目", "金额"], [["收入", "100"], ["成本", "80"]]),
    '<table><tr><td rowspan="2">项目</td><td colspan="2">金额</td></tr><tr><td>本期</td><td>上期</td></tr>'
    "<tr><td>收入</td><td>100</td><td>90</td></tr></table>",
    '<table><tr><td rowspan="4">照片</td><td>姓名</td><td>张三</td></tr><tr><td>性别</td><td>男</td></tr>'
    "<tr><td>民族</td><td>汉族</td></tr><tr><td>籍贯</td><td>云南</td></tr><tr><td>简历</td><td colspan=\"2\">无</td></tr></table>",
    "<table><tr><th>a</th><th>b</th></tr><tr><th>c</th><th>d</th></tr><tr><td>1</td><td>2</td></tr></table>",
    "<table><tr><th>只有表头</th></tr></table>",
    "<table><tr><td>只有一行</td><td>两列</td></tr></table>",
    "<table></table>",
    "<table><tr></tr><tr><td>前面有空行</td></tr><tr><td>第二行</td></tr></table>",
    '<table><tr><td colspan="999">列合并数特别大</td></tr><tr><td>a</td></tr></table>',
    '<table><tr><td rowspan="abc">写错的 rowspan</td><td colspan="0">零</td></tr><tr><td>1</td><td>2</td></tr></table>',
    "<table><tr><td>外<table><tr><td>内1</td><td>内2</td></tr><tr><td>内3</td><td>内4</td></tr></table>层</td><td>右</td></tr>"
    "<tr><td>下</td><td>值</td></tr></table>",
    "<table><tr><td>一</td><td>二</td></tr></table><p>中间的文字</p><table><tr><td>三</td><td>四</td></tr><tr><td>五</td><td>六</td></tr></table>",
    "<TABLE><TR><TD>大写标签</TD><TD>也认</TD></TR><TR><TD>1</TD><TD>2</TD></TR></TABLE>",
    "<fcel>姓名<fcel>年龄<nl><fcel>张三<fcel>30<nl>",
    "<fcel>项目<fcel>金额<lcel><nl><ucel><fcel>本期<fcel>上期<nl><fcel>收入<fcel>100<fcel>90<nl>",
    "<ched>表头一<ched>表头二<nl><rhed>行头<fcel>值<nl><srow>分节<ecel><nl>",
    "前面的文字<fcel>a<fcel>b<nl><fcel>c<ecel><nl>后面的文字",
    "<fcel>没有换行标记的一行<fcel>也算",
    "| 姓名 | 年龄 |\n| --- | --- |\n| 张三 | 30 |\n| 李四 | 28 |",
    "类别 | 数值\n甲 | 10\n乙 | 20",
    "| 只有 | 一行 |",
    "a | b\n:---: | ---:\nc | d\n\n  e | f  ",
    "| a | b | c |\n| 1 | 2 |",
    "不是表格的普通文字",
    "一行里有 | 竖线\n另一行没有",
    "",
]


def main() -> None:
    for d in (FIXTURES, GOLDEN / "paddle"):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)

    # 读取器：构造的用例
    cases = paddle_cases()
    for name, data in cases.items():
        write(FIXTURES / f"{name}.json", data)
        write(GOLDEN / "paddle" / f"{name}.json", dump_document(document_from_result(data, name=name)))

    # 读取器：仓库自带的合同样例，按目录读（每页一个文件，靠 input_path 归成一份文档）
    sample = ROOT / "examples" / "ocr" / "sample_contract"
    shutil.copytree(sample, FIXTURES / "sample_contract")
    docs = load_documents(sample)
    write(GOLDEN / "paddle" / "sample_contract.json", [dump_document(d) for d in docs])

    # 文本工具
    write(GOLDEN / "textnorm.json", [
        {"text": t, "tokens": estimate_tokens(t), "normalize": normalize_chars(t), "squash": squash(t),
         "one_line": one_line(t), "one_line_10": one_line(t, 10)}
        for t in TEXTS
    ])

    # 表格解析
    table_golden = []
    for content in TABLE_CONTENTS:
        tables = tables_from_content(content)
        table_golden.append({
            "content": content,
            "tables": [{**dump_table(t), "render": t.render(), "n_cols": t.n_cols,
                        "split_40": [list(r) for r in t.split_rows(40)]} for t in tables],
        })
    write(GOLDEN / "tables.json", table_golden)

    print(f"读取器用例 {len(cases) + 1} 个，文本用例 {len(TEXTS)} 个，表格用例 {len(TABLE_CONTENTS)} 个")
    print("写到", RES)


if __name__ == "__main__":
    main()
