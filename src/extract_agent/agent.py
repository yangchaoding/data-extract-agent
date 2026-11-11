"""抽取 agent：定位 → 抽取 → 核对；核对不过就带着问题重问，找不到就扩大范围。

一份文档的处理过程：
  1. 切片，建关键词索引；片段多的时候再让模型按目录给每个字段挑片段（定位）
  2. 每个字段取排在最前面的几个片段，候选片段合在一起不超预算的字段放进同一次调用（抽取）
  3. 模型给出的每个值都要带原文证据。程序核对证据确实是片段里的原文、值确实能从证据里读出来（核对）
  4. 没通过核对的字段，带着具体问题重问；没找到的字段，换下一批片段再找
  5. 列表字段顺着找到的位置向相邻片段延伸，直到不再有新的条目
"""

from __future__ import annotations

import json
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable, TypeVar

from .chunker import Chunk, chunk_document
from .document import Block, Document
from .index import ChunkIndex
from .llm import LLM, OutputTruncated, parse_json
from .prompts import ROUTE_SYSTEM, SYSTEM, extraction_prompt, routing_prompt
from .schema import Field, Schema
from .textutil import match_key, quote_parts, squash
from .values import locate_evidence, read_value

T = TypeVar("T")
R = TypeVar("R")

_LAST = 10**9


@dataclass(eq=False)
class Item:
    """一个抽取到的值（标量字段的值，或列表字段的一项）和它的出处。"""

    value: Any
    evidence: str = ""
    chunk: Chunk | None = None  # 证据所在的片段
    block: Block | None = None  # 证据所在的版面块（页码、坐标由它给出）
    issues: list[str] = field(default_factory=list)

    @property
    def verified(self) -> bool:
        return not self.issues

    def to_dict(self) -> dict:
        out: dict[str, Any] = {"value": self.value, "evidence": self.evidence}
        if self.chunk is not None:
            out["chunk"] = self.chunk.id
        if self.block is not None:
            if self.block.pages:
                out["pages"] = self.block.pages
            if self.block.bbox:
                out["bbox"] = self.block.bbox
            if self.block.path:
                out["section"] = " > ".join(self.block.path)
        elif self.chunk is not None:
            if self.chunk.pages:
                out["pages"] = self.chunk.pages
            if self.chunk.section:
                out["section"] = self.chunk.section
        if self.issues:
            out["issues"] = self.issues
        return out


@dataclass
class FieldResult:
    """一个字段的结果。

    status: ok 通过核对；unverified 有值但没通过核对；missing 没找到。
    issues 不为空说明这个字段需要人看一眼，即使 status 是 ok（比如读出了两个不同的值）。
    """

    field: Field
    status: str
    items: list[Item] = field(default_factory=list)
    issues: list[str] = field(default_factory=list)
    alternatives: list[Item] = field(default_factory=list)  # 标量字段：同样通过了核对的其他值
    rounds: int = 0
    searched: tuple[int, int] = (0, 0)  # 看过的片段数、片段总数

    @property
    def value(self) -> Any:
        if self.field.type == "list":
            return [it.value for it in self.items]
        return self.items[0].value if self.items else None

    @property
    def needs_review(self) -> bool:
        return self.status == "unverified" or bool(self.issues)

    def to_dict(self) -> dict:
        out: dict[str, Any] = {"status": self.status}
        issues = list(self.issues)
        if self.field.type == "list":
            out["items"] = [it.to_dict() for it in self.items]
        elif self.items:
            out.update(self.items[0].to_dict())
            issues = out.pop("issues", []) + issues
        else:
            out["value"] = None
        if self.alternatives:
            out["alternatives"] = [it.to_dict() for it in self.alternatives]
        if self.status == "missing":
            out["searched"] = "{}/{}".format(*self.searched)
        if issues:
            out["issues"] = issues
        return out


@dataclass
class ExtractionResult:
    document: str
    schema: str
    fields: dict[str, FieldResult]
    chunks: int
    usage: dict = field(default_factory=dict)

    @property
    def data(self) -> dict[str, Any]:
        """只有值的结果：{字段名: 值}。"""
        return {name: r.value for name, r in self.fields.items()}

    @property
    def needs_review(self) -> list[str]:
        """需要人工看一眼的字段：没通过核对的、没找到的必填字段、过程中出过状况的。"""
        return [name for name, r in self.fields.items() if r.needs_review]

    def to_dict(self) -> dict:
        counts = Counter(r.status for r in self.fields.values())
        return {
            "document": self.document,
            "schema": self.schema,
            "data": self.data,
            "fields": {name: r.to_dict() for name, r in self.fields.items()},
            "summary": {
                "ok": counts["ok"],
                "unverified": counts["unverified"],
                "missing": counts["missing"],
                "needs_review": self.needs_review,
                "chunks": self.chunks,
                **self.usage,
            },
        }


def _value_key(value: Any) -> str:
    return squash(json.dumps(value, ensure_ascii=False, sort_keys=True, default=str))


def _position(item: Item) -> tuple[int, int]:
    """条目在文档里的位置：(片段序号, 证据在片段里的位置)。位置不明的排在所属片段的最后。"""
    if item.chunk is None:
        return (_LAST, _LAST)
    found = [at for part in quote_parts(item.evidence) if (at := item.chunk.source.position(part)) >= 0]
    return (item.chunk.index, max(found) if found else _LAST)  # 引了表头加一行时，以靠后的那一行为准


class _State:
    """一个字段在抽取过程中的状态。"""

    def __init__(self, f: Field, queue: list[int], top_k: int) -> None:
        self.field = f
        self.is_list = f.type == "list"
        self.queue = queue  # 还没看过的片段，按相关度排序（通读的字段按文档顺序）
        self.top_k = len(queue) if f.exhaustive else (f.top_k or top_k)
        self.seen: set[int] = set()
        self.follow: list[int] = []  # 列表字段：下一轮要接着看的相邻片段
        self.items: list[Item] = []
        self.notes: list[str] = []  # 过程中出的状况：回复不成形、输出被截断、列表没有收尾
        self.done = False
        self.rounds = 0
        self._slots: dict[tuple[str, int], int] = {}  # 列表字段：(值, 同一次回复里第几次出现) → items 的下标
        self._fresh: list[Item] = []

    def take(self, siblings: dict[int, list[int]]) -> list[int]:
        """这一轮要看的片段。"""
        if self.follow:
            picked, self.follow = self.follow, []
        else:
            picked = []
            while self.queue and len(picked) < self.top_k:
                i = self.queue.pop(0)
                if i not in self.seen and i not in picked:
                    picked.append(i)
        if self.is_list:  # 同一张大表拆出的其他分段也一起看
            for i in list(picked):
                picked += [j for j in siblings.get(i, ()) if j not in self.seen and j not in picked]
        return picked

    def add(self, items: list[Item], shown: set[int]) -> None:
        """收下一次调用的结果。

        列表字段要去重：同一个片段可能出现在几次调用里，同一件事也可能在文档里写了几处。
        但一次回复里模型列了两遍的相同条目是两条（发票上内容相同的两行），不能合并。
        """
        self.seen |= shown
        if not self.is_list:
            self.items += items
            return
        times: Counter[str] = Counter()
        for item in items:
            key = _value_key(item.value)
            slot = (key, times[key])
            times[key] += 1
            at = self._slots.get(slot)
            if at is None:
                self._slots[slot] = len(self.items)
                self.items.append(item)
                self._fresh.append(item)
            elif item.verified and not self.items[at].verified:
                self.items[at] = item

    def settle(self, n_chunks: int) -> None:
        """一轮结束后决定是否继续。"""
        fresh, self._fresh = self._fresh, []
        if not self.is_list:
            self.done = any(it.verified for it in self.items)
            return
        if not fresh:
            self.done = bool(self.items)
            return
        near = {j for it in fresh if it.chunk for j in (it.chunk.index - 1, it.chunk.index + 1)}
        self.follow = sorted(j for j in near if 0 <= j < n_chunks and j not in self.seen)
        self.done = not self.follow

    def result(self, n_chunks: int) -> FieldResult:
        f = self.field
        common = {"rounds": self.rounds, "searched": (len(self.seen), n_chunks)}
        if not self.items:
            issues = (["必填字段没有找到"] if f.required else []) + self.notes
            return FieldResult(f, "missing", issues=issues, **common)
        if self.is_list:
            items = sorted(self.items, key=_position)  # 按在文档里出现的先后排
            # 过程中出过状况就可能缺项，条目都对也不能算通过
            status = "ok" if all(it.verified for it in items) and not self.notes else "unverified"
            return FieldResult(f, status, items, issues=list(self.notes), **common)
        verified = [it for it in self.items if it.verified]
        if not verified:
            return FieldResult(f, "unverified", [self.items[0]], **common)
        best, others = verified[0], []
        keys = {_value_key(best.value)}
        for it in verified[1:]:
            if _value_key(it.value) not in keys:
                keys.add(_value_key(it.value))
                others.append(it)
        issues = []
        if others:
            values = "、".join(json.dumps(it.value, ensure_ascii=False, default=str) for it in others)
            issues.append(f"别的片段里还读出了不同的值：{values}，见 alternatives")
        return FieldResult(f, "ok", [best], issues=issues, alternatives=others, **common)


@dataclass
class _Batch:
    """一次模型调用：从这些片段里抽这些字段。"""

    fields: list[str]
    chunks: set[int]


@dataclass
class _Output:
    items: dict[str, list[Item]] = field(default_factory=dict)
    unparsed: list[str] = field(default_factory=list)  # 模型的回复始终不成形、没能读出答案的字段
    truncated: list[str] = field(default_factory=list)  # 输出被截断、拆到最小仍然放不下的字段

    def merge(self, other: "_Output") -> None:
        for name, items in other.items.items():
            self.items.setdefault(name, []).extend(items)
        self.unparsed += other.unparsed
        self.truncated += other.truncated


def _interleave(a: list[int], b: list[int]) -> list[int]:
    out: list[int] = []
    seen: set[int] = set()
    for i in range(max(len(a), len(b))):
        for src in (a, b):
            if i < len(src) and src[i] not in seen:
                seen.add(src[i])
                out.append(src[i])
    return out


def _siblings(chunks: list[Chunk]) -> dict[int, list[int]]:
    """共用同一个版面块的片段（大表、长段落拆出的各个分段）。"""
    by_block: dict[int, list[int]] = {}
    for c in chunks:
        for block in c.blocks:
            by_block.setdefault(block.id, []).append(c.index)
    out: dict[int, list[int]] = {}
    for group in by_block.values():
        for i in group:
            mates = out.setdefault(i, [])
            mates += [j for j in group if j != i and j not in mates]
    return {i: mates for i, mates in out.items() if mates}


def _locate_block(chunk: Chunk | None, evidence: str) -> Block | None:
    """证据落在片段里的哪个版面块上（引了几段时以最长的一段为准）。"""
    parts = quote_parts(evidence)
    if chunk is None or not parts:
        return None
    part = max(parts, key=lambda p: len(match_key(p)))
    holders = [b for b in chunk.blocks if b.source.supports(part)]
    if holders:  # 几个块里都有这段文字时（页眉里也印着标题）取最短的那个
        return min(holders, key=lambda b: len(b.source.key))
    # 证据跨了两个块，或者带上了片段里的标记（如"[印章]"）：取最接近的块
    best = max(chunk.blocks, key=lambda b: b.source.similarity(part), default=None)
    return best if best is not None and best.source.similarity(part) >= 0.5 else None


def _merge_retry(previous: list[Item], answer: list[Item]) -> list[Item]:
    """列表字段重问之后：上一次已经通过核对的条目留着，再加上这次回复里多出来的。

    模型重答时可能给出完整的列表，也可能只给改正的那几项，两种情况结果都应该是完整的列表。
    """
    kept = [it for it in previous if it.verified]
    unmatched = [it.value for it in kept]
    extra = []
    for it in answer:
        if it.value in unmatched:
            unmatched.remove(it.value)
        else:
            extra.append(it)
    return kept + extra


def _is_empty(value: Any) -> bool:
    return value is None or value == "" or value == [] or value == {}


class ExtractionAgent:
    def __init__(
        self,
        llm: LLM,
        *,
        chunk_tokens: int = 1000,
        context_tokens: int = 6000,
        top_k: int = 5,
        max_rounds: int = 4,
        retries: int = 1,
        route: bool = True,
        route_tokens: int = 16000,
        max_fields_per_call: int = 8,
        workers: int = 4,
    ) -> None:
        """
        chunk_tokens: 每个切片的大小上限（估算 token）。
        context_tokens: 一次调用里放进去的片段总量上限；整篇文档不超过它时不切片。
        top_k: 每个字段每一轮看几个片段（schema 里可以按字段单独设）。
        max_rounds: 一个字段最多找几轮。
        retries: 没通过核对时带着问题重问几次。
        route: 片段多的时候，是否先让模型按目录给字段挑片段。
        route_tokens: 目录的大小上限，超过就只用关键词检索定位。
        max_fields_per_call: 一次调用最多抽几个字段。
        workers: 并发的调用数。
        """
        self.llm = llm
        self.context_tokens = context_tokens
        self.chunk_tokens = min(chunk_tokens, context_tokens) if context_tokens > 0 else chunk_tokens
        self.top_k = top_k
        self.max_rounds = max_rounds
        self.retries = retries
        self.route = route
        self.route_tokens = route_tokens
        self.max_fields_per_call = max_fields_per_call
        self.workers = workers

    def run(self, doc: Document, schema: Schema) -> ExtractionResult:
        usage = getattr(self.llm, "usage", None)
        before = usage.to_dict() if usage is not None else {}
        chunks = chunk_document(doc, self.chunk_tokens, whole_under=self.context_tokens)
        states = self._states(doc, schema, chunks)
        siblings = _siblings(chunks)
        for round_no in range(1, self.max_rounds + 1):
            wants: dict[str, list[int]] = {}
            for name, state in states.items():
                if state.done:
                    continue
                picked = state.take(siblings)
                if picked:
                    wants[name] = picked
                    state.rounds = round_no
                else:
                    state.done = True
            if not wants:
                break
            batches = self._plan(wants, chunks)
            outputs = self._map(
                lambda b: self._extract(
                    doc, schema, [schema[n] for n in b.fields], [chunks[i] for i in sorted(b.chunks)]
                ),
                batches,
            )
            for batch, output in zip(batches, outputs):
                for name in batch.fields:
                    states[name].add(output.items.get(name, []), batch.chunks)
                    if name in output.unparsed:
                        states[name].notes.append(f"第 {round_no} 轮：模型的回复不是合法的 JSON")
                    if name in output.truncated:
                        states[name].notes.append(
                            f"第 {round_no} 轮：模型的输出被截断，结果可能不全（调大 EXTRACT_LLM_MAX_TOKENS）"
                        )
            for name in wants:
                states[name].settle(len(chunks))
        for state in states.values():
            if state.is_list and state.items and not state.done:
                state.notes.append(
                    "到最大轮数时还在向相邻片段延伸，列表可能不完整（调大 max_rounds，或把字段设为 exhaustive）"
                )
        after = usage.to_dict() if usage is not None else {}
        return ExtractionResult(
            document=doc.name,
            schema=schema.name,
            fields={name: state.result(len(chunks)) for name, state in states.items()},
            chunks=len(chunks),
            usage={k: after[k] - before.get(k, 0) for k in after},
        )

    # -- 定位 ---------------------------------------------------------------

    def _states(self, doc: Document, schema: Schema, chunks: list[Chunk]) -> dict[str, _State]:
        index = ChunkIndex(chunks)
        routed: dict[str, list[int]] = {}
        if self.route and len(chunks) > self.top_k and not all(f.exhaustive for f in schema.fields):
            routed = self._route(doc, schema, chunks)
        states = {}
        for f in schema.fields:
            if f.exhaustive:
                queue = list(range(len(chunks)))
            else:
                queue = _interleave(routed.get(f.name, []), index.rank(f.query))
            states[f.name] = _State(f, queue, self.top_k)
        return states

    def _route(self, doc: Document, schema: Schema, chunks: list[Chunk]) -> dict[str, list[int]]:
        fields = [f for f in schema.fields if not f.exhaustive]
        prompt = routing_prompt(doc, fields, chunks, self.top_k, self.route_tokens, about=schema.description)
        if prompt is None:
            return {}
        try:
            data = parse_json(self.llm.complete(ROUTE_SYSTEM, prompt))
        except (ValueError, OutputTruncated):
            return {}  # 定位只是辅助，回复不成形就只靠关键词检索
        by_id = {c.id: c.index for c in chunks}
        out: dict[str, list[int]] = {}
        for f in fields:
            ids = data.get(f.name)
            if isinstance(ids, str):
                ids = [ids]
            if isinstance(ids, list):
                picked = dict.fromkeys(by_id[str(i)] for i in ids if str(i) in by_id)
                out[f.name] = list(picked)
        return out

    # -- 调用计划 -----------------------------------------------------------

    def _fit(self, wanted: list[int], chunks: list[Chunk]) -> list[list[int]]:
        """把一个字段要看的片段按上下文预算分组。"""
        groups: list[list[int]] = []
        cur: list[int] = []
        used = 0
        for i in wanted:
            if cur and used + chunks[i].tokens > self.context_tokens:
                groups.append(cur)
                cur, used = [], 0
            cur.append(i)
            used += chunks[i].tokens
        if cur:
            groups.append(cur)
        return groups

    def _plan(self, wants: dict[str, list[int]], chunks: list[Chunk]) -> list[_Batch]:
        """把（字段, 片段）合并成尽量少的调用：片段合在一起不超预算的字段放进同一次调用。"""
        batches: list[_Batch] = []
        for name, wanted in wants.items():
            for group in self._fit(wanted, chunks):
                for batch in batches:
                    if name not in batch.fields and len(batch.fields) >= self.max_fields_per_call:
                        continue
                    union = batch.chunks | set(group)
                    if sum(chunks[i].tokens for i in union) <= self.context_tokens:
                        batch.chunks = union
                        if name not in batch.fields:
                            batch.fields.append(name)
                        break
                else:
                    batches.append(_Batch([name], set(group)))
        return batches

    def _map(self, fn: Callable[[T], R], items: list[T]) -> list[R]:
        if self.workers <= 1 or len(items) <= 1:
            return [fn(item) for item in items]
        with ThreadPoolExecutor(max_workers=self.workers) as pool:
            return list(pool.map(fn, items))

    # -- 抽取与核对 ---------------------------------------------------------

    def _extract(
        self, doc: Document, schema: Schema, fields: list[Field], chunks: list[Chunk]
    ) -> _Output:
        """一次抽取。输出被截断时把任务对半拆开重做：先拆字段，只剩一个字段时拆片段。"""
        try:
            return self._extract_once(doc, schema, fields, chunks)
        except OutputTruncated:
            if len(fields) > 1:
                mid = len(fields) // 2
                jobs = [(fields[:mid], chunks), (fields[mid:], chunks)]
            elif len(chunks) > 1:
                mid = len(chunks) // 2
                jobs = [(fields, chunks[:mid]), (fields, chunks[mid:])]
            else:
                return _Output(truncated=[fields[0].name])
        out = _Output()
        for fs, cs in jobs:
            out.merge(self._extract(doc, schema, fs, cs))
        return out

    def _extract_once(
        self, doc: Document, schema: Schema, fields: list[Field], chunks: list[Chunk]
    ) -> _Output:
        shown = {c.id: c for c in chunks}
        out = _Output()
        pending, feedback = fields, ""
        for _ in range(self.retries + 1):
            prompt = extraction_prompt(doc, pending, chunks, feedback, about=schema.description)
            try:
                data = parse_json(self.llm.complete(SYSTEM, prompt))
            except ValueError:
                out.unparsed = [f.name for f in pending]
                feedback = "- 上一次的回复不是合法的 JSON 对象。只输出一个 JSON 对象，不要有其他文字。"
                continue
            out.unparsed = []
            if not any(f.name in data for f in pending):  # 模型多包了一层
                for inner in data.values():
                    if isinstance(inner, dict) and any(f.name in inner for f in pending):
                        data = inner
                        break
            again: list[Field] = []
            notes: list[str] = []
            for f in pending:
                items = self._read(f, data.get(f.name), shown)
                if f.type == "list":
                    items = _merge_retry(out.items.get(f.name, []), items)
                out.items[f.name] = items
                bad = [(n, it) for n, it in enumerate(items, 1) if it.issues]
                if bad:
                    again.append(f)
                    notes += [self._note(f, n, it) for n, it in bad]
            if not again:
                break
            pending, feedback = again, "\n".join(notes)
        return out

    @staticmethod
    def _note(f: Field, n: int, item: Item) -> str:
        where = f"{f.name} 的第 {n} 项" if f.type == "list" else f.name
        answer = json.dumps({"value": item.value, "evidence": item.evidence}, ensure_ascii=False, default=str)
        return f"- {where}：你的回答是 {answer}；问题：{'；'.join(item.issues)}"

    def _read(self, f: Field, raw: Any, shown: dict[str, Chunk]) -> list[Item]:
        if f.type != "list":
            if isinstance(raw, list):  # 单值字段却给了列表：取第一个
                raw = raw[0] if raw else None
            item = self._item(f, raw, shown)
            return [item] if item else []
        if isinstance(raw, dict):
            raw = raw["items"] if isinstance(raw.get("items"), list) else [raw]
        if not isinstance(raw, list):
            return []
        return [item for item in (self._item(f, r, shown) for r in raw) if item]

    def _item(self, f: Field, raw: Any, shown: dict[str, Chunk]) -> Item | None:
        """读出模型给的一个值并核对；模型表示没找到时返回 None。"""
        value, evidence, claimed = raw, "", None
        if isinstance(raw, dict):
            # 模型有时不包 value，直接把子字段平铺在这一项里
            flat = bool(f.fields) and "value" not in raw and any(s.name in raw for s in f.fields)
            if flat or "value" in raw or "evidence" in raw:
                value = raw if flat else raw.get("value")
                quote = raw.get("evidence") or ""
                evidence = "\n".join(map(str, quote)) if isinstance(quote, list) else str(quote)
                claimed = shown.get(str(raw.get("chunk") or ""))
        if _is_empty(value):
            return None
        chunk, issues = locate_evidence(evidence, claimed, list(shown.values()))
        item = Item(value, evidence, chunk, _locate_block(chunk, evidence), issues)
        # 证据本身不成立时，值只整理类型，不再拿去和它对
        quote = evidence if not issues else ""
        if f.type != "list":
            item.value, problems = read_value(f, value, quote, chunk, item.block)
            issues += problems
        elif not f.fields:
            text_field = Field(f.name, verbatim=f.verbatim)
            item.value, problems = read_value(text_field, value, quote, chunk, item.block)
            issues += problems
        elif not isinstance(value, dict):
            issues.append("这一项应该是一个包含各子字段的对象")
        else:
            row: dict[str, Any] = {}
            for sub in f.fields:
                v = value.get(sub.name)
                if _is_empty(v):
                    row[sub.name] = None
                    if sub.required:
                        issues.append(f"缺少 {sub.name}")
                    continue
                row[sub.name], problems = read_value(sub, v, quote, chunk, item.block)
                issues += [f"{sub.name}：{msg}" for msg in problems]
            if all(v is None for v in row.values()):
                return None
            item.value = row
        return item
