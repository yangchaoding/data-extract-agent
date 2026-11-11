from extract_agent.chunker import chunk_document
from extract_agent.loader import document_from_result

from support import block, html_table, page

PARA = "双方应当遵守诚实信用原则，按照约定全面履行各自的义务，不得擅自变更或者解除合同。" * 3


def _sections(n: int) -> list[dict]:
    blocks = [block("doc_title", "服务合同")]
    for i in range(1, n + 1):
        blocks += [block("paragraph_title", f"第{i}条 约定{i}"), block("text", PARA), block("text", PARA)]
    return blocks


def test_small_document_is_kept_whole():
    doc = document_from_result(page(None, _sections(3)))
    chunks = chunk_document(doc, max_tokens=100, whole_under=doc.tokens)
    assert len(chunks) == 1 and chunks[0].id == "c1"
    assert len(chunks[0].blocks) == len(doc.blocks)


def test_chunks_break_at_headings_and_never_end_with_one():
    doc = document_from_result([page(i, _sections(4)[1:] if i else _sections(4)) for i in range(3)])
    chunks = chunk_document(doc, max_tokens=300)
    assert len(chunks) > 4
    for c in chunks:
        assert c.tokens <= 300 * 1.2
        assert c.blocks[-1].kind != "heading"  # 标题要和它下面的正文在一起
    # 所有内容都在，顺序不变
    assert [b.id for c in chunks for b in c.blocks] == [b.id for b in doc.blocks]
    # 每片都知道自己在哪一节、哪一页
    second = chunks[1]
    assert second.section.startswith("第") and second.pages
    assert second.render().startswith(f'<chunk id="c2" section="{second.section}" pages="{second.pages}">')


def test_tiny_sections_are_merged_into_one_chunk():
    blocks = []
    for i in range(1, 9):
        blocks += [block("paragraph_title", f"第{i}条"), block("text", "本条从略。")]
    chunks = chunk_document(document_from_result(page(None, blocks)), max_tokens=400)
    assert len(chunks) == 1


def test_large_table_is_split_by_rows_with_header_caption_and_unit():
    rows = [["股东名称", "期末持股数量", "持股比例(%)"]]
    rows += [[f"股东{i:02d}有限公司", f"{(60 - i) * 1_000_000:,}", f"{(60 - i) / 10:.2f}"] for i in range(1, 41)]
    doc = document_from_result(page(None, [
        block("paragraph_title", "一、前十名股东持股情况"),
        block("table_title", "表3 股东持股情况"),
        block("text", "单位：股"),
        block("table", html_table(rows)),
        block("text", "上述股东之间不存在关联关系。"),
    ]))
    chunks = chunk_document(doc, max_tokens=250)
    table_chunks = [c for c in chunks if c.has_table]
    assert len(table_chunks) >= 3
    for c in table_chunks:
        assert "| 股东名称 | 期末持股数量 | 持股比例(%) |" in c.text  # 表头
        assert "表3 股东持股情况" in c.text and "单位：股" in c.text  # 题注和单位说明
        assert c.section == "一、前十名股东持股情况"
    names = [n for c in table_chunks for n in ("股东%02d有限公司" % i for i in range(1, 41)) if n in c.text]
    assert names == ["股东%02d有限公司" % i for i in range(1, 41)]  # 每一行恰好出现一次
    (table_block,) = [b for b in doc.blocks if b.kind == "table"]
    assert all(any(b is table_block for b in c.blocks) for c in table_chunks)  # 各分段指向同一个表格块


def test_long_paragraph_is_split_at_sentence_ends():
    doc = document_from_result(page(None, [block("text", "这是一个很长的段落里的一句话。" * 80)]))
    chunks = chunk_document(doc, max_tokens=200)
    assert len(chunks) > 3
    assert all(c.text.endswith("。") for c in chunks)
    assert "".join(c.text for c in chunks) == doc.blocks[0].text


def test_table_of_contents_is_left_out():
    doc = document_from_result(page(None, [
        block("content", "第一节 重要提示 ...... 3\n第二节 公司简介 ...... 5"),
        block("paragraph_title", "第一节 重要提示"),
        block("text", "本公司董事会保证年度报告内容真实。"),
    ]))
    (chunk,) = chunk_document(doc, max_tokens=500)
    assert "......" not in chunk.text and "董事会" in chunk.text


def test_a_long_run_of_headings_does_not_grow_without_bound():
    # 没被识别成目录的目录页：一连串"标题"，后面一直没有正文
    blocks = [block("paragraph_title", f"第{i}条 这是目录里的一行标题文字") for i in range(1, 81)]
    blocks.append(block("text", "正文从这里开始。"))
    chunks = chunk_document(document_from_result(page(None, blocks)), max_tokens=200)
    assert len(chunks) > 3 and max(c.tokens for c in chunks) < 200 * 1.3
    assert sum(len(c.blocks) for c in chunks) == 81
