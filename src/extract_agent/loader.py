"""读取 PaddleOCR-VL 的识别结果，转成统一的 Document。

支持的输入：
  - save_to_json() 保存的 JSON（每页一个文件，或 restructure_pages(concatenate_pages=True) 的合并结果）
  - 服务化接口的返回（result.layoutParsingResults[].prunedResult）
  - save_to_markdown() 保存的 Markdown
  - 放着上述文件的目录（可以同时放多份文档的结果）
"""

from __future__ import annotations

import itertools
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from .document import Block, Document, is_lead_in
from .tables import Table, tables_from_content
from .textutil import estimate_tokens, one_line, squash

HEADING_LABELS = {"doc_title", "paragraph_title", "abstract_title", "reference_title", "content_title"}
CAPTION_LABELS = {"table_title", "figure_title", "chart_title"}
FOOTNOTE_LABELS = {"footnote", "vision_footnote"}
FORMULA_LABELS = {"formula", "display_formula", "inline_formula"}
MARGIN_LABELS = {"header", "footer", "aside_text"}  # 页眉页脚：同样的内容只保留第一次出现
SKIP_LABELS = {"number", "formula_number", "header_image", "footer_image"}

_MARKUP_HINTS = ("<div", "<img", "<span", "<p>", "<p ", "<br", "<sup", "<sub", "</")
_MD_IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
_BLANK_LINES_RE = re.compile(r"\n\s*\n+")
_HASHES_RE = re.compile(r"^\s*(#{1,6})\s+")


class _TextOnly(HTMLParser):
    """丢掉标签只留文字；块级标签处换行。"""

    _BREAKS = {"br", "p", "div", "tr", "li"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag in self._BREAKS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in self._BREAKS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


def plain_text(content: str) -> str:
    """去掉 format_block_content / Markdown 输出里附带的排版标记，只留正文。"""
    lowered = content.lower()
    if any(h in lowered for h in _MARKUP_HINTS):
        parser = _TextOnly()
        parser.feed(content)
        parser.close()
        content = "".join(parser.parts)
    content = _MD_IMAGE_RE.sub("", content)
    return _BLANK_LINES_RE.sub("\n", content).strip()


# ---------------------------------------------------------------------------
# 标题层级：JSON 结果里不带层级，按编号样式推断
# ---------------------------------------------------------------------------

_CN_NUM = "一二三四五六七八九十百零〇"
_HEADING_STYLES: list[tuple[str, re.Pattern[str]]] = [
    ("part", re.compile(rf"^第[{_CN_NUM}\d]+[编篇部卷]")),
    ("chapter", re.compile(rf"^第[{_CN_NUM}\d]+章")),
    ("section", re.compile(rf"^第[{_CN_NUM}\d]+节")),
    ("article", re.compile(rf"^第[{_CN_NUM}\d]+条")),
    ("cn", re.compile(rf"^[{_CN_NUM}]+[、．.]")),
    ("cn_paren", re.compile(rf"^[（(][{_CN_NUM}]+[）)]")),
    ("num_paren", re.compile(r"^(?:[（(]\d{1,2}[）)]|\d{1,2}[）)])")),
    ("word", re.compile(r"^(?:chapter|section|part|article|appendix)\s+\w+", re.I)),
    ("roman", re.compile(r"^[IVX]+[、.．\s]")),
]
_DOTTED_RE = re.compile(r"^(\d{1,2}(?:\.\d{1,2})*)[、.．]?(?![\d年月日%.．])")


def heading_style(title: str) -> str | None:
    """标题的编号样式，例如"第一章"→chapter、"1.2"→num2；没有编号返回 None。"""
    t = title.strip()
    for name, pattern in _HEADING_STYLES:
        if pattern.match(t):
            return name
    m = _DOTTED_RE.match(t)
    if m:
        return f"num{m.group(1).count('.') + 1}"
    return None


class _Leveler:
    """按编号样式第一次出现的先后决定层级：先出现的样式层级更高。"""

    def __init__(self) -> None:
        self.stack: list[str] = []

    def level(self, title: str) -> int:
        style = heading_style(title)
        if style is None:
            return min(len(self.stack) + 1, 6)
        if style in self.stack:
            del self.stack[self.stack.index(style) + 1 :]
        else:
            self.stack.append(style)
        return min(len(self.stack), 6)


class _Builder:
    def __init__(self) -> None:
        self.blocks: list[Block] = []
        self.title: str | None = None
        self._ids = itertools.count()
        self._leveler = _Leveler()
        self._stack: list[tuple[int, str]] = []
        self._lead: list[str] = []  # 紧挨在下一个块前面的题注、单位说明

    def add(
        self,
        kind: str,
        label: str,
        text: str,
        *,
        page: int | None = None,
        bbox: list[int] | None = None,
        table: Table | None = None,
        level: int | None = None,
    ) -> Block | None:
        text = text.strip()
        if not text:
            return None
        if kind == "heading":
            title = one_line(text, 80)
            is_doc_title = label == "doc_title" or level == 0
            if is_doc_title and self.title is None and not self._stack:
                level = 0
                self.title = title
            else:
                # 文档中途再出现的"文档标题"（附件、分册）按一级标题处理
                if is_doc_title:
                    level = 1
                elif level is None:
                    level = self._leveler.level(title)
                level = max(1, min(level, 6))
                while self._stack and self._stack[-1][0] >= level:
                    self._stack.pop()
                self._stack.append((level, title))
        else:
            level = None
        block = Block(
            id=next(self._ids),
            kind=kind,
            label=label,
            text=text,
            page=page,
            bbox=bbox,
            table=table,
            level=level,
            path=tuple(t for _, t in self._stack),
            lead=tuple(self._lead),
            tokens=estimate_tokens(text),
        )
        self.blocks.append(block)
        self._lead = self._lead + [text] if is_lead_in(block) else []
        return block

    def remove(self, block: Block) -> None:
        self.blocks = [b for b in self.blocks if b is not block]
        self._lead = []


def _add_block(
    builder: _Builder, label: str, content: str, page: int | None, bbox: list[int] | None
) -> list[Block]:
    added: list[Block | None] = []
    if label in HEADING_LABELS:
        level = None
        m = _HASHES_RE.match(content)
        if m:  # format_block_content=True 或 Markdown：层级已经写在 # 里
            level = len(m.group(1)) - 1
            content = content[m.end() :]
        added.append(
            builder.add("heading", label, one_line(plain_text(content)), page=page, bbox=bbox, level=level)
        )
    elif label in ("table", "chart"):
        tables = tables_from_content(content)
        for table in tables:
            added.append(builder.add("table", label, table.render(), page=page, bbox=bbox, table=table))
        if not tables:
            added.append(builder.add("text", label, plain_text(content), page=page, bbox=bbox))
    else:
        if label in CAPTION_LABELS:
            kind = "caption"
        elif label in FOOTNOTE_LABELS:
            kind = "footnote"
        elif label in FORMULA_LABELS:
            kind = "formula"
        elif label == "seal":
            kind = "seal"
        elif label == "content":
            kind = "toc"
        else:
            kind = "text"
        added.append(builder.add(kind, label, plain_text(content), page=page, bbox=bbox))
    return [b for b in added if b is not None]


# ---------------------------------------------------------------------------
# JSON 结果
# ---------------------------------------------------------------------------


def _unwrap_pages(data: Any) -> list[dict]:
    """把各种外层包装统一成"每页一个 dict（含 parsing_res_list）"的列表。"""
    if isinstance(data, list):
        pages: list[dict] = []
        for item in data:
            pages.extend(_unwrap_pages(item))
        return pages
    if isinstance(data, dict):
        if isinstance(data.get("result"), dict):
            data = data["result"]
        if isinstance(data.get("layoutParsingResults"), list):
            return [
                item["prunedResult"]
                for item in data["layoutParsingResults"]
                if isinstance(item, dict) and isinstance(item.get("prunedResult"), dict)
            ]
        if isinstance(data.get("prunedResult"), dict):
            data = data["prunedResult"]
        if isinstance(data.get("res"), dict):
            data = data["res"]
        if isinstance(data.get("parsing_res_list"), list):
            return [data]
    raise ValueError("不是 PaddleOCR-VL 的识别结果：找不到 parsing_res_list")


def _bbox(value: Any) -> list[int] | None:
    if isinstance(value, (list, tuple)) and len(value) == 4:
        try:
            return [int(v) for v in value]
        except (TypeError, ValueError):
            return None
    return None


def _similar_width(a: list[int] | None, b: list[int] | None) -> bool:
    if not a or not b:
        return True
    wa, wb = a[2] - a[0], b[2] - b[0]
    if wa <= 0 or wb <= 0:
        return True
    return abs(wa - wb) / min(wa, wb) < 0.1


_CONTINUED_RE = re.compile(r"续上?表|[（(]\s*续\s*[)）]|^续$|continued|cont['’‘]d", re.I)


def _is_head_note(block: Block) -> bool:
    """页首、表格之前的题注和说明（"续表"、"表3 …（续）"、"单位：万元"）：不算正文。"""
    if block.kind not in ("caption", "text"):
        return False
    return is_lead_in(block) or (len(block.text) <= 30 and bool(_CONTINUED_RE.search(block.text)))


def _repeats(note: Block, table: Block) -> bool:
    """页首的这条说明只是"续表"字样，或者在重复上一页那张表的题注、单位说明。"""
    if len(note.text) <= 30 and _CONTINUED_RE.search(note.text):
        return True
    return squash(note.text) in {squash(t) for t in table.lead}


def _from_pages(pages: list[dict], name: str, source: str) -> Document:
    first = pages[0] if pages else {}
    declared = first.get("page_count")
    # 合并成一页的结果里没有每个块的页码
    concatenated = (
        len(pages) == 1
        and first.get("page_index") is None
        and isinstance(declared, int)
        and declared > 1
    )
    builder = _Builder()
    by_global: dict[Any, Block] = {}
    seen_margins: set[str] = set()
    last_page = 0
    prev_tail: Block | None = None  # 上一页的最后一个正文块
    for i, page in enumerate(pages):
        idx = page.get("page_index")
        page_no = None if concatenated else (idx + 1 if isinstance(idx, int) else i + 1)
        last_page = max(last_page, page_no or 0)
        at_head = True  # 还没有遇到本页的正文
        head_notes: list[Block] = []  # 页首、正文之前的题注和说明
        tail: Block | None = None
        for raw in page.get("parsing_res_list") or []:
            if not isinstance(raw, dict):
                continue
            label = str(raw.get("block_label") or "text")
            content = raw.get("block_content")
            content = content if isinstance(content, str) else ""
            bbox = _bbox(raw.get("block_bbox"))
            gid, group = raw.get("global_block_id"), raw.get("global_group_id")
            if label in SKIP_LABELS:
                continue
            if label == "table" and not content.strip():
                # restructure_pages 合并跨页表格后，后半段内容为空并指向前半段
                target = by_global.get(group) if group != gid else None
                if target is not None and page_no is not None:
                    target.page_end = max(target.page_end or 0, page_no)
                continue
            if label in MARGIN_LABELS:
                # 页眉页脚：同样的内容（不计页码）只保留第一次出现；本页只有页码的不要
                key = squash(re.sub(r"\d+", "", plain_text(content)))
                if re.search(r"\w", key) and key not in seen_margins:
                    seen_margins.add(key)
                    _add_block(builder, label, content, page_no, bbox)
                continue
            if label in FOOTNOTE_LABELS or label == "seal":  # 不影响跨页表格的衔接
                _add_block(builder, label, content, page_no, bbox)
                continue
            if (
                at_head
                and label == "table"
                and prev_tail is not None
                and prev_tail.label == "table"
                and prev_tail.table is not None
                and _similar_width(prev_tail.bbox, bbox)
                and all(_repeats(note, prev_tail) for note in head_notes)
            ):
                parts = tables_from_content(content)
                if len(parts) == 1 and prev_tail.table.absorb(parts[0]):
                    # 上一页以表格结尾、本页以表格开头，列数一致，中间只隔着重复的题注：是同一张表
                    prev_tail.text = prev_tail.table.render()
                    prev_tail.tokens = estimate_tokens(prev_tail.text)
                    prev_tail.page_end = page_no
                    for note in head_notes:
                        builder.remove(note)
                    if gid is not None:
                        by_global[gid] = prev_tail
                    at_head, tail = False, prev_tail
                    continue
            added = _add_block(builder, label, content, page_no, bbox)
            if at_head and label != "table" and added and _is_head_note(added[0]):
                head_notes += added
                continue
            at_head = False
            # 没有内容的块（未识别的图片等）也算隔开了表格，所以这里置空
            tail = added[-1] if added else None
            if added and gid is not None:
                by_global[gid] = added[-1]
        prev_tail = tail
    page_count = declared if concatenated else (last_page or None)
    return Document(
        name=name, source=source, blocks=builder.blocks, title=builder.title, page_count=page_count
    )


# ---------------------------------------------------------------------------
# Markdown 结果
# ---------------------------------------------------------------------------


def _markdown_page(builder: _Builder, text: str, page: int | None) -> None:
    lines = text.splitlines()
    para: list[str] = []

    def flush() -> None:
        if not para:
            return
        is_table = len(para) >= 2 and all("|" in ln for ln in para)
        _add_block(builder, "table" if is_table else "text", "\n".join(para), page, None)
        para.clear()

    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if "<table" in s.lower():
            flush()
            buf = [s]
            while "</table>" not in buf[-1].lower() and i + 1 < len(lines):
                i += 1
                buf.append(lines[i].strip())
            _add_block(builder, "table", "\n".join(buf), page, None)
        elif _HASHES_RE.match(s):
            flush()
            _add_block(builder, "doc_title" if s.startswith("# ") else "paragraph_title", s, page, None)
        elif not s:
            flush()
        else:
            para.append(s)
        i += 1
    flush()


def _from_markdown(texts: list[str], name: str, source: str) -> Document:
    builder = _Builder()
    paged = len(texts) > 1
    for n, text in enumerate(texts):
        _markdown_page(builder, text, n + 1 if paged else None)
    return Document(
        name=name,
        source=source,
        blocks=builder.blocks,
        title=builder.title,
        page_count=len(texts) if paged else None,
    )


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

_MD_SUFFIXES = {".md", ".markdown", ".mdown", ".mkd", ".txt"}
_PAGE_FILE_RE = re.compile(r"^(.*)_(\d+)$")


def _natural_key(path: Path) -> list:
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", path.name)]


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def _json_documents(path: Path) -> list[Document]:
    """目录里的 JSON 结果按原始文件（input_path）分组，每组是一份文档。"""
    groups: dict[str, list[tuple[int, dict]]] = {}
    for file in sorted(path.glob("*.json"), key=_natural_key):
        try:
            pages = _unwrap_pages(json.loads(_read(file)))
        except (ValueError, OSError):
            continue  # 目录里其他用途的 JSON
        for page in pages:
            src = page.get("input_path")
            if isinstance(src, str) and src:
                name = Path(src.replace("\\", "/")).stem
            else:
                name = re.sub(r"_res$", "", file.stem)
            group = groups.setdefault(name, [])
            idx = page.get("page_index")
            group.append((idx if isinstance(idx, int) else len(group), page))
    docs = []
    for name, group in groups.items():
        group.sort(key=lambda item: item[0])
        docs.append(_from_pages([p for _, p in group], name, str(path)))
    return docs


def _markdown_documents(path: Path) -> list[Document]:
    """目录里的 Markdown：name_0.md、name_1.md… 这样从 0 连续编号的是同一份文档的各页。"""
    files = sorted((f for f in path.iterdir() if f.suffix.lower() in _MD_SUFFIXES), key=_natural_key)
    paged: dict[str, dict[int, Path]] = {}
    for f in files:
        m = _PAGE_FILE_RE.match(f.stem)
        if m:
            paged.setdefault(m.group(1), {})[int(m.group(2))] = f
    docs, used = [], set()
    for base, by_no in paged.items():
        if len(by_no) >= 2 and sorted(by_no) == list(range(len(by_no))):
            ordered = [by_no[n] for n in range(len(by_no))]
            used.update(ordered)
            docs.append(_from_markdown([_read(f) for f in ordered], base, str(path)))
    docs += [_from_markdown([_read(f)], f.stem, str(f)) for f in files if f not in used]
    return docs


def document_from_result(data: Any, name: str = "document", source: str = "") -> Document:
    """从已经读进内存的识别结果（dict 或 list）构造 Document。"""
    return _from_pages(_unwrap_pages(data), name, source)


def load_documents(path: str | Path) -> list[Document]:
    """读取识别结果。path 是 JSON / Markdown 文件，或放着这些文件的目录（可以含多份文档）。"""
    p = Path(path)
    if p.is_dir():
        docs = _json_documents(p) or _markdown_documents(p)
        if not docs:
            raise ValueError(f"目录里没有找到 PaddleOCR-VL 的 JSON 或 Markdown 结果: {p}")
        return docs
    if not p.is_file():
        raise FileNotFoundError(f"找不到输入: {p}")
    if p.suffix.lower() in _MD_SUFFIXES:
        return [_from_markdown([_read(p)], p.stem, str(p))]
    return [document_from_result(json.loads(_read(p)), re.sub(r"_res$", "", p.stem), str(p))]


def load_document(path: str | Path) -> Document:
    """读取一份文档的识别结果；path 下有多份文档时报错（那种情况用 load_documents）。"""
    docs = load_documents(path)
    if len(docs) != 1:
        names = "、".join(d.name for d in docs[:5])
        raise ValueError(f"{path} 下有 {len(docs)} 份文档（{names}…），请用 load_documents")
    return docs[0]
