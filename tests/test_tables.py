from extract_agent.tables import Table, parse_html_tables, tables_from_content

from support import html_table


def test_plain_table_first_row_is_header():
    (t,) = parse_html_tables(html_table([["项目", "金额"], ["营业收入", "1,234.56"], ["净利润", "78.90"]]))
    assert t.header_rows == 1
    assert t.rows == [["项目", "金额"], ["营业收入", "1,234.56"], ["净利润", "78.90"]]
    assert t.render().splitlines() == [
        "| 项目 | 金额 |",
        "| --- | --- |",
        "| 营业收入 | 1,234.56 |",
        "| 净利润 | 78.90 |",
    ]


def test_rowspan_and_colspan_are_expanded_into_a_grid():
    html = (
        "<table>"
        '<tr><td rowspan="2">项目</td><td colspan="2">2024年</td></tr>'
        "<tr><td>本期</td><td>上期</td></tr>"
        "<tr><td>营业收入</td><td>100</td><td>90</td></tr>"
        '<tr><td rowspan="2">费用</td><td>10</td><td>9</td></tr>'
        "<tr><td>20</td><td>18</td></tr>"
        "</table>"
    )
    (t,) = parse_html_tables(html)
    assert t.header_rows == 2  # 分组表头占两行
    assert t.rows == [
        ["项目", "2024年", "2024年"],
        ["项目", "本期", "上期"],
        ["营业收入", "100", "90"],
        ["费用", "10", "9"],
        ["费用", "20", "18"],  # 被合并的行补上了行名，按行拆分后每行仍然完整
    ]


def test_th_cells_mark_the_header_explicitly():
    html = "<table><tr><th>名称</th><th>数量</th></tr><tr><td>螺栓</td><td>10</td></tr></table>"
    (t,) = parse_html_tables(html)
    assert t.header_rows == 1 and t.explicit_header


def test_tolerates_styled_tags_images_and_unclosed_markup():
    html = (
        "<table border=1 style='margin: auto;'><tr><td style='text-align: center;'>名称</td><td>图</td></tr>"
        '<tr><td>阀门<br>DN50</td><td><img src="imgs/a.jpg" alt="Image" /></td>'
    )
    (t,) = parse_html_tables(html)
    assert t.rows == [["名称", "图"], ["阀门 DN50", ""]]


def test_pipe_characters_in_cells_do_not_break_rendering():
    (t,) = parse_html_tables(html_table([["a", "b"], ["x|y", "1"]]))
    assert t.render().splitlines()[-1] == "| x｜y | 1 |"


def test_split_rows_keeps_every_row_and_repeats_header():
    rows = [["股东名称", "持股数量"]] + [[f"股东{i:02d}有限公司", f"{i * 1000:,}"] for i in range(1, 41)]
    (t,) = parse_html_tables(html_table(rows))
    ranges = t.split_rows(120)
    assert len(ranges) > 2
    assert ranges[0][0] == 0 and ranges[-1][1] == 40
    assert all(a[1] == b[0] for a, b in zip(ranges, ranges[1:]))  # 首尾相接，不丢行不重复
    for start, end in ranges:
        assert t.render(start, end).startswith("| 股东名称 | 持股数量 |")


def test_absorb_appends_continuation_and_drops_repeated_header():
    (first,) = parse_html_tables(html_table([["期次", "金额"], ["第一期", "100"]]))
    (rest,) = parse_html_tables(html_table([["期次", "金额"], ["第二期", "200"], ["第三期", "300"]]))
    assert first.absorb(rest)
    assert first.rows == [["期次", "金额"], ["第一期", "100"], ["第二期", "200"], ["第三期", "300"]]


def test_absorb_refuses_a_different_table():
    (first,) = parse_html_tables(html_table([["期次", "金额"], ["第一期", "100"]]))
    (wider,) = parse_html_tables(html_table([["a", "b", "c"], ["1", "2", "3"]]))
    (other_header,) = parse_html_tables(
        "<table><tr><th>姓名</th><th>职务</th></tr><tr><td>张三</td><td>经理</td></tr></table>"
    )
    assert not first.absorb(wider)
    assert not first.absorb(other_header)
    assert len(first.rows) == 2


def test_otsl_and_pipe_text_fallbacks():
    (otsl,) = tables_from_content("<fcel>项目<fcel>金额<nl><fcel>收入<fcel>100<nl><fcel>合计<ecel><nl>")
    assert otsl.rows == [["项目", "金额"], ["收入", "100"], ["合计", ""]]
    (pipe,) = tables_from_content("年份 | 收入\n2023 | 10\n2024 | 12")
    assert pipe.rows == [["年份", "收入"], ["2023", "10"], ["2024", "12"]]
    (md,) = tables_from_content("| 年份 | 收入 |\n| --- | --- |\n| 2023 | 10 |")
    assert md.rows == [["年份", "收入"], ["2023", "10"]]
    assert tables_from_content("这只是一段普通的文字。") == []


def test_table_without_header_row_renders_without_separator():
    assert Table([["只有一行", "1"]], header_rows=0).render() == "| 只有一行 | 1 |"
