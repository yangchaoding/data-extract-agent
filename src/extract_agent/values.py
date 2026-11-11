"""把模型给出的值整理成字段的类型，并核对"证据出自原文、值出自证据"。"""

from __future__ import annotations

import math
import re
import unicodedata
from decimal import Decimal
from typing import Any, Sequence

from .chunker import Chunk
from .document import Block
from .schema import Field
from .textutil import (
    SourceText,
    match_key,
    normalize_chars,
    one_line,
    quote_parts,
    squash,
    strip_thousands,
)

# ---------------------------------------------------------------------------
# 中文数字
# ---------------------------------------------------------------------------

_CN_DIGITS = {c: i for i, c in enumerate("〇一二三四五六七八九")}
_CN_DIGITS.update(dict.fromkeys("零○Oo0", 0))  # 年份里的"〇"常被写成或识别成圆圈、字母 O、数字 0
_CN_RUN_RE = re.compile("[〇○零一二三四五六七八九十][〇○零一二三四五六七八九十Oo0]*")


def _cn_run(run: str) -> str:
    if "十" in run:
        left, _, right = run.partition("十")
        if len(left) > 1 or len(right) > 1 or "十" in right:
            return run
        return str((_CN_DIGITS[left] if left else 1) * 10 + (_CN_DIGITS[right] if right else 0))
    return "".join(str(_CN_DIGITS[c]) for c in run)


def arabic_numerals(text: str) -> str:
    """把中文小写数字换成阿拉伯数字：二〇二四年三月十五日 → 2024年3月15日。"""
    return _CN_RUN_RE.sub(lambda m: _cn_run(m.group()), unicodedata.normalize("NFKC", text))


_CN_UNITS = {"十": 10, "拾": 10, "百": 100, "佰": 100, "千": 1000, "仟": 1000}
_CN_VALUES = {c: i for i, c in enumerate("零一二三四五六七八九")}
_CN_VALUES.update({c: i for i, c in enumerate("零壹贰叁肆伍陆柒捌玖")})
_CN_VALUES.update({"〇": 0, "两": 2})
_CN_AMOUNT_RE = re.compile("[零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+")
_FRACTION_WORD_RE = re.compile("[百千]分之")  # 百分之六：去掉之后按 6 读


def parse_cn_number(run: str) -> int | None:
    """带单位的中文数字：一百二十 → 120，壹佰贰拾捌万 → 1280000。

    只处理写了"十百千万亿"的写法；逐位写的（如年份二〇二四）返回 None，由 arabic_numerals 处理。
    """
    has_value = any(c in _CN_VALUES or c in _CN_UNITS for c in run)
    has_unit = any(c in _CN_UNITS or c in "万亿" for c in run)
    if not (has_value and has_unit):
        return None
    total = section = digit = 0
    for c in run:
        if c in _CN_UNITS:
            section += (digit or 1) * _CN_UNITS[c]
            digit = 0
        elif c == "万":
            total += (section + digit) * 10**4
            section = digit = 0
        elif c == "亿":
            total = (total + section + digit) * 10**8
            section = digit = 0
        else:
            digit = _CN_VALUES[c]
    return total + section + digit


# ---------------------------------------------------------------------------
# 数字和单位
# ---------------------------------------------------------------------------

_SCALE_WORDS: list[tuple[re.Pattern[str], int]] = [
    (re.compile(pattern, re.I), scale)
    for pattern, scale in [
        ("万亿", 10**12), ("千亿", 10**11), ("百亿", 10**10), ("十亿", 10**9), ("亿", 10**8),
        ("千万", 10**7), ("百万", 10**6), ("十万", 10**5), ("万", 10**4),
        # "千"只在货币和股份前面算数量级：千克、千米、千瓦本身就是单位
        ("千(?=美元|港元|港币|欧元|日元|英镑|元|股|份)", 10**3),
        ("billion", 10**9), ("million", 10**6), ("thousand", 10**3),
    ]
]
_CURRENCY_PREFIX_RE = re.compile(r"(?:人民币|rmb|cny)\s*", re.I)
_BASE_UNIT_RE = re.compile("美元|港元|港币|欧元|日元|英镑|元|股|份|%|‰")


def _unit_text(unit: str) -> str:
    """单位的规范写法：全半角统一，去掉前面的币种（人民币万元 → 万元）。"""
    u = unicodedata.normalize("NFKC", unit).strip()
    m = _CURRENCY_PREFIX_RE.match(u)
    return u[m.end() :] if m and m.end() < len(u) else u


def unit_scale(unit: str) -> int | None:
    """单位里的数量级：万元 → 10**4，百万美元 → 10**6，元、%、千克 → 1。

    unit 不像单位（空的，或以括号等符号开头）时返回 None。
    """
    u = _unit_text(unit)
    for pattern, scale in _SCALE_WORDS:
        if pattern.match(u):
            return scale
    return 1 if u and (u[0].isalpha() or u[0] in "%‰") else None


def _base_unit(unit: str) -> str:
    """去掉数量级之后的单位：万元 → 元，百万美元 → 美元。"""
    u = _unit_text(unit)
    for pattern, _ in _SCALE_WORDS:
        m = pattern.match(u)
        if m:
            return u[m.end() :]
    return u


def _attached_scale(tail: str) -> int | None:
    """原文里紧跟在一个数后面的单位的数量级：万元 → 10**4，元、股、% → 1。

    tail 是这个数后面的文字。后面不是单位（表格竖线、标点、别的词）时返回 None：
    原文里数字后面什么字都可能出现，所以只认数量级词和几种常见的基本单位。
    """
    t = _unit_text(tail.lstrip(" ("))
    scale = unit_scale(t)
    if scale and scale > 1:
        return scale
    return 1 if _BASE_UNIT_RE.match(t) else None


_NUM_RE = re.compile(r"[-+]?\d+(?:\.\d+)?")
_PLAIN_NUMBER_RE = re.compile(r"\d+(?:\.\d+)?")


def read_number(raw: Any) -> tuple[Decimal, int | None] | None:
    """读出模型给的一个数，返回 (写出来的数, 它带的单位的数量级)；读不出来返回 None。

    没带单位时数量级为 None：1234.56 → (1234.56, None)，"1234.56万元" → (1234.56, 10**4)，
    "56%" → (56, 1)。用十进制表示，换算时不会有浮点误差。
    """
    if isinstance(raw, bool):
        return None
    if isinstance(raw, int):
        return Decimal(raw), None
    if isinstance(raw, float):
        return (Decimal(str(raw)), None) if math.isfinite(raw) else None
    s = strip_thousands(unicodedata.normalize("NFKC", str(raw))).strip()
    s = _FRACTION_WORD_RE.sub("", s)
    m = _NUM_RE.search(s)
    if m:
        num = Decimal(m.group())
        if s.startswith("(") and ")" in s[m.end() :]:  # 会计写法：括号表示负数
            num = -abs(num)
        return num, unit_scale(s[m.end() :].lstrip(" ()"))  # 单位可能写在括号里：1,234.56（万元）
    cn = _CN_AMOUNT_RE.search(s)  # 没有阿拉伯数字：按中文数字读
    n = parse_cn_number(cn.group()) if cn else None
    if cn and n is not None:
        scale = unit_scale(s[cn.end() :])
        if scale is None and re.search("[万亿]", cn.group()):
            scale = 1  # 壹佰贰拾捌万：数量级已经算进数里了，这是按基本单位写的
        return Decimal(n), scale
    converted = arabic_numerals(s)  # 逐位写的：二〇二四
    m = _NUM_RE.search(converted)
    return (Decimal(m.group()), unit_scale(converted[m.end() :])) if m else None


def _plain(num: Decimal) -> int | float:
    return int(num) if num == num.to_integral_value() else float(num)


def _occurrences(text: str) -> list[tuple[Decimal, int | None]]:
    """text 里写着的每一个数（不带正负号），和紧跟在它后面的单位的数量级（后面不是单位时为 None）。

    阿拉伯数字、逐位写的中文数字（二〇二四）、带单位的中文数字（壹佰贰拾捌万，数量级已经算进数里）。
    """
    plain = _FRACTION_WORD_RE.sub("", strip_thousands(normalize_chars(text)))
    found: list[tuple[Decimal, int | None]] = []
    for source in (plain, arabic_numerals(plain)):
        for m in _PLAIN_NUMBER_RE.finditer(source):
            found.append((Decimal(m.group()), _attached_scale(source[m.end() : m.end() + 10])))
    for m in _CN_AMOUNT_RE.finditer(plain):
        n = parse_cn_number(m.group())
        if n is not None:
            folded = re.search("[万亿]", m.group()) is not None
            found.append((Decimal(n), 1 if folded else _attached_scale(plain[m.end() : m.end() + 10])))
    return found


# 对单位的明确说明：单位：万元、金额单位为人民币千元、金额（百万元）、每股收益(元/股)
_UNIT_WORD = r"(?:[十百千]?[万亿]|千)?(?:美元|港元|元|股|份)(?:/[^\s()|,;]{1,4})?"
_UNIT_NOTE_RE = re.compile(
    r"单位\s*[:为]?\s*(?:人民币|rmb)?\s*(" + _UNIT_WORD + r")|\(\s*(?:人民币|rmb)?\s*(" + _UNIT_WORD + r")\s*\)",
    re.I,
)
# 题注、单位行里提到的带数量级的单位（前面不是数字）：下表金额均以万元列示
_NUMERAL_CHARS = "0-9余多几零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"
_UNIT_MENTION_RE = re.compile(f"(?<![{_NUMERAL_CHARS}])((?:[十百千]?[万亿]|千)(?:美元|港元|元|股|份))")


def _noted_scales(text: str, base: str, mentions: bool = False) -> set[int]:
    """text 里对单位的说明给出的数量级；只看和 base 同类的单位（base 是"元"时，"万元"算，"万股"不算）。

    mentions=True 时，没有写成"单位：…"、只是提到的带数量级的单位也算（用于题注和单位行）。
    """
    text = unicodedata.normalize("NFKC", text)
    notes = [m.group(1) or m.group(2) for m in _UNIT_NOTE_RE.finditer(text)]
    if mentions:
        notes += _UNIT_MENTION_RE.findall(text)
    return {unit_scale(note) or 1 for note in notes if _base_unit(note) == base}


def _unit_notes(field: Field, evidence: str, block: Block | None) -> set[int]:
    """原文对这个数的单位的说明，由近到远：evidence 里的；所在的块里的，和紧挨在块前面的题注、单位行里的。

    字段没有指定单位时不看这些说明：不知道它要的是金额还是比例，表格上的"单位：万元"未必是说它。
    """
    if not field.unit:
        return set()
    base = _base_unit(field.unit)
    notes = _noted_scales(evidence, base)
    if notes or block is None:
        return notes
    return _noted_scales(block.text, base) | _noted_scales("\n".join(block.lead), base, mentions=True)


def _mentions_scale(scale: int, text: str) -> bool:
    """text 里有没有出现过这个数量级的词（万、亿、百万…）。"""
    text = unicodedata.normalize("NFKC", text)
    return any(pattern.search(text) for pattern, s in _SCALE_WORDS if s == scale)


def _settle_scale(
    field: Field, number: Decimal, stated: int | None, evidence: str, chunk: Chunk | None, block: Block | None
) -> tuple[int | None, list[str]]:
    """定出模型给的这个数是按什么数量级写的，返回 (数量级, 问题)。None 表示"就是字段的单位"。

    number 是模型写出来的数（不带正负号），stated 是它带的单位的数量级。
    原文给一个数的单位有两种写法：紧跟在数字后面（5,000万元），或者另外说明（表头里的"金额(万元)"、
    表格上方的"单位：万元"）。模型写的单位和原文的对不上，或者在原文里找不到依据，都算问题。
    """
    occurrences = _occurrences(evidence)
    attached = {scale for n, scale in occurrences if n == number}
    notes = _unit_notes(field, evidence, block)
    if not attached:
        # evidence 里没有这个数。模型可能自己做了换算：换算得对就接受，否则让它照原文重写
        if stated is None:  # 它可能是按基本单位写的，也可能是按字段的单位写的
            readings = [(number, 1), (number * (unit_scale(field.unit) or 1), None)]
        else:
            readings = [(number * stated, stated)]
        for n, unit in occurrences:
            for s in notes if unit is None else {unit}:
                for full, scale in readings:
                    if n * s == full:
                        return scale, []
        return stated, [
            f"evidence 里没有 {_plain(number)} 这个数。数字要照原文写，后面带上原文的单位"
            "（例如 1234.56万元），换算由程序来做，不要自己乘除"
        ]
    known = (attached - {None}) or notes
    if stated is None:
        if len(known) > 1:
            return None, ["原文里注明的单位不止一种，这个数却没有带单位。请在数字后面写上它在原文里的单位"]
        return next(iter(known), None), []
    if known and stated not in known:
        expected = next(iter(known)) if len(known) == 1 else stated
        return expected, ["这个数的单位和原文注明的不一样。数字后面的单位要照原文写"]
    if not known and stated > 1:
        shown = evidence + "\n" + (chunk.text if chunk is not None else "")
        if not _mentions_scale(stated, shown):
            return stated, ["原文里找不到这个单位的依据。单位要照原文写，原文没有写单位的只写数字"]
    return stated, []


def _marked_negative(number: Decimal, evidence: str) -> bool:
    """evidence 里这个数每次出现都明确标着是负的：前面紧挨着负号，或者在表格单元格里用括号括着（会计写法）。"""
    text = strip_thousands(normalize_chars(evidence))
    marks = []
    for m in _PLAIN_NUMBER_RE.finditer(text):
        if Decimal(m.group()) != number:
            continue
        before, after = text[: m.start()], text[m.end() :]
        minus = re.search(r"(?:^|[\s|(:])-$", before) is not None
        paren = bool(re.search(r"\|\s*\($", before) and re.match(r"\)\s*(?:\||$)", after))
        marks.append(minus or paren)
    return bool(marks) and all(marks)


# ---------------------------------------------------------------------------
# 日期
# ---------------------------------------------------------------------------

_YMD_RE = re.compile(r"(?<!\d)(\d{4})\s*[-/.年]\s*(\d{1,2})(?:\s*[-/.月]\s*(\d{1,2}))?(?!\d)")
_COMPACT_DATE_RE = re.compile(r"(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)")
_DMY_RE = re.compile(r"(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})(?!\d)")  # 18/03/2024 或 03/18/2024
_MONTH_DAY_RE = re.compile(r"(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]")
_ISO_DATE_RE = re.compile(r"\d{4}(?:-\d{2}){0,2}")
_EN_MONTHS = ("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")


def _format_date(year: str, month: str, day: str | None) -> str | None:
    y, m = int(year), int(month)
    if not 1 <= m <= 12:
        return None
    if day is None:
        return f"{y:04d}-{m:02d}"
    d = int(day)
    if not 1 <= d <= 31:
        return None
    return f"{y:04d}-{m:02d}-{d:02d}"


def parse_date(raw: Any) -> str | None:
    """归一化为 YYYY-MM-DD；只有年月时 YYYY-MM；只有年份时 YYYY。"""
    s = arabic_numerals(str(raw)).strip()
    if re.fullmatch(r"\d{4}年?", s):
        return s[:4]
    m = _COMPACT_DATE_RE.fullmatch(s) or _YMD_RE.search(s)
    if m:
        return _format_date(*m.groups())
    m = _DMY_RE.fullmatch(s)
    if m:  # 日和月谁先谁后，只有其中一个大于 12 时才分得清
        a, b, year = m.groups()
        if int(a) > 12:
            return _format_date(year, b, a)
        if int(b) > 12:
            return _format_date(year, a, b)
    return None


def date_in(value: str, evidence: str) -> bool:
    """evidence 里是否写着 value 这个日期（value 是 parse_date 的结果）。

    年、月、日必须属于同一个日期，不能是从两个日期里各取一部分凑出来的。
    """
    if not _ISO_DATE_RE.fullmatch(value):
        return False
    text = arabic_numerals(evidence)
    full = [m for regex in (_YMD_RE, _COMPACT_DATE_RE) for m in regex.finditer(text)]
    found = {_format_date(*m.groups()) for m in full}
    for m in _DMY_RE.finditer(text):
        a, b, year = m.groups()
        found |= {_format_date(year, a, b), _format_date(year, b, a)}
    if any(d and (d == value or d.startswith(value + "-")) for d in found):
        return True
    # 年份写在别处的日期：自2024年4月1日起至12月31日止、24年3月18日、March 18, 2024
    year, *rest = value.split("-")
    if not (re.search(rf"(?<!\d){year}(?!\d)", text) or re.search(rf"(?<!\d){year[2:]}\s*年", text)):
        return False
    if not rest:
        return True
    for m in full:  # 已经是完整日期的部分不能再拆开用
        text = text.replace(m.group(), " ")
    month = int(rest[0])
    named = _EN_MONTHS[month - 1] in text.lower()
    if len(rest) == 1:
        return named or bool(re.search(rf"(?<!\d)0?{month}\s*月", text))
    day = int(rest[1])
    if any((int(m.group(1)), int(m.group(2))) == (month, day) for m in _MONTH_DAY_RE.finditer(text)):
        return True
    return named and bool(re.search(rf"(?<!\d)0?{day}(?!\d)", text))


# ---------------------------------------------------------------------------
# 核对
# ---------------------------------------------------------------------------


def locate_evidence(
    evidence: str, claimed: Chunk | None, shown: Sequence[Chunk]
) -> tuple[Chunk | None, list[str]]:
    """核对 evidence 是给模型看过的片段里的原文，返回 (它所在的片段, 问题)。

    evidence 可以是摘抄的几段（换行或省略号隔开），每一段都必须逐字出现在某个片段里。
    模型偶尔会写错片段 id，所以先在它声称的片段里找，找不到再到其余片段里找。
    """
    parts = quote_parts(evidence)
    if not parts:
        return claimed, ["没有给出 evidence"]
    candidates = ([claimed] if claimed is not None else []) + [c for c in shown if c is not claimed]
    home: Chunk | None = None
    longest = -1
    for part in parts:
        chunk = next((c for c in candidates if c.source.supports(part)), None)
        if chunk is None:
            nearest = max(candidates, key=lambda c: c.source.similarity(part), default=None)
            if nearest is not None and nearest.source.similarity(part) < 0.5:
                nearest = None
            return nearest or claimed, [
                f"evidence 必须逐字摘抄原文，一个字、一个数都不能改；片段里找不到这一段：{one_line(part, 60)}"
            ]
        if len(match_key(part)) > longest:
            home, longest = chunk, len(match_key(part))
    return home, []


_TRUE = {"true", "yes", "y", "1", "是", "有", "对"}
_FALSE = {"false", "no", "n", "0", "否", "无", "没有", "不是"}


def _read_number_value(
    field: Field, raw: Any, evidence: str, chunk: Chunk | None, block: Block | None
) -> tuple[Any, list[str]]:
    """整理一个数并和 evidence 核对。

    模型照原文写出数字和单位，换算成字段的单位由这里来做。
    """
    num = read_number(raw)
    if num is None:
        return raw, [f"{raw!r} 不是数字"]
    written, scale = num
    issues: list[str] = []
    if evidence.strip():
        scale, issues = _settle_scale(field, abs(written), scale, evidence, chunk, block)
        if written > 0 and _marked_negative(written, evidence):
            issues.append("evidence 里这个数是负数（带负号，或者用括号括着），值也要写成负数")
    # 数量级为 None：原文和模型都没有给单位，认为它已经是字段的单位
    target = unit_scale(field.unit) or 1
    value = _plain(written if scale is None else written * scale / target)
    if field.type == "integer" and not isinstance(value, int):
        issues.insert(0, f"{value} 不是整数")
    return value, issues


def _check_verbatim(value: str, evidence: str, chunk: Chunk | None) -> list[str]:
    """照抄型的字符串：值必须是片段里的文字，并且 evidence 引的确实是它所在的地方。"""
    source = chunk.source if chunk is not None else SourceText(evidence)
    if not source.contains_value(value):
        return [f"值 {value!r} 不是原文里的文字，string 的值要照抄原文"]
    if SourceText(evidence).contains_value(value) or SourceText(value).supports(evidence):
        return []
    return [f"evidence 里没有值 {value!r}，evidence 要摘抄这个值所在的那句原文"]


def read_value(
    field: Field, raw: Any, evidence: str = "", chunk: Chunk | None = None, block: Block | None = None
) -> tuple[Any, list[str]]:
    """把模型给出的值整理成字段的类型，并核对它确实能从 evidence 里读出来。返回 (值, 问题)。

    能核对的：数字要写在 evidence 里，日期要是 evidence 里的某个日期，照抄型的字符串要是原文里的文字。
    枚举、布尔和允许概括的字符串是模型的判断，程序核对不了，只能保证它引的 evidence 是真的。
    evidence 为空时只整理类型（没有证据这件事由 locate_evidence 报告）。
    chunk、block 是 evidence 所在的片段和版面块。
    """
    t = field.type
    has_evidence = bool(evidence.strip())
    if t in ("number", "integer"):
        return _read_number_value(field, raw, evidence, chunk, block)
    if t == "date":
        date = parse_date(raw)
        if date is None:
            return raw, [f"{raw!r} 不是日期，日期要写成 YYYY-MM-DD"]
        if has_evidence and not date_in(date, evidence):
            return date, [f"日期 {date} 和 evidence 里的日期对不上"]
        return date, []
    if t == "boolean":
        if isinstance(raw, bool):
            return raw, []
        key = squash(str(raw))
        if key in _TRUE:
            return True, []
        if key in _FALSE:
            return False, []
        return raw, [f"{raw!r} 不是 true 或 false"]
    if t == "enum":
        key = squash(str(raw))
        for option in field.options:
            if squash(option) == key:
                return option, []
        return raw, [f"{raw!r} 不在可选项 {field.options} 中"]
    if isinstance(raw, (dict, list)):
        return raw, ["应该是一个字符串"]
    value = str(raw).strip()
    if has_evidence and field.verbatim:
        return value, _check_verbatim(value, evidence, chunk)
    return value, []
