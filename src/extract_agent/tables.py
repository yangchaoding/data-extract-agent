"""表格解析：HTML（含 rowspan/colspan）→ 二维网格 → 紧凑的管道表文本。

PaddleOCR-VL 的表格块内容是 HTML。直接把 HTML 交给模型既费 token，
拆分大表时合并单元格也会丢上下文，所以先把合并单元格展开成规整的网格：
每一行都自带完整的行、列信息，可以按行拆分并重复表头。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from html.parser import HTMLParser

from .textutil import estimate_tokens, squash

_MAX_COLSPAN = 64
_REPEAT_FULL_LEN = 24  # 合并单元格展开时，短文本逐格重复，长文本用同上符号代替
_DITTO = "〃"
_MAX_HEADER_ROWS = 4


@dataclass
class _Cell:
    text: str
    rowspan: int = 1
    colspan: int = 1
    header: bool = False


def _span(value: object) -> int:
    try:
        return max(1, int(str(value).strip()))
    except (TypeError, ValueError):
        return 1


def _clean_cell(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().replace("|", "｜")


class _TableHTMLParser(HTMLParser):
    """宽容的表格解析：容忍未闭合的 td/tr、带 style 的标签、单元格内的 img/br、嵌套表格。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.tables: list[list[list[_Cell]]] = []
        self._depth = 0
        self._rows: list[list[_Cell]] = []
        self._row: list[_Cell] | None = None
        self._cell: _Cell | None = None
        self._buf: list[str] = []

    def _close_cell(self) -> None:
        if self._cell is not None:
            self._cell.text = _clean_cell("".join(self._buf))
            if self._row is None:
                self._row = []
            self._row.append(self._cell)
        self._cell = None
        self._buf = []

    def _close_row(self) -> None:
        self._close_cell()
        if self._row is not None:
            # 空行也要保留：它可能完全被上方单元格的 rowspan 占据
            self._rows.append(self._row)
        self._row = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "table":
            self._depth += 1
            if self._depth == 1:
                self._rows, self._row, self._cell, self._buf = [], None, None, []
            return
        if self._depth == 0:
            return
        if self._depth > 1:  # 嵌套表格：内容并入外层单元格
            if tag in ("td", "th", "tr", "br"):
                self._buf.append(" ")
            return
        if tag == "tr":
            self._close_row()
            self._row = []
        elif tag in ("td", "th"):
            self._close_cell()
            if self._row is None:
                self._row = []
            a = dict(attrs)
            self._cell = _Cell(
                "",
                rowspan=_span(a.get("rowspan")),
                colspan=min(_span(a.get("colspan")), _MAX_COLSPAN),
                header=tag == "th",
            )
        elif tag == "br":
            self._buf.append(" ")

    def handle_endtag(self, tag: str) -> None:
        if tag == "table":
            if self._depth == 1:
                self._close_row()
                self.tables.append(self._rows)
                self._rows = []
            if self._depth > 0:
                self._depth -= 1
            return
        if self._depth != 1:
            return
        if tag in ("td", "th"):
            self._close_cell()
        elif tag == "tr":
            self._close_row()

    def handle_data(self, data: str) -> None:
        if self._depth >= 1 and self._cell is not None:
            self._buf.append(data)

    def close(self) -> None:
        super().close()
        if self._depth >= 1:  # 表格没有闭合标签
            self._close_row()
            if self._rows:
                self.tables.append(self._rows)
            self._rows = []
            self._depth = 0


def _detect_header_rows(raw_rows: list[list[_Cell]]) -> tuple[int, bool]:
    """返回 (表头行数, 表头是否由 <th> 明确标出)。"""
    n = len(raw_rows)
    if n <= 1:
        return 0, False
    th_rows = 0
    for cells in raw_rows:
        if cells and all(c.header for c in cells):
            th_rows += 1
        else:
            break
    if 0 < th_rows < n:
        return min(th_rows, _MAX_HEADER_ROWS), True
    first = raw_rows[0]
    if not first:
        return 1, False
    rows = max(c.rowspan for c in first)
    if any(c.colspan > 1 for c in first):  # 分组表头：下一行是子表头
        rows = max(rows, 2)
    return max(1, min(rows, _MAX_HEADER_ROWS, n - 1)), False


def _expand(raw_rows: list[list[_Cell]]) -> list[list[str]]:
    """把 rowspan/colspan 展开成矩形网格。"""
    n_rows = len(raw_rows)
    occupied: dict[tuple[int, int], str] = {}
    n_cols = 0
    for r, cells in enumerate(raw_rows):
        c = 0
        for cell in cells:
            while (r, c) in occupied:
                c += 1
            for dr in range(min(cell.rowspan, n_rows - r)):
                for dc in range(cell.colspan):
                    origin = dr == 0 and dc == 0
                    text = cell.text if origin or len(cell.text) <= _REPEAT_FULL_LEN else _DITTO
                    occupied.setdefault((r + dr, c + dc), text)
            c += cell.colspan
            n_cols = max(n_cols, c)
    return [[occupied.get((r, c), "") for c in range(n_cols)] for r in range(n_rows)]


def _fmt_row(row: list[str]) -> str:
    return "| " + " | ".join(cell if cell else " " for cell in row) + " |"


@dataclass
class Table:
    rows: list[list[str]]
    header_rows: int = 1
    explicit_header: bool = False  # 表头是识别结果里明确标出的（<th>），不是猜的

    @property
    def n_cols(self) -> int:
        return len(self.rows[0]) if self.rows else 0

    def absorb(self, other: "Table") -> bool:
        """把下一页的续表接到本表后面；other 不像是本表的续表时不动并返回 False。"""
        if other.n_cols != self.n_cols:
            return False
        head = {tuple(squash(c) for c in row) for row in self.rows[: self.header_rows]}
        repeated = 0  # 续表开头重复的表头行数
        for row in other.rows:
            if tuple(squash(c) for c in row) not in head:
                break
            repeated += 1
        if other.explicit_header and repeated == 0:  # 有自己的、不一样的表头：是另一张表
            return False
        self.rows.extend(other.rows[repeated:])
        return True

    @property
    def body(self) -> list[list[str]]:
        return self.rows[self.header_rows :]

    def render(self, start: int = 0, end: int | None = None) -> str:
        """渲染为管道表。start/end 是数据行（不含表头）的范围，表头总是保留。"""
        head = self.rows[: self.header_rows]
        lines = [_fmt_row(r) for r in head]
        if head:
            lines.append("| " + " | ".join(["---"] * self.n_cols) + " |")
        lines += [_fmt_row(r) for r in self.body[start:end]]
        return "\n".join(lines)

    def split_rows(self, max_tokens: int) -> list[tuple[int, int]]:
        """把数据行切成若干段，使"表头 + 每段"不超过 max_tokens（每段至少一行）。"""
        head_tokens = sum(estimate_tokens(_fmt_row(r)) for r in self.rows[: self.header_rows])
        head_tokens += self.n_cols * 2
        budget = max(max_tokens - head_tokens, max_tokens // 3)
        ranges: list[tuple[int, int]] = []
        start = used = 0
        body = self.body
        for i, row in enumerate(body):
            t = estimate_tokens(_fmt_row(row))
            if i > start and used + t > budget:
                ranges.append((start, i))
                start, used = i, 0
            used += t
        if start < len(body) or not ranges:
            ranges.append((start, len(body)))
        return ranges


def _build(raw_rows: list[list[_Cell]]) -> Table | None:
    if not any(raw_rows):
        return None
    header_rows, explicit = _detect_header_rows(raw_rows)
    grid = _expand(raw_rows)
    keep = [any(cell for cell in row) for row in grid]
    rows = [row for row, k in zip(grid, keep) if k]
    if not rows or not rows[0]:
        return None
    header_rows = min(sum(keep[:header_rows]), max(len(rows) - 1, 0))
    return Table(rows, header_rows=header_rows, explicit_header=explicit and header_rows > 0)


def parse_html_tables(html: str) -> list[Table]:
    parser = _TableHTMLParser()
    try:
        parser.feed(html)
        parser.close()
    except Exception:  # noqa: BLE001 - 标签严重损坏时当作没有表格，交给调用方按文本处理
        return []
    return [t for t in (_build(raw) for raw in parser.tables) if t is not None]


def _pad(rows: list[list[str]]) -> list[list[str]]:
    width = max((len(r) for r in rows), default=0)
    return [r + [""] * (width - len(r)) for r in rows]


_OTSL_TAG_RE = re.compile(r"<(fcel|ecel|lcel|ucel|xcel|ched|rhed|srow|nl)>")


def parse_otsl(text: str) -> Table | None:
    """OTSL 标记的兜底解析。

    PaddleOCR-VL 内部先生成 OTSL 再转 HTML；转换失败时块内容里留下的就是 OTSL 原文。
    """
    tokens = _OTSL_TAG_RE.split(text)
    if len(tokens) < 3:
        return None
    rows: list[list[str]] = []
    row: list[str] = []
    for i in range(1, len(tokens) - 1, 2):
        tag, content = tokens[i], _clean_cell(tokens[i + 1])
        if tag == "nl":
            rows.append(row)
            row = []
        elif tag == "ecel":
            row.append("")
        elif tag == "lcel":  # 与左侧单元格合并
            row.append(row[-1] if row else "")
        elif tag in ("ucel", "xcel"):  # 与上方单元格合并
            above = rows[-1] if rows else []
            row.append(above[len(row)] if len(row) < len(above) else "")
        else:
            row.append(content)
    if row:
        rows.append(row)
    rows = [r for r in _pad(rows) if any(r)]
    if not rows:
        return None
    return Table(rows, header_rows=1 if len(rows) > 1 else 0)


_SEPARATOR_CELL_RE = re.compile(r":?-{2,}:?")


def parse_pipe_table(text: str) -> Table | None:
    """Markdown 管道表，或"a | b"形式的多行文本（图表识别结果是这种格式）。"""
    lines = [ln.strip() for ln in text.strip().splitlines() if ln.strip()]
    if len(lines) < 2 or not all("|" in ln for ln in lines):
        return None
    rows: list[list[str]] = []
    for ln in lines:
        cells = [_clean_cell(c) for c in ln.strip("|").split("|")]
        if any(cells) and all(_SEPARATOR_CELL_RE.fullmatch(c) for c in cells if c):
            continue
        rows.append(cells)
    rows = [r for r in _pad(rows) if any(r)]
    if len(rows) < 2:
        return None
    return Table(rows, header_rows=1)


def tables_from_content(content: str) -> list[Table]:
    """从块内容里解析表格：HTML → OTSL → 管道文本，都不是则返回空列表。"""
    if "<table" in content.lower():
        tables = parse_html_tables(content)
        if tables:
            return tables
    if _OTSL_TAG_RE.search(content):
        table = parse_otsl(content)
        if table:
            return [table]
    table = parse_pipe_table(content)
    return [table] if table else []
