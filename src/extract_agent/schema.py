"""抽取 schema：要抽哪些字段、各是什么类型。用 YAML 或 JSON 描述。"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

SCALAR_TYPES = {"string", "number", "integer", "date", "boolean", "enum"}
TYPES = SCALAR_TYPES | {"list"}
_ATTRS = {
    "name", "type", "title", "description", "required", "hints", "options", "unit", "fields",
    "verbatim", "top_k", "exhaustive",
}


@dataclass
class Field:
    name: str
    type: str = "string"
    title: str = ""  # 中文名
    description: str = ""
    required: bool = False
    hints: list[str] = field(default_factory=list)  # 原文里可能出现的叫法，用于定位
    options: list[str] = field(default_factory=list)  # enum 的可选项
    unit: str = ""  # number / integer 的单位，如 元、万元、%；原文用别的单位时由程序换算成它
    fields: list["Field"] = field(default_factory=list)  # list 每一项的子字段；为空表示字符串列表
    verbatim: bool = True  # string 的值是否必须是原文里的文字（概括性的字段设为 false）
    top_k: int | None = None  # 定位时每一轮看几个片段，默认用 agent 的设置
    exhaustive: bool = False  # 通读全文找这个字段（散落在各处的列表用），而不是只看最相关的片段

    @property
    def query(self) -> str:
        """定位用的检索词。"""
        parts = [self.title, *self.hints, self.description]
        parts += [sub.title or sub.name for sub in self.fields]
        return " ".join(p for p in parts if p) or self.name

    def describe(self, indent: str = "") -> str:
        """写进提示词里的字段说明。"""
        head = f"{indent}- {self.name}"
        if self.title:
            head += f"（{self.title}）"
        head += f"：{self.type}"
        if self.type == "enum":
            head += "，可选项：" + " / ".join(self.options)
        if self.unit:
            head += f"，单位：{self.unit}"
        if self.required:
            head += "，必填"
        if self.type in ("string", "list") and not self.fields and not self.verbatim:
            head += "，可以概括"
        if self.description:
            head += f"。{self.description}"
        if self.hints:
            head += f"（原文中可能写作：{'、'.join(self.hints)}）"
        lines = [head]
        if self.type == "list":
            if self.fields:
                lines.append(f"{indent}  每一项是一个对象，包含：")
                lines += [sub.describe(indent + "    ") for sub in self.fields]
            else:
                lines.append(f"{indent}  每一项是一个字符串")
        return "\n".join(lines)


@dataclass
class Schema:
    name: str
    fields: list[Field]
    description: str = ""

    def __getitem__(self, name: str) -> Field:
        for f in self.fields:
            if f.name == name:
                return f
        raise KeyError(name)


def _field(raw: Any, where: str, nested: bool = False) -> Field:
    if not isinstance(raw, dict) or not raw.get("name"):
        raise ValueError(f"{where}: 每个字段都要是带 name 的对象")
    name = str(raw["name"])
    where = f"{where}.{name}"
    unknown = set(raw) - _ATTRS
    if unknown:
        raise ValueError(f"{where}: 不认识的属性 {sorted(unknown)}")
    ftype = str(raw.get("type", "string"))
    if ftype not in TYPES:
        raise ValueError(f"{where}: type 必须是 {sorted(TYPES)} 之一，现在是 {ftype!r}")
    if ftype == "list" and nested:
        raise ValueError(f"{where}: list 的子字段不能再是 list")
    options = [str(o) for o in raw.get("options") or []]
    if ftype == "enum" and not options:
        raise ValueError(f"{where}: enum 类型需要 options")
    unit = str(raw.get("unit") or "")
    if unit and ftype not in ("number", "integer"):
        raise ValueError(f"{where}: 只有 number 和 integer 类型可以有 unit")
    subs = [_field(sub, where, nested=True) for sub in raw.get("fields") or []]
    if subs and ftype != "list":
        raise ValueError(f"{where}: 只有 list 类型可以有子字段")
    sub_names = [s.name for s in subs]
    if len(set(sub_names)) != len(sub_names):
        raise ValueError(f"{where}: 子字段名重复")
    return Field(
        name=name,
        type=ftype,
        title=str(raw.get("title") or ""),
        description=str(raw.get("description") or ""),
        required=bool(raw.get("required", False)),
        hints=[str(h) for h in raw.get("hints") or []],
        options=options,
        unit=unit,
        fields=subs,
        verbatim=bool(raw.get("verbatim", True)),
        top_k=int(raw["top_k"]) if raw.get("top_k") else None,
        exhaustive=bool(raw.get("exhaustive", False)),
    )


def schema_from_dict(raw: Any) -> Schema:
    if not isinstance(raw, dict) or not isinstance(raw.get("fields"), list) or not raw["fields"]:
        raise ValueError("schema 需要一个非空的 fields 列表")
    fields = [_field(f, "fields") for f in raw["fields"]]
    names = [f.name for f in fields]
    dup = sorted({n for n in names if names.count(n) > 1})
    if dup:
        raise ValueError(f"字段名重复: {dup}")
    return Schema(
        name=str(raw.get("name") or "schema"),
        fields=fields,
        description=str(raw.get("description") or ""),
    )


def load_schema(path: str | Path) -> Schema:
    p = Path(path)
    text = p.read_text(encoding="utf-8-sig")
    if p.suffix.lower() == ".json":
        return schema_from_dict(json.loads(text))
    import yaml

    return schema_from_dict(yaml.safe_load(text))
