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
    golden/textmatch.json              逐字比对：原文、摘抄，以及比对的各项结果
    golden/lexical.json                检索分词的输入和结果
    golden/chunks.json                 每个用例在几种设置下的切片结果和检索排序
    fixtures/schemas/*.yaml            输入：从 examples/schemas 复制来的字段定义
    golden/schemas.json                字段定义读出来的结构、检索词、写进提示词的说明；以及写错时的报错
    golden/prompts.json                系统提示词，以及几种情况下拼出来的抽取和定位提示词
    golden/values.json                 数字、单位、日期的读取，以及值和证据的核对
    golden/pyformat.json               按 Python 规则格式化的结果（repr、str、json.dumps）
    golden/jsonreply.json              从模型的回复里取 JSON
    fixtures/extract/<文档名>.json     输入：抽取流程的用例用到的文档
    golden/extract.json                抽取的整条流程：每个用例发给模型的提示词、模型的回复、最后的结果

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

from extract_agent.chunker import Chunk, chunk_document  # noqa: E402
from extract_agent.document import Block  # noqa: E402
from extract_agent.index import ChunkIndex  # noqa: E402
from extract_agent.llm import parse_json  # noqa: E402
from extract_agent.loader import document_from_result, load_documents  # noqa: E402
from extract_agent.prompts import ROUTE_SYSTEM, SYSTEM, extraction_prompt, routing_prompt  # noqa: E402
from extract_agent.schema import load_schema, schema_from_dict  # noqa: E402
from extract_agent.tables import tables_from_content  # noqa: E402
from extract_agent.textutil import (  # noqa: E402
    SourceText, estimate_tokens, lexical_tokens, match_key, normalize_chars, one_line, quote_parts, squash,
)
from extract_agent.values import (  # noqa: E402
    arabic_numerals, date_in, locate_evidence, parse_cn_number, parse_date, read_number, read_value, unit_scale,
)

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


# 逐字比对的用例：(原文, 摘抄)。覆盖"哪些差别可以忽略、哪些不行"的各种情况
MATCH_CASES = [
    ("含税总价为人民币1,280,000.00元（大写：壹佰贰拾捌万元整）", "含税总价为人民币1,280,000.00元"),
    ("含税总价为人民币1,280,000.00元", "含税总价为人民币 1280000.00 元"),          # 空格和千分位不算差别
    ("含税总价为人民币1,280,000.00元", "含税总价为人民币1,280,000.00元。"),        # 多一个标点不算
    ("含税总价为人民币1,280,000.00元", "含税总价为人民币1,290,000.00元"),          # 改了一个数：不是原文
    ("含税总价为人民币1,280,000.00元", "含税总价为美元1,280,000.00元"),            # 改了字：不是原文
    ("增值税税率为6%，合同期限12个月", "税率为6%"),
    ("单价 1.5 元，数量 15 件", "单价15元"),                                       # 1.5 不能当成 15
    ("| 30 | 384 | 合计 |", "30384"),                                              # 相邻单元格的数不能粘起来
    ("| 30 | 384 | 合计 |", "| 30 | 384 |"),
    ("金额 1234 元", "23"),                                                        # 不能从一个数的中间取
    ("金额 1234 元", "1234"),
    ("金额 1234.56 元", "234.56"),
    ("金额 21.56 元", "1.5"),
    ("| 项目 | 本期 | 上期 |\n| --- | --- | --- |\n| 营业收入 | 12,345.67 | 10,000.00 |",
     "| 项目 | 本期 | 上期 |\n| 营业收入 | 12,345.67 | 10,000.00 |"),              # 表头加一行，中间换行
    ("第一条 甲方应于合同签订后十日内支付首期款。第二条 乙方应按期交付。第三条 违约责任另行约定。",
     "甲方应于合同签订后十日内支付首期款...违约责任另行约定"),                       # 中间用省略号略过
    ("第一条 甲方应于合同签订后十日内支付首期款。第二条 乙方应按期交付。",
     "甲方应于合同签订后十日内支付首期款\u2026\u2026乙方应按期交付"),
    ("第一条 甲方应于合同签订后十日内支付首期款。", "甲方应支付首期款...乙方应按期交付"),   # 有一段不是原文
    ("账号：6222 0212 3456 7890 123", "6222021234567890123"),                     # 分组书写的号码
    ("账号：6222 0212 3456 7890 123", "6222 0212 3456"),
    ("账号：6222 0212 3456 7890 123", "62220212"),
    ("签订日期：2024年3月18日", "2024年3月18日"),
    ("自2024年4月1日起至2025年3月31日止", "2024年4月1日起至2025年3月31日"),
    ("会议时间：上午9:00至11:30", "9:00"),
    ("2011.07-2015.07 昆明学院文学专业学习", "2011.07-2015.07"),
    ("比例为 3/4，另见 2024/03/18", "3/4"),
    ("出生年月（岁）1973.10（51岁）", "1973.10(51岁)"),                            # 全角括号和半角括号不算差别
    ("\u201c甲方\u201d指星河智造科技有限公司", '"甲方"指星河智造科技有限公司'),
    ("ABC Company Ltd. 的 Invoice No. INV-2024-001", "abc company ltd"),          # 不分大小写
    ("ABC Company Ltd. 的 Invoice No. INV-2024-001", "INV-2024-001"),
    ("编号 HT-2024-0318 的合同", "HT-2024-0318"),
    ("编号 HT-2024-0318 的合同", "HT-2024-031"),
    ("总计 100 元", "总计100元整"),
    ("某某公司", ""),                                                               # 空的摘抄
    ("某某公司", "，。、"),                                                         # 只有标点
    ("某某公司", "| --- | --- |"),                                                  # 表格的分隔线
    ("甲", "甲"),
    ("甲乙", "乙"),
    ("12", "12"),
    ("版本 1.2.3 和 1.2", "1.2"),
    ("第\u2460项和第\u2461项", "第\u2460项"),                                       # 带圈数字
    ("不换行空格\u00a0隔开的\u00a0文字", "不换行空格隔开的文字"),
    ("零宽\u200b字符夹在中间", "零宽字符夹在中间"),
    ("ＡＢＣ１２３全角", "abc123全角"),
    ("同一个词出现两次：合同，以及合同。", "合同"),
    ("数字后面跟着点 12. 然后是别的", "12"),
    ("温度 -5 到 10 度", "-5"),
    ("增长 12.5%，下降 3%", "12.5%"),
]

LEXICAL_TEXTS = [
    "",
    "合同总价",
    "甲",
    "合同总金额 价税合计 含税总价",
    "HT-2024-0318 号合同，Invoice No. INV_2024.001",
    "Hello World 你好世界 123",
    "ＡＢＣ全角字母和１２３",
    "日本語のひらがな、한국어",
    "a-b-c x.y.z foo_bar 1.2.3",
    "第一章 总则；第二章 分则。",
]

# 切片的几种设置：(每片上限, 整篇不超过它就不切)
CHUNK_SETTINGS = [(1000, 6000), (300, 600), (200, 0), (60, 0)]
# 检索词。挑的是这些用例文档里有的会命中、有的不会命中的词
QUERIES = ["合同总金额 含税总价 价税合计", "违约金 逾期", "付款计划 分期 支付", "营业收入 本期 上期",
           "姓名 民族 籍贯", "流动资产 货币资金", "完全不会出现的词xyz"]


def dump_chunk(c) -> dict:
    return {"index": c.index, "id": c.id, "text": c.text, "path": list(c.path), "pages": c.pages,
            "section": c.section, "tokens": c.tokens, "has_table": c.has_table, "headings": c.headings,
            "block_ids": [b.id for b in c.blocks], "render": c.render()}


def dump_chunk_runs(doc) -> list:
    """一份文档在各种设置下的切片结果，以及每个检索词的得分和排序。"""
    runs = []
    for max_tokens, whole_under in CHUNK_SETTINGS:
        chunks = chunk_document(doc, max_tokens, whole_under=whole_under)
        index = ChunkIndex(chunks)
        runs.append({
            "max_tokens": max_tokens, "whole_under": whole_under,
            "chunks": [dump_chunk(c) for c in chunks],
            "bm25": [{"query": q, "scores": index.scores(q), "rank": index.rank(q)} for q in QUERIES],
        })
    return runs


# ---------------------------------------------------------------------------
# 字段定义、提示词、值的核对
# ---------------------------------------------------------------------------


def dump_field(f) -> dict:
    return {"name": f.name, "type": f.type, "title": f.title, "description": f.description, "required": f.required,
            "hints": f.hints, "options": f.options, "unit": f.unit, "verbatim": f.verbatim, "top_k": f.top_k,
            "exhaustive": f.exhaustive, "query": f.query, "describe": f.describe(),
            "fields": [dump_field(s) for s in f.fields]}


# 写错的字段定义，每一种都应该报错
BAD_SCHEMAS = [
    {},
    {"fields": []},
    {"fields": "不是列表"},
    {"fields": [{"title": "没有 name"}]},
    {"fields": ["不是对象"]},
    {"fields": [{"name": "a", "typo": 1, "another": 2}]},
    {"fields": [{"name": "a", "type": "text"}]},
    {"fields": [{"name": "a", "type": "enum"}]},
    {"fields": [{"name": "a", "type": "string", "unit": "元"}]},
    {"fields": [{"name": "a", "type": "string", "fields": [{"name": "b"}]}]},
    {"fields": [{"name": "a", "type": "list", "fields": [{"name": "b", "type": "list"}]}]},
    {"fields": [{"name": "a", "type": "list", "fields": [{"name": "b"}, {"name": "b"}]}]},
    {"fields": [{"name": "a"}, {"name": "b"}, {"name": "a"}, {"name": "b"}]},
]


def mk_chunk(text, index=0):
    return Chunk(index=index, text=text, path=(), blocks=[], page_start=None, page_end=None, tokens=0)


def mk_block(text, lead=()):
    return Block(id=0, kind="table", label="table", text=text, lead=tuple(lead))


NUMBER_RAWS = [
    1280000, 1234.56, 0.05, -3, True, False, None, "1,280,000.00元", "1234.56万元", "6%", "百分之六", "千分之三",
    "(1,234.56)", "（1,234.56）", "1,234.56（万元）", "-500", "+7.5", "5亿元", "3.2亿", "5千元", "3千克", "12个月",
    "壹佰贰拾捌万元整", "一百二十", "三千五百万", "二亿三千万", "二〇二四", "十五", "人民币100万元", "RMB 50 million",
    "1.5 billion", "约 300 万", "abc", "", "１２３４．５６", "1e5", "12,34", "1,234,567", "0.5‰", "两千", "一万零五百",
]
UNITS = ["元", "万元", "亿元", "百万元", "千元", "千克", "千米", "%", "‰", "人民币万元", "RMB万元", "美元", "百万美元",
         "万股", "股", "", "（万元）", "million", "Billion USD", "thousand", "个月", "万", "亿", "十万", "千万", "百亿",
         "千亿", "万亿", "十亿", "千港元", "  元 ", "元/股"]
CN_TEXTS = ["二〇二四年三月十五日", "二○二四年", "十五", "三十", "三十五", "十", "二O二四", "一百二十", "壹佰贰拾捌万",
            "三千五百万", "二亿三千万", "两千", "一万零五百", "五", "百", "千万", "三十五十", "一二三", "零", "〇"]
# 带单位的中文数字。parse_cn_number 只接受纯中文数字串（调用它之前总是先用正则截出来）
CN_AMOUNTS = ["一百二十", "壹佰贰拾捌万", "三千五百万", "二亿三千万", "两千", "一万零五百", "五", "百", "千万", "十",
              "三十五", "零", "〇", "二〇二四", "万", "亿", "一亿", "十万", "拾", "壹仟零伍拾", "九千九百九十九万九千九百九十九"]
DATE_RAWS = ["2024-03-18", "2024年3月18日", "2024/3/18", "2024.03.18", "20240318", "2024年3月", "2024-03", "2024",
             "2024年", "二〇二四年三月十八日", "18/03/2024", "03/18/2024", "03/04/2024", "下个月", "", "2024-13-01",
             "2024-02-30", "2024年3月18日至2025年3月17日", 20240318, "签订于 2024年3月18日", "2024 年 3 月 18 日"]
DATE_IN = [
    ("2024-03-18", "签订日期：2024年3月18日"),
    ("2024-03-19", "签订日期：2024年3月18日"),
    ("2024-12-31", "自2024年4月1日起至12月31日止"),
    ("2025-04-01", "自2024年4月1日起至2025年3月31日止"),     # 年份取自后一个日期，月日取自前一个：不行
    ("2024-04-01", "自2024年4月1日起至2025年3月31日止"),
    ("2024-03", "2024年3月18日"),
    ("2024-03", "2024年3月"),
    ("2024-04", "2024年3月18日"),
    ("2024", "2024年度报告"),
    ("2025", "2024年度报告"),
    ("2024-03-18", "March 18, 2024"),
    ("2024-03-18", "24年3月18日"),
    ("2024-03-18", "18/03/2024"),
    ("2024-03-18", "二〇二四年三月十八日"),
    ("2024-03-18", "20240318"),
    ("2024-03-18", "没有日期的一句话"),
    ("不是日期", "2024年3月18日"),
    ("2024-03-18", "2024年，3月18日开会"),
    ("2024-05-20", "时间：2024年5月20日（星期一）上午9:00"),
]

YUAN = {"name": "a", "type": "number", "unit": "元"}
WAN = {"name": "a", "type": "number", "unit": "万元"}
PCT = {"name": "a", "type": "number", "unit": "%"}
NUM = {"name": "a", "type": "number"}
INT = {"name": "a", "type": "integer"}
DATE = {"name": "a", "type": "date"}
BOOL = {"name": "a", "type": "boolean"}
ENUM = {"name": "a", "type": "enum", "options": ["诉讼", "仲裁"]}
STR = {"name": "a"}
FREE = {"name": "a", "verbatim": False}
ROW = "| 营业收入 | 12,345.67 | 10,000.00 |"
TABLE = "| 项目 | 本期 | 上期 |\n| --- | --- | --- |\n" + ROW
TABLE_WAN = "| 项目 | 本期金额(万元) | 上期金额(万元) |\n| --- | --- | --- |\n" + ROW
PARTY = "甲方（委托方）：星河智造科技有限公司\n乙方（受托方）：青岚软件技术有限公司\n争议提交杭州仲裁委员会。甲方应支付违约金。"

# 值和证据的核对：(字段, 模型给的值, 证据, 证据所在的片段, 证据所在的块, 块前面的题注)。后三项可以没有
READ_CASES = [
    (YUAN, "1,280,000.00元", "含税总价为人民币1,280,000.00元", None, None, ()),
    (YUAN, "128万元", "合同总价为128万元", None, None, ()),
    (YUAN, 1280000, "含税总价为人民币1,280,000.00元", None, None, ()),
    (YUAN, "1280000", "合同总价为128万元", None, None, ()),                 # 模型自己换算了，换算得对
    (YUAN, "129万元", "合同总价为128万元", None, None, ()),                 # 证据里没有这个数
    (YUAN, "1290000", "合同总价为128万元", None, None, ()),                 # 自己换算，还算错了
    (WAN, "12,345.67", ROW, TABLE, TABLE, ("单位：万元",)),
    (YUAN, "12,345.67", ROW, TABLE, TABLE, ("单位：万元",)),                # 题注里的单位
    (YUAN, "12,345.67", ROW, TABLE_WAN, TABLE_WAN, ()),                     # 表头里的单位
    (YUAN, "12,345.67万元", ROW, TABLE, TABLE, ("单位：万元",)),
    (YUAN, "12,345.67元", ROW, TABLE, TABLE, ("单位：万元",)),              # 模型写的单位和原文注明的不一样
    (YUAN, "12,345.67", ROW, TABLE, TABLE, ("下表金额均以万元列示",)),      # 只是顺带提到
    (YUAN, "12,345.67", ROW, TABLE, TABLE, ()),                             # 哪里都没有单位
    (NUM, "12,345.67", ROW, TABLE, TABLE, ("单位：万元",)),                 # 字段没设单位：不套用表格上方的说明
    (YUAN, "12,345.67", ROW, TABLE, TABLE, ("单位：万股",)),                # 说的是股，不是元
    (YUAN, "5亿元", "注册资本5亿元", None, None, ()),
    (YUAN, "5亿元", "注册资本5元", None, None, ()),
    (YUAN, "5亿元", "注册资本为 5，另见附注", "注册资本为 5，另见附注", None, ()),   # 原文里找不到"亿"
    (NUM, "6%", "增值税税率为6%", None, None, ()),
    (PCT, "6%", "增值税税率为6%", None, None, ()),
    (PCT, "百分之六", "税率为百分之六", None, None, ()),
    (PCT, "0.05%", "每逾期一日按合同总价的0.05%支付", None, None, ()),
    (INT, "12个月", "合同期限12个月", None, None, ()),
    (INT, "12.5", "数量12.5个", None, None, ()),
    (NUM, "abc", "没有数字", None, None, ()),
    (NUM, True, "数量为 1", None, None, ()),
    (YUAN, "1,234.56", "| 净利润 | (1,234.56) |", None, None, ()),          # 括号表示负数，值却写成了正的
    (YUAN, "(1,234.56)", "| 净利润 | (1,234.56) |", None, None, ()),
    (YUAN, "-500", "亏损 -500 元", None, None, ()),
    (YUAN, "500", "亏损 -500 元", None, None, ()),
    (YUAN, "壹佰贰拾捌万元整", "（大写：壹佰贰拾捌万元整）", None, None, ()),
    (YUAN, "1280000", "（大写：壹佰贰拾捌万元整）", None, None, ()),
    (YUAN, "1,000元", "", None, None, ()),                                   # 没有证据：只整理类型
    (YUAN, 3.5, "单价3.5元", None, None, ()),
    (WAN, "1280000元", "总价1,280,000元", None, None, ()),
    (YUAN, "100", "收入100万元，成本100元", None, None, ()),                 # 注明的单位不止一种，却没带单位
    (YUAN, "5千元", "奖金5千元", None, None, ()),
    (NUM, "3千克", "重量3千克", None, None, ()),
    (YUAN, "2024", "二〇二四年度拨款", None, None, ()),
    (DATE, "2024-03-18", "签订日期：2024年3月18日", None, None, ()),
    (DATE, "2024年3月18日", "签订日期：2024年3月18日", None, None, ()),
    (DATE, "2024-03-19", "签订日期：2024年3月18日", None, None, ()),
    (DATE, "2025-04-01", "自2024年4月1日起至2025年3月31日止", None, None, ()),
    (DATE, "下个月", "下个月交付", None, None, ()),
    (DATE, "2024-03-18", "", None, None, ()),
    (BOOL, "是", "是否含税：是", None, None, ()),
    (BOOL, True, "是否含税：是", None, None, ()),
    (BOOL, "maybe", "是否含税：不确定", None, None, ()),
    (BOOL, 0, "0", None, None, ()),
    (ENUM, "仲裁", "提交杭州仲裁委员会", None, None, ()),
    (ENUM, " 仲 裁 ", "提交杭州仲裁委员会", None, None, ()),
    (ENUM, "调解", "协商调解", None, None, ()),
    (STR, "星河智造科技有限公司", "甲方（委托方）：星河智造科技有限公司", PARTY, None, ()),
    (STR, "星河智造有限公司", "甲方（委托方）：星河智造科技有限公司", PARTY, None, ()),     # 值不是原文
    (STR, "杭州仲裁委员会", "甲方应支付违约金", PARTY, None, ()),                           # 值是原文，但证据引错了地方
    (STR, "星河智造科技有限公司", "甲方（委托方）：星河智造科技有限公司", None, None, ()),   # 没定位到片段
    (FREE, "逾期按日支付违约金", "甲方应支付违约金", PARTY, None, ()),                     # 允许概括：不核对值
    (STR, {"x": 1}, "随便", None, None, ()),
    (STR, ["a"], "随便", None, None, ()),
    (STR, 47, "年龄 47", None, None, ()),
    (STR, "  前后有空白  ", "前后有空白", None, None, ()),
    (STR, "6222021234567890123", "账号：6222 0212 3456 7890 123", None, None, ()),
    (STR, "第一句。第二句。第三句。", "第一句。...第三句。", "第一句。第二句。第三句。", None, ()),  # 证据中间略过了
    (STR, "无", "奖惩情况 | 无", "| 奖惩情况 | 无 |", None, ()),
]

LOCATE_CHUNKS = ["第一条 甲方应于合同签订后十日内支付首期款。", "第二条 乙方应按期交付。违约金为合同总价的5%。",
                 "| 项目 | 金额 |\n| --- | --- |\n| 首期款 | 384,000.00 |"]
# 证据的定位：(证据, 模型声称的片段序号)
LOCATE_CASES = [
    ("甲方应于合同签订后十日内支付首期款", 0),
    ("甲方应于合同签订后十日内支付首期款", 1),            # 片段编号写错了，到别的片段里找
    ("甲方应于合同签订后十日内支付首期款", None),
    ("违约金为合同总价的5%", None),
    ("违约金为合同总价的6%", 1),                          # 改了一个数
    ("| 项目 | 金额 |\n| 首期款 | 384,000.00 |", 2),
    ("乙方应按期交付...| 首期款 | 384,000.00 |", 1),      # 两段在不同的片段里，以长的那段为准
    ("完全无关的一句话，哪个片段里都没有", 0),
    ("", 0),
    ("，。", None),
    ("甲方应于合同签订后十五日内支付首期款", None),       # 很像但不是原文
]

PYFORMAT_VALUES = [
    None, True, False, 0, 1280000, -3, 0.05, 1234.56, 12800000.0, 100.0, 1e16, 1.5e-05, 0.0001, 123456789012345.6,
    "", "张三", "带'单引号", '带"双引号', "两种'都\"有", "换行\n和制表\t符", "反斜杠\\", "不换行空格\u00a0零宽\u200b",
    [], {}, [1, "a", None, True], {"value": 1280000, "evidence": "含税总价为人民币1,280,000.00元"},
    {"b": 1, "a": {"d": [1.5, {"z": None}], "c": "文"}}, [[], {}], ["诉讼", "仲裁"],
    {"称谓": "妻子", "姓名": "苏婉清", "年龄": "47"},
]

JSON_REPLIES = [
    '{"a": 1}',
    '  {"a": 1}  ',
    '```json\n{"a": 1}\n```',
    '```\n{"a": 1}\n```',
    '好的，结果如下：\n{"a": {"value": "x", "evidence": "y"}}\n以上。',
    '<think>先想一想，{不是 JSON}</think>{"a": 1}',
    '<think>没有闭合 {"a": 1}',
    '[1, 2, 3]',
    '"只是一个字符串"',
    '不是 JSON',
    '',
    '{"a": 1} {"b": 2}',
    '{"a": [1, 2, {"b": null}], "c": 1.5, "d": true, "e": "中文"}',
    '{"a": 1,}',
    '第一个 {"a": 1} 第二个 {"b": 2}',
    '{"a": "值里有 } 括号"}',
]


# ---------------------------------------------------------------------------
# 抽取引擎：整条流程的录播
#
# 每个用例是"一份文档 + 一份字段定义 + 一个假模型 + 一组引擎参数"。用 Python 版跑一遍，
# 把它发给模型的每一条提示词、模型的每一条回复、以及最后的结果都记下来。
# Java 版用同样的输入重跑：它发出的提示词必须和这里记下的逐字相同（回复按提示词取），最后的结果也必须相同。
# 用例和 tests/test_agent.py 里的一一对应，假模型直接用 tests/support.py 里的。
# ---------------------------------------------------------------------------

sys.path.insert(0, str(ROOT / "tests"))

from support import CONTRACT_RULES, RuleLLM, ScriptedLLM, asked_fields, shown_chunks  # noqa: E402

from extract_agent.agent import ExtractionAgent  # noqa: E402
from extract_agent.llm import OutputTruncated, Usage  # noqa: E402

ENGINE_DEFAULTS = dict(chunk_tokens=1000, context_tokens=6000, top_k=5, max_rounds=4, retries=1, route=True,
                       route_tokens=16000, max_fields_per_call=8)

FILLER = "本节内容为一般性约定，双方应当遵守诚实信用原则，按照约定全面履行各自的义务。" * 3
EVIDENCE = "3.1 本合同含税总价为人民币1,280,000.00元"
ROW_1 = "| 第一期 | 合同签订后10个工作日内 | 30% | 384,000.00 |"
ROW_2 = "| 第二期 | 《需求规格说明书》经甲方确认后10个工作日内 | 20% | 256,000.00 |"
ROW_3 = "| 第三期 | 系统上线并经甲方验收合格后10个工作日内 | 40% | 512,000.00 |"


class Recorder:
    """包在假模型外面，记下每一次调用。每次调用记 11 个输入 token、5 个输出 token，和假模型服务相同。"""

    def __init__(self, inner) -> None:
        self.inner = inner
        self.usage = Usage()
        self.calls: list[dict] = []

    def complete(self, system: str, user: str) -> str:
        kind = "route" if system == ROUTE_SYSTEM else "extract"
        assert system in (ROUTE_SYSTEM, SYSTEM)
        self.usage.add(11, 5)
        try:
            reply = self.inner.complete(system, user)
        except OutputTruncated:
            self.calls.append({"kind": kind, "user": user, "reply": None, "truncated": True})
            raise
        self.calls.append({"kind": kind, "user": user, "reply": reply, "truncated": False})
        return reply


class TruncatingLLM(RuleLLM):
    """输出装不下太多内容的模型：一次超过 max_fields 个字段或 max_chunks 个片段就被截断。"""

    def __init__(self, rules, max_fields=1, max_chunks=1):
        super().__init__(rules)
        self.max_fields, self.max_chunks = max_fields, max_chunks

    def complete(self, system, user):
        if len(asked_fields(user)) > self.max_fields or len(shown_chunks(user)) > self.max_chunks:
            raise OutputTruncated("截断")
        return super().complete(system, user)


class FlakyLLM(RuleLLM):
    """提示词里出现某段文字时输出总是被截断。"""

    def __init__(self, rules, poison):
        super().__init__(rules)
        self.poison = poison

    def complete(self, system, user):
        if self.poison in user:
            raise OutputTruncated("截断")
        return super().complete(system, user)


class BadRouteLLM(RuleLLM):
    """定位请求的回复不成形，抽取照常。"""

    def complete(self, system, user):
        if system == ROUTE_SYSTEM:
            return "这些片段我都不太确定。"
        return super().complete(system, user)


def extract_docs() -> dict:
    """抽取用例里用到的文档（合同样例之外的），PaddleOCR-VL 格式。"""
    sections = [
        ("第一条 定义", "合同总价的定义见附件一。"),
        ("第二条 服务范围", ""),
        ("第三条 结算", "合同总价的结算方式由双方另行约定。"),
        ("第四条 费用", "本项目费用共计人民币500,000元。"),
        ("第五条 发票", "合同总价对应的发票由乙方开具。"),
        ("第六条 其他", ""),
    ]
    long_blocks = []
    for title, lead in sections:
        long_blocks += [block("paragraph_title", title), block("text", lead + FILLER)]

    rows = [["股东名称", "期末持股数量", "持股比例(%)"]]
    rows += [[f"股东{i:02d}有限公司", f"{(60 - i) * 1_000_000:,}", f"{(60 - i) / 10:.2f}"] for i in range(1, 31)]
    shareholders = [
        page(0, [block("doc_title", "2024年年度报告"), block("paragraph_title", "第一节 公司简介"),
                 block("text", FILLER * 2)]),
        page(1, [block("paragraph_title", "第二节 股东情况"), block("table_title", "表1 前三十名股东持股情况"),
                 block("text", "单位：股"), block("table", html_table(rows))]),
        page(2, [block("paragraph_title", "第三节 其他事项"), block("text", FILLER * 2)]),
    ]

    stages = [("第一期", "100,000"), ("第二期", "200,000"), ("第三期", "300,000")]
    stage_blocks = [block("paragraph_title", "第一条 定义"), block("text", FILLER)]
    for n, (stage, amount) in enumerate(stages, 2):
        stage_blocks += [block("paragraph_title", f"第{n}条 {stage}付款"),
                         block("text", f"{stage}款项为人民币{amount}元。" + FILLER)]
    stage_blocks += [block("paragraph_title", "第五条 其他"), block("text", FILLER)]

    date_blocks = []
    for n, day in enumerate(("2024年5月1日", "2024年6月15日", "2024年9月30日", "2024年12月31日"), 1):
        date_blocks += [block("paragraph_title", f"第{n}条 事项{n}"),
                        block("text", f"乙方应于{day}前完成本条约定的工作。" + FILLER)]

    return {
        "long_contract": page(None, long_blocks),
        "shareholders_report": shareholders,
        "staged_payments": page(None, stage_blocks),
        "scattered_dates": page(None, date_blocks),
        "empty": page(None, []),
    }


def extraction_cases() -> list[dict]:
    def one_field(**attrs):
        return {"fields": [{"name": "total_amount", "type": "number", **attrs}]}

    amount_schema = one_field(title="合同总金额", hints=["合同总价"])
    amount_rule = {"total_amount": r"费用共计人民币([\d,]+)元"}
    small = dict(chunk_tokens=150, context_tokens=320, top_k=2, max_rounds=4)
    shareholders = {"fields": [{
        "name": "shareholders", "title": "股东", "type": "list", "hints": ["股东名称", "持股比例"],
        "fields": [{"name": "name", "required": True}, {"name": "shares", "type": "number"},
                   {"name": "ratio", "type": "number"}],
    }]}
    shareholder_rule = {"shareholders": (r"\| (股东\d+有限公司) \| ([\d,]+) \| ([\d.]+) \|", ["name", "shares", "ratio"])}
    split_table = dict(route=False, chunk_tokens=200, context_tokens=450, top_k=1)
    payments = {"fields": [{"name": "payments", "type": "list",
                            "fields": [{"name": "stage"}, {"name": "amount", "type": "number"}]}]}
    good = {"total_amount": {"value": 1280000, "evidence": EVIDENCE, "chunk": "c1"}}
    answer = json.dumps(good, ensure_ascii=False)

    def deadlines(exhaustive):
        return {"fields": [{"name": "deadlines", "type": "list", "exhaustive": exhaustive, "hints": ["事项1"],
                            "fields": [{"name": "date", "type": "date"}]}]}

    deadline_rule = {"deadlines": (r"应于(\d+年\d+月\d+日)前", ["date"])}
    read_all = dict(route=False, chunk_tokens=150, context_tokens=320, top_k=1, max_rounds=2)

    cases = [
        # 小文档：整份合同是一片，13 个字段两次调用
        dict(name="contract_full", doc="sample_contract", schema="contract.yaml",
             llm=lambda: RuleLLM(CONTRACT_RULES)),
        # 编出来的证据：标记为没通过核对，值保留
        dict(name="fabricated_evidence", doc="sample_contract", schema=one_field(required=True),
             llm=lambda: ScriptedLLM([{"total_amount": {"value": 2000000, "evidence": "合同总价为人民币贰佰万元整，分三期支付",
                                                        "chunk": "c1"}}])),
        # 抄错了数字：重问之后改对
        dict(name="wrong_value_fixed_on_retry", doc="sample_contract", schema=one_field(),
             llm=lambda: ScriptedLLM([{"total_amount": {"value": 1820000, "evidence": EVIDENCE, "chunk": "c1"}}, good])),
        # 片段编号写错了：自己找到证据所在的片段
        dict(name="wrong_chunk_id", doc="sample_contract", schema=one_field(),
             llm=lambda: ScriptedLLM([{"total_amount": {"value": "1,280,000.00", "evidence": EVIDENCE, "chunk": "c7"}}])),
        # 模型实际会给出的几种回复形式
        dict(name="reply_in_code_fence", doc="sample_contract", schema=one_field(), options=dict(retries=0),
             llm=lambda: ScriptedLLM([f"```json\n{answer}\n```"])),
        dict(name="reply_with_thinking", doc="sample_contract", schema=one_field(), options=dict(retries=0),
             llm=lambda: ScriptedLLM([f"<think>先找合同价款条款……</think>\n{answer}"])),
        dict(name="reply_with_prose", doc="sample_contract", schema=one_field(), options=dict(retries=0),
             llm=lambda: ScriptedLLM([f"抽取结果如下：\n{answer}\n以上。"])),
        dict(name="reply_wrapped", doc="sample_contract", schema=one_field(), options=dict(retries=0),
             llm=lambda: ScriptedLLM(['{"result": %s}' % answer])),
        dict(name="reply_without_evidence", doc="sample_contract", schema=one_field(), options=dict(retries=0),
             llm=lambda: ScriptedLLM(['{"total_amount": 1280000}'])),
        # 回复不是 JSON：重问；一直不是就记下来，不当成"文档里没有"
        dict(name="invalid_json_then_good", doc="sample_contract", schema=one_field(),
             llm=lambda: ScriptedLLM(["抱歉，我没有找到。", good])),
        dict(name="invalid_json_always", doc="sample_contract", schema=one_field(),
             llm=lambda: ScriptedLLM(["抱歉，我没有找到。"])),
        # 没找到的字段
        dict(name="missing_fields", doc="sample_contract",
             schema={"fields": [{"name": "guarantor", "title": "担保人", "required": True},
                                {"name": "fax", "title": "传真"}]},
             llm=lambda: RuleLLM({})),
        # 最先看的片段里没有：往后找
        dict(name="search_widens", doc="long_contract", schema=amount_schema, options=dict(route=False, **small),
             llm=lambda: RuleLLM(amount_rule)),
        # 模型按目录定位：第一轮就找到
        dict(name="routing_by_outline", doc="long_contract", schema=amount_schema, options=small,
             llm=lambda: RuleLLM(amount_rule, route={"total_amount": ["c4"]})),
        # 定位的回复里有不认识的编号、不是字符串的编号、不认识的字段
        dict(name="routing_reply_partly_unusable", doc="long_contract", schema=amount_schema, options=small,
             llm=lambda: RuleLLM(amount_rule, route={"total_amount": ["c99", 4], "unknown": ["c1"]})),
        # 定位的回复不是 JSON：只靠关键词检索
        dict(name="routing_reply_not_json", doc="long_contract", schema=amount_schema, options=small,
             llm=lambda: BadRouteLLM(amount_rule)),
        # 找到最大轮数为止
        dict(name="stops_after_max_rounds", doc="long_contract", schema=amount_schema,
             options=dict(route=False, **{**small, "max_rounds": 2}), llm=lambda: RuleLLM(amount_rule)),
        # 候选片段相同的字段放进同一次调用
        dict(name="fields_share_one_call", doc="long_contract",
             schema={"fields": [{"name": "total_amount", "type": "number", "hints": ["费用共计"]},
                                {"name": "fee_clause", "hints": ["费用共计"]},
                                {"name": "invoice_by", "hints": ["发票", "开具"]}]},
             options=dict(route=False, **{**small, "top_k": 1}),
             llm=lambda: RuleLLM({**amount_rule, "fee_clause": r"(本项目费用共计人民币[\d,]+元)",
                                  "invoice_by": r"发票由(乙方)开具"})),
        # 大表被拆成几片：各片的行都要收齐
        dict(name="list_from_split_table", doc="shareholders_report", schema=shareholders, options=split_table,
             llm=lambda: RuleLLM(shareholder_rule)),
        # 列表顺着找到的位置向相邻片段延伸
        dict(name="list_follows_neighbours", doc="staged_payments",
             schema={"fields": [{"name": "payments", "type": "list", "hints": ["第二期"],
                                 "fields": [{"name": "stage"}, {"name": "amount", "type": "number"}]}]},
             options=dict(route=False, chunk_tokens=150, context_tokens=320, top_k=1),
             llm=lambda: RuleLLM({"payments": (r"(第.期)款项为人民币([\d,]+)元", ["stage", "amount"])})),
        # 列表里没通过核对的项单独标出来；空项丢掉
        dict(name="list_items_marked_individually", doc="sample_contract", schema=payments,
             llm=lambda: ScriptedLLM([{"payments": [
                 {"value": {"stage": "第一期", "amount": 384000}, "evidence": ROW_1, "chunk": "c1"},
                 {"value": {"stage": "第五期", "amount": 99999}, "evidence": "| 第五期 | 质保期满 | 5% | 99,999.00 |",
                  "chunk": "c1"},
                 {"value": {"stage": None, "amount": None}, "evidence": "", "chunk": "c1"},
             ]}])),
        # 重问时只给了改正的那一项：通过核对的几项要留着
        dict(name="retry_returns_only_fixed_item", doc="sample_contract", schema=payments,
             llm=lambda: ScriptedLLM([
                 {"payments": [
                     {"value": {"stage": "第三期", "amount": 512000}, "evidence": ROW_3, "chunk": "c1"},
                     {"value": {"stage": "第二期", "amount": 265000}, "evidence": ROW_2, "chunk": "c1"},
                     {"value": {"stage": "第一期", "amount": 384000}, "evidence": ROW_1, "chunk": "c1"},
                 ]},
                 {"payments": [{"value": {"stage": "第二期", "amount": 256000}, "evidence": ROW_2, "chunk": "c1"}]},
             ])),
        # 回复的形状不太规矩
        dict(name="loose_flat_item", doc="sample_contract", schema=payments,
             llm=lambda: ScriptedLLM([{"payments": [{"stage": "第一期", "amount": "384,000.00", "evidence": ROW_1,
                                                     "chunk": "c1"}]}])),
        dict(name="loose_single_object_for_list", doc="sample_contract", schema=payments,
             llm=lambda: ScriptedLLM([{"payments": {"value": {"stage": "第一期", "amount": 384000}, "evidence": ROW_1,
                                                    "chunk": "c1"}}])),
        dict(name="loose_list_for_scalar", doc="sample_contract", schema=one_field(),
             llm=lambda: ScriptedLLM([{"total_amount": [good["total_amount"]]}])),
        # 空文档
        dict(name="empty_document", doc="empty", schema=one_field(required=True), llm=lambda: RuleLLM({})),
        # 散落在各处的列表：只看最相关的几片找不全，设成通读全文才找得全
        dict(name="scattered_list_partial", doc="scattered_dates", schema=deadlines(False), options=read_all,
             llm=lambda: RuleLLM(deadline_rule)),
        dict(name="scattered_list_exhaustive", doc="scattered_dates", schema=deadlines(True), options=read_all,
             llm=lambda: RuleLLM(deadline_rule)),
        # 输出被截断：把任务拆开重做
        dict(name="truncation_splits_the_job", doc="long_contract",
             schema={"fields": [{"name": "total_amount", "type": "number", "hints": ["费用共计"]},
                                {"name": "invoice_by", "hints": ["费用共计"]}]},
             options=dict(route=False, chunk_tokens=150, context_tokens=2000, top_k=6),
             llm=lambda: TruncatingLLM({**amount_rule, "invoice_by": r"发票由(乙方)开具"})),
        # 拆到最小还是被截断：记下来
        dict(name="truncation_cannot_split", doc="shareholders_report", schema=shareholders, options=split_table,
             llm=lambda: TruncatingLLM(shareholder_rule, max_chunks=0)),
        # 一个分段总是被截断：拿到的条目都对，但列表不能算完整
        dict(name="one_bad_segment", doc="shareholders_report", schema=shareholders,
             options=dict(route=False, chunk_tokens=200, context_tokens=200, top_k=1),
             llm=lambda: FlakyLLM(shareholder_rule, "股东30有限公司")),
    ]
    return cases


def record_extraction(case: dict, docs: dict, schemas: dict) -> dict:
    spec = case["schema"]
    schema = schemas[spec] if isinstance(spec, str) else schema_from_dict(spec)
    options = {**ENGINE_DEFAULTS, **case.get("options", {})}
    recorder = Recorder(case["llm"]())
    # 录的时候一次只跑一个调用，这样记下来的顺序是确定的，重新生成时文件不会无故变化
    result = ExtractionAgent(recorder, workers=1, **options).run(docs[case["doc"]], schema)
    return {"name": case["name"], "doc": case["doc"], "schema": spec, "options": options,
            "calls": recorder.calls, "result": result.to_dict()}


def main() -> None:
    for d in (FIXTURES, GOLDEN / "paddle"):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)

    # 读取器：构造的用例
    cases = paddle_cases()
    chunk_golden = {}
    fixture_docs = {}
    for name, data in cases.items():
        write(FIXTURES / f"{name}.json", data)
        doc = document_from_result(data, name=name)
        fixture_docs[name] = doc
        write(GOLDEN / "paddle" / f"{name}.json", dump_document(doc))
        chunk_golden[name] = dump_chunk_runs(doc)

    # 读取器：仓库自带的合同样例，按目录读（每页一个文件，靠 input_path 归成一份文档）
    sample = ROOT / "examples" / "ocr" / "sample_contract"
    shutil.copytree(sample, FIXTURES / "sample_contract")
    docs = load_documents(sample)
    write(GOLDEN / "paddle" / "sample_contract.json", [dump_document(d) for d in docs])
    chunk_golden["sample_contract"] = dump_chunk_runs(docs[0])

    # 切片和检索
    write(GOLDEN / "chunks.json", chunk_golden)

    # 逐字比对
    match_golden = []
    for source, quote in MATCH_CASES:
        src = SourceText(source)
        match_golden.append({
            "source": source, "quote": quote,
            "key": match_key(source), "key_glued": match_key(source, glue_digit_groups=True),
            "quote_key": match_key(quote), "parts": quote_parts(quote),
            "supports": src.supports(quote), "contains_value": src.contains_value(quote),
            "position": src.position(quote), "similarity": src.similarity(quote),
        })
    write(GOLDEN / "textmatch.json", match_golden)
    write(GOLDEN / "lexical.json", [{"text": t, "tokens": lexical_tokens(t)} for t in LEXICAL_TEXTS])

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

    # 字段定义：仓库自带的几份，加上各种写错的情况
    schemas_src = ROOT / "examples" / "schemas"
    schemas_dst = RES / "fixtures" / "schemas"
    if schemas_dst.exists():
        shutil.rmtree(schemas_dst)
    shutil.copytree(schemas_src, schemas_dst)
    schemas = {f.name: load_schema(f) for f in sorted(schemas_src.glob("*.yaml"))}
    schema_golden = {"schemas": [], "errors": []}
    for file, s in schemas.items():
        schema_golden["schemas"].append({"file": file, "name": s.name, "description": s.description,
                                         "fields": [dump_field(x) for x in s.fields]})
    for raw in BAD_SCHEMAS:
        try:
            schema_from_dict(raw)
            message = None
        except ValueError as e:
            message = str(e)
        schema_golden["errors"].append({"raw": raw, "error": message})
    write(GOLDEN / "schemas.json", schema_golden)

    # 提示词：系统提示词，以及几种情况下拼出来的抽取提示词和定位提示词
    fixture_docs["sample_contract"] = docs[0]

    def prompt_case(doc_name, schema_file, max_tokens, whole_under, field_names, chunk_idx, feedback, top_k, budget):
        doc, schema = fixture_docs[doc_name], schemas[schema_file]
        chunks = chunk_document(doc, max_tokens, whole_under=whole_under)
        fields = [schema[n] for n in field_names] if field_names else list(schema.fields)
        shown = [chunks[i] for i in chunk_idx] if chunk_idx is not None else chunks
        return {"doc": doc_name, "schema": schema_file, "max_tokens": max_tokens, "whole_under": whole_under,
                "fields": [f.name for f in fields], "chunks": [c.index for c in shown], "feedback": feedback,
                "extraction": extraction_prompt(doc, fields, shown, feedback, about=schema.description),
                "top_k": top_k, "budget": budget,
                "routing": routing_prompt(doc, fields, chunks, top_k, budget, about=schema.description)}

    feedback = '- total_amount：你的回答是 {"value": 1290000, "evidence": "含税总价"}；问题：evidence 里没有 1290000 这个数。'
    write(GOLDEN / "prompts.json", {"system": SYSTEM, "route_system": ROUTE_SYSTEM, "cases": [
        prompt_case("sample_contract", "contract.yaml", 1000, 6000, None, None, "", 5, 16000),
        prompt_case("sample_contract", "contract.yaml", 200, 0,
                    ["total_amount", "payment_schedule", "breach_penalty"], [0, 1, 2], feedback, 3, 16000),
        prompt_case("sample_contract", "contract.yaml", 200, 0, ["party_a"], [0], "", 5, 60),   # 目录放不下预览
        prompt_case("sample_contract", "contract.yaml", 200, 0, ["party_a"], [0], "", 5, 5),    # 目录怎么都放不下
        prompt_case("concatenated", "generic.yaml", 1000, 6000, None, None, "", 5, 16000),      # 没有页码的文档
        prompt_case("headings", "annual_report.yaml", 120, 0, None, [1, 2], "", 2, 16000),
        prompt_case("response_list", "personnel_appointment.yaml", 1000, 6000, None, None, "", 5, 16000),
        prompt_case("cross_page_table", "vat_invoice.yaml", 60, 0, None, [0, 1], "", 5, 16000),
    ]})

    # 数字、单位、日期的读取，以及值和证据的核对
    numbers = []
    for raw in NUMBER_RAWS:
        n = read_number(raw)
        numbers.append({"raw": raw, "value": None if n is None else str(n[0]), "scale": None if n is None else n[1],
                        "readable": n is not None})
    read_golden = []
    for spec, raw, evidence, chunk_text, block_text, lead in READ_CASES:
        f = schema_from_dict({"fields": [spec]}).fields[0]
        value, issues = read_value(f, raw, evidence,
                                   mk_chunk(chunk_text) if chunk_text is not None else None,
                                   mk_block(block_text, lead) if block_text is not None else None)
        read_golden.append({"field": spec, "raw": raw, "evidence": evidence, "chunk": chunk_text, "block": block_text,
                            "lead": list(lead), "value": value, "issues": issues})
    locate_chunks = [mk_chunk(t, i) for i, t in enumerate(LOCATE_CHUNKS)]
    locate_golden = []
    for evidence, claimed in LOCATE_CASES:
        home, issues = locate_evidence(evidence, locate_chunks[claimed] if claimed is not None else None, locate_chunks)
        locate_golden.append({"evidence": evidence, "claimed": claimed,
                              "home": None if home is None else home.index, "issues": issues})
    write(GOLDEN / "values.json", {
        "numbers": numbers,
        "units": [{"unit": u, "scale": unit_scale(u)} for u in UNITS],
        "cn": [{"text": t, "arabic": arabic_numerals(t)} for t in CN_TEXTS],
        "cn_amounts": [{"text": t, "parsed": parse_cn_number(t)} for t in CN_AMOUNTS],
        "dates": [{"raw": r, "parsed": parse_date(r)} for r in DATE_RAWS],
        "date_in": [{"value": v, "evidence": e, "result": date_in(v, e)} for v, e in DATE_IN],
        "read": read_golden,
        "locate_chunks": LOCATE_CHUNKS,
        "locate": locate_golden,
    })

    # 按 Python 规则格式化
    write(GOLDEN / "pyformat.json", [
        {"value": v, "repr": repr(v), "str": str(v), "json": json.dumps(v, ensure_ascii=False),
         "json_sorted": json.dumps(v, ensure_ascii=False, sort_keys=True),
         "json_indent2": json.dumps(v, ensure_ascii=False, indent=2)}
        for v in PYFORMAT_VALUES
    ])

    # 从模型的回复里取 JSON
    replies = []
    for text in JSON_REPLIES:
        try:
            result = parse_json(text)
        except ValueError:
            result = None
        replies.append({"text": text, "ok": result is not None, "result": result})
    write(GOLDEN / "jsonreply.json", replies)

    # 抽取引擎：整条流程的录播
    extract_fixtures = RES / "fixtures" / "extract"
    if extract_fixtures.exists():
        shutil.rmtree(extract_fixtures)
    engine_docs = {"sample_contract": docs[0]}
    for name, data in extract_docs().items():
        write(extract_fixtures / f"{name}.json", data)
        engine_docs[name] = document_from_result(data, name=name)
    recorded = [record_extraction(case, engine_docs, schemas) for case in extraction_cases()]
    write(GOLDEN / "extract.json", recorded)

    print(f"读取器用例 {len(cases) + 1} 个，文本用例 {len(TEXTS)} 个，表格用例 {len(TABLE_CONTENTS)} 个，"
          f"比对用例 {len(MATCH_CASES)} 个，切片 {len(chunk_golden)} 份文档 x {len(CHUNK_SETTINGS)} 种设置，"
          f"字段定义 {len(schemas)} 份，值的核对 {len(READ_CASES)} 个，"
          f"抽取流程 {len(recorded)} 个（模型调用 {sum(len(r['calls']) for r in recorded)} 次）")
    print("写到", RES)


if __name__ == "__main__":
    main()
