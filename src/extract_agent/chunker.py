"""按文档结构自动切片。

规则：
  - 以版面块为最小单位，尽量在章节标题处断开，不在段落中间断开
  - 标题、表格题注、"单位：万元"这类说明，必须和它后面的内容在同一个切片里
  - 表格整体保留；超过切片上限的大表按行拆分，每段重复表头、题注和单位说明
  - 超长段落按句子拆分
  - 整篇文档很小时不切片（票据、证照）
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from functools import cached_property

from .document import Block, Document, is_lead_in
from .textutil import SourceText, estimate_tokens

_NO_LIMIT = 10**9
_SENTENCE_END_RE = re.compile(r"(?<=[。！？；!?;\n])")


@dataclass
class Chunk:
    index: int
    text: str
    path: tuple[str, ...]  # 所在章节路径（片内各块的公共部分）
    blocks: list[Block] = field(repr=False)  # 片内的版面块；大表的各个分段共用同一个块
    page_start: int | None
    page_end: int | None
    tokens: int

    @property
    def id(self) -> str:
        return f"c{self.index + 1}"

    @property
    def headings(self) -> list[str]:
        return [b.text for b in self.blocks if b.kind == "heading"]

    @property
    def has_table(self) -> bool:
        return any(b.table is not None for b in self.blocks)

    @property
    def pages(self) -> str:
        if self.page_start is None:
            return ""
        if self.page_end in (None, self.page_start):
            return str(self.page_start)
        return f"{self.page_start}-{self.page_end}"

    @property
    def section(self) -> str:
        return " > ".join(self.path)

    @cached_property
    def source(self) -> SourceText:
        return SourceText(self.text)

    def render(self) -> str:
        """给模型看的形式：带 id、章节、页码的片段。"""
        attrs = [f'id="{self.id}"']
        if self.path:
            attrs.append(f'section="{self.section}"')
        if self.pages:
            attrs.append(f'pages="{self.pages}"')
        return f"<chunk {' '.join(attrs)}>\n{self.text}\n</chunk>"


@dataclass
class _Piece:
    block: Block
    text: str
    tokens: int
    glue: bool = False  # 标题、题注：要和后面的内容放在一起
    alone: bool = False  # 大表拆出的后续分段：另起一片


def _render(block: Block) -> str:
    if block.kind == "heading":
        return "#" * min((block.level or 0) + 1, 6) + " " + block.text
    if block.kind == "seal":
        return f"[印章] {block.text}"
    if block.kind == "footnote":
        return f"[注] {block.text}"
    return block.text


def _split_text(text: str, max_tokens: int) -> list[str]:
    parts: list[str] = []
    for sentence in _SENTENCE_END_RE.split(text):
        tokens = estimate_tokens(sentence)
        if tokens <= max_tokens:
            parts.append(sentence)
            continue
        step = max(100, int(len(sentence) * max_tokens / tokens))
        parts.extend(sentence[i : i + step] for i in range(0, len(sentence), step))
    out: list[str] = []
    cur, used = "", 0
    for part in parts:
        tokens = estimate_tokens(part)
        if cur and used + tokens > max_tokens:
            out.append(cur)
            cur, used = "", 0
        cur += part
        used += tokens
    if cur.strip():
        out.append(cur)
    return [p.strip() for p in out if p.strip()]


def _pieces(block: Block, max_tokens: int) -> list[_Piece]:
    if block.kind == "heading" or is_lead_in(block):
        text = _render(block)
        return [_Piece(block, text, estimate_tokens(text), glue=True)]
    if block.tokens <= max_tokens:
        text = _render(block)
        return [_Piece(block, text, estimate_tokens(text))]
    if block.table is not None:
        ranges = block.table.split_rows(max_tokens)
        if len(ranges) > 1:
            pieces = []
            for n, (start, end) in enumerate(ranges):
                head = [f"（表格分段 {n + 1}/{len(ranges)}，表头已重复）"]
                if n > 0:  # 第一段前面本来就有题注和单位说明，后面各段要重复一遍
                    head = list(block.lead) + head
                text = "\n".join(head + [block.table.render(start, end)])
                pieces.append(_Piece(block, text, estimate_tokens(text), alone=n > 0))
            return pieces
    return [_Piece(block, t, estimate_tokens(t)) for t in _split_text(_render(block), max_tokens)]


def _common_prefix(paths: list[tuple[str, ...]]) -> tuple[str, ...]:
    if not paths:
        return ()
    prefix = paths[0]
    for path in paths[1:]:
        n = 0
        while n < min(len(prefix), len(path)) and prefix[n] == path[n]:
            n += 1
        prefix = prefix[:n]
    return prefix


def _make_chunk(index: int, pieces: list[_Piece]) -> Chunk:
    blocks: list[Block] = []
    for piece in pieces:
        if not blocks or blocks[-1] is not piece.block:
            blocks.append(piece.block)
    pages = [p for b in blocks for p in (b.page, b.page_end) if p is not None]
    return Chunk(
        index=index,
        text="\n\n".join(p.text for p in pieces),
        path=_common_prefix([b.path for b in blocks]),
        blocks=blocks,
        page_start=min(pages) if pages else None,
        page_end=max(pages) if pages else None,
        tokens=sum(p.tokens for p in pieces),
    )


def chunk_document(
    doc: Document,
    max_tokens: int = 1000,
    min_tokens: int | None = None,
    whole_under: int = 0,
) -> list[Chunk]:
    """把文档切成片。

    max_tokens: 每片的大小上限（估算 token）。
    min_tokens: 当前片不到这个大小时，遇到新标题也不断开（避免碎片），默认 max_tokens/4。
    whole_under: 整篇文档不超过这个大小时不切片，返回单独一片。
    """
    if doc.tokens <= whole_under:
        max_tokens = min_tokens = _NO_LIMIT
    if min_tokens is None:
        min_tokens = max_tokens // 4

    chunks: list[Chunk] = []
    cur: list[_Piece] = []

    def close(keep_tail: bool = True) -> None:
        nonlocal cur
        carry: list[_Piece] = []
        while keep_tail and cur and cur[-1].glue:  # 末尾的标题、题注留给下一片
            carry.insert(0, cur.pop())
        if cur:
            chunks.append(_make_chunk(len(chunks), cur))
        cur = carry

    for block in doc.blocks:
        if block.kind == "toc":  # 目录页会命中所有关键词，对定位只有干扰
            continue
        for piece in _pieces(block, max_tokens):
            has_content = any(not p.glue for p in cur)
            used = sum(p.tokens for p in cur)
            if has_content and (
                piece.alone
                or used + piece.tokens > max_tokens
                or (block.kind == "heading" and used >= min_tokens)
            ):
                close()
            elif not has_content and used > max_tokens:
                # 一长串标题后面一直没有正文（多半是没被识别成目录的目录页）：不再等，自成一片
                close(keep_tail=False)
            cur.append(piece)
    if cur:
        chunks.append(_make_chunk(len(chunks), cur))
    return chunks
