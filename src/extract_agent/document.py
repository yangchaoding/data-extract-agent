"""文档的统一内部表示：按阅读顺序排列的内容块。"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import cached_property

from .tables import Table
from .textutil import SourceText


@dataclass
class Block:
    """一个版面块。

    kind 是归一化后的类别：heading / text / table / caption / footnote / seal / formula / toc；
    label 保留 PaddleOCR-VL 原始的 block_label。
    """

    id: int
    kind: str
    label: str
    text: str
    page: int | None = None  # 页码，从 1 开始；无法确定时为 None
    page_end: int | None = None  # 跨页合并的表格：结束页
    bbox: list[int] | None = None  # 所在页图像上的 [x1, y1, x2, y2]
    table: Table | None = None
    level: int | None = None  # 标题层级：0 为文档标题，1 为一级标题
    path: tuple[str, ...] = ()  # 所在章节路径；标题块包含自身
    lead: tuple[str, ...] = ()  # 紧挨在它前面的题注、单位说明（"表3 …"、"单位：万元"）
    tokens: int = 0

    @property
    def pages(self) -> str:
        if self.page is None:
            return ""
        if self.page_end in (None, self.page):
            return str(self.page)
        return f"{self.page}-{self.page_end}"

    @cached_property
    def source(self) -> SourceText:
        """比对用的原文。读取阶段结束后块的内容不再变化，可以缓存。"""
        return SourceText(self.text)


_UNIT_LINE_RE = re.compile(r"单位\s*[:：为]|币种\s*[:：]|\bunit\s*[:：]", re.I)
_TABLE_NO_RE = re.compile(r"^(?:续?表|附表|table)\s*[\d一二三四五六七八九十]", re.I)


def is_lead_in(block: Block) -> bool:
    """题注，或单独成行的单位说明、表号：它们说的是紧跟在后面的那个块，要和它放在一起。"""
    if block.kind == "caption":
        return True
    return (
        block.kind == "text"
        and block.tokens <= 40
        and bool(_UNIT_LINE_RE.search(block.text) or _TABLE_NO_RE.match(block.text))
    )


@dataclass
class Document:
    name: str
    source: str
    blocks: list[Block]
    title: str | None = None
    page_count: int | None = None

    @property
    def tokens(self) -> int:
        return sum(b.tokens for b in self.blocks)
