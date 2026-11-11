"""文本工具：token 估算、归一化、原文比对、检索分词。"""

from __future__ import annotations

import re
import unicodedata
from functools import cached_property


def _chars(*codepoints: int) -> str:
    """按码位写出字符：不可见的、长得一样的字符不直接写在源码里。"""
    return "".join(map(chr, codepoints))


# 中日韩文字的码位范围：CJK 扩展 A、CJK 基本区、兼容表意文字、平假名和片假名、谚文
_CJK_RANGES = ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0x3040, 0x30FF), (0xAC00, 0xD7AF))
_CJK_CLASS = "".join(f"{chr(lo)}-{chr(hi)}" for lo, hi in _CJK_RANGES)  # 用在正则的字符类里
_CJK_RE = re.compile(f"[{_CJK_CLASS}]")
_DIGIT_RE = re.compile(r"[0-9]")
_LATIN_RE = re.compile(r"[A-Za-z]")
_SPACE_RE = re.compile(r"\s")


def estimate_tokens(text: str) -> int:
    """粗略估算 token 数（偏保守）。

    各家模型的分词器差别很大，这里只用于切片大小和上下文预算：
    CJK 字符按 1 token，数字 0.5，拉丁字母 0.3，其余符号 0.6。
    """
    if not text:
        return 0
    cjk = len(_CJK_RE.findall(text))
    digits = len(_DIGIT_RE.findall(text))
    latin = len(_LATIN_RE.findall(text))
    spaces = len(_SPACE_RE.findall(text))
    other = len(text) - cjk - digits - latin - spaces
    return int(cjk + digits * 0.5 + latin * 0.3 + other * 0.6 + spaces * 0.1) + 1


_TRANS = str.maketrans(
    {
        # 弯引号、低位引号、直角引号 → 直引号
        **dict.fromkeys(_chars(0x201C, 0x201D, 0x201E, 0x300C, 0x300D, 0x300E, 0x300F), '"'),
        **dict.fromkeys(_chars(0x2018, 0x2019), "'"),
        # 破折号、连接号、减号、连字符、不换行连字符、横线 → "-"
        **dict.fromkeys(_chars(0x2014, 0x2013, 0x2212, 0x2010, 0x2011, 0x2015), "-"),
    }
)
# 空白、表格竖线、零宽字符（U+200B 至 U+200D、U+FEFF）、软连字符（U+00AD）
_DROP_RE = re.compile(r"[\s|" + _chars(0x200B, 0x200C, 0x200D, 0xFEFF, 0x00AD) + "]+")


def normalize_chars(text: str) -> str:
    """全半角统一，引号和横线统一；不动空白和大小写。"""
    return unicodedata.normalize("NFKC", text).translate(_TRANS)


def squash(text: str) -> str:
    """判断两段文字是否相同时用的归一化：全半角统一、引号横线统一、去掉空白和表格竖线、转小写。"""
    return _DROP_RE.sub("", normalize_chars(text)).lower()


_THOUSANDS_RE = re.compile(r"(?<=\d),(?=\d{3}(?!\d))")


def strip_thousands(text: str) -> str:
    """去掉数字里的千分位逗号：1,234,567.89 → 1234567.89。"""
    return _THOUSANDS_RE.sub("", text)


_NUMBER_SEPARATORS = ".,:/-"


def match_key(text: str, glue_digit_groups: bool = False) -> str:
    """判断"是不是原文"时用的形式。

    模型摘抄原文时常常改动空白、换行、全半角、标点和千分位，这些不影响内容，比较时都忽略；
    文字和数字一个都不能动。为了让数字保持完整：
      - 两个数字之间紧挨着的小数点等分隔符保留，1.5 不会变成 15；
      - 两个数字之间隔着别的东西（空格、表格竖线、其他标点）时留一个空格作为边界，
        相邻单元格里的 30 和 384 不会粘成 30384。
    glue_digit_groups=True 时，只隔着空格的数字连在一起：用来认出分组书写的号码
    （6222 0212 3456 → 622202123456）。
    """
    s = normalize_chars(text).lower()
    out: list[str] = []
    i, n = 0, len(s)
    while i < n:
        if s[i].isalnum():
            out.append(s[i])
            i += 1
            continue
        j = i
        while j < n and not s[j].isalnum():
            j += 1
        if out and j < n and out[-1].isdigit() and s[j].isdigit():  # 这一段夹在两个数字之间
            run = s[i:j]
            if len(run) == 1 and run in _NUMBER_SEPARATORS:
                out.append(run)
            elif not (glue_digit_groups and not run.strip(" \t")):
                out.append(" ")
        i = j
    return strip_thousands("".join(out))


def _find(piece: str, key: str) -> int:
    """piece 在 key 里的位置，找不到返回 -1。数字必须完整：不能从一个数的中间开始或结束。"""
    if not piece or piece not in key:
        return -1
    pattern = re.escape(piece)
    if piece[0].isdigit():
        pattern = r"(?<![\d.,])" + pattern  # key 里的 . 和 , 只出现在两个数字之间
    if piece[-1].isdigit():
        pattern += r"(?![\d.,])"
    m = re.search(pattern, key)
    return m.start() if m else -1


_BREAK_RE = re.compile(r"\n|\.{3,}|" + _chars(0x2026) + "+")  # 换行、省略号


def quote_parts(quote: str) -> list[str]:
    """引文按换行和省略号分成的各段；没有实际内容的段（比如表格的分隔线）去掉。"""
    return [p.strip() for p in _BREAK_RE.split(quote) if match_key(p)]


class SourceText:
    """一段原文，以及比对时用的缓存。"""

    def __init__(self, text: str) -> None:
        self.text = text

    @cached_property
    def key(self) -> str:
        return match_key(self.text)

    @cached_property
    def _glued_key(self) -> str:
        return match_key(self.text, glue_digit_groups=True)

    @cached_property
    def _trigrams(self) -> frozenset[str]:
        s = self.key
        return frozenset(s[i : i + 3] for i in range(len(s) - 2))

    def supports(self, quote: str) -> bool:
        """quote 是不是这段原文里的文字。

        忽略空白、标点、全半角、大小写和千分位的差别，除此之外必须逐字相同。
        quote 可以是摘抄的几段（用换行或省略号隔开，比如表头加上其中一行），每一段都必须是原文。
        """
        parts = quote_parts(quote)
        return bool(parts) and all(_find(match_key(p), self.key) >= 0 for p in parts)

    def contains_value(self, value: str) -> bool:
        """value 是不是原文里的文字。比 supports 多容忍一点：原文里分组书写的号码可以连着写。"""
        if self.supports(value):
            return True
        return _find(match_key(value, glue_digit_groups=True), self._glued_key) >= 0

    def position(self, text: str) -> int:
        """text 在原文里的位置（按比较用的形式计）；找不到返回 -1。"""
        return _find(match_key(text), self.key)

    def similarity(self, quote: str) -> float:
        """quote 和原文有多像，0~1：quote 的字符三元组有多大比例出现在原文里。

        只用来定位和给提示，不用来判断真伪（判断真伪用 supports）。
        """
        q = match_key(quote)
        if not q:
            return 0.0
        if len(q) < 3:
            return 1.0 if q in self.key else 0.0
        grams = [q[i : i + 3] for i in range(len(q) - 2)]
        return sum(1 for g in grams if g in self._trigrams) / len(grams)


_TOKEN_RE = re.compile(f"[{_CJK_CLASS}]+|[a-z0-9]+(?:[._-][a-z0-9]+)*")


def lexical_tokens(text: str) -> list[str]:
    """检索用分词：CJK 取相邻二元组（单字成词时取单字），字母数字取整词。"""
    out: list[str] = []
    for m in _TOKEN_RE.finditer(unicodedata.normalize("NFKC", text).lower()):
        tok = m.group()
        if _CJK_RE.match(tok):
            if len(tok) == 1:
                out.append(tok)
            else:
                out.extend(tok[i : i + 2] for i in range(len(tok) - 1))
        else:
            out.append(tok)
    return out


def one_line(text: str, limit: int | None = None) -> str:
    """压成一行；超过 limit 时截断并加省略号。"""
    t = re.sub(r"\s+", " ", text).strip()
    if limit is not None and len(t) > limit:
        t = t[: max(limit - 1, 1)] + _chars(0x2026)
    return t
