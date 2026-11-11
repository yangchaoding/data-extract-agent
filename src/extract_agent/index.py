"""切片的关键词检索（BM25），不依赖向量模型。"""

from __future__ import annotations

import math
from collections import Counter

from .chunker import Chunk
from .textutil import lexical_tokens

_K1 = 1.5
_B = 0.75


class ChunkIndex:
    def __init__(self, chunks: list[Chunk]) -> None:
        # 章节路径里的词权重加倍：标题命中比正文命中更说明问题
        self._docs = [
            Counter(lexical_tokens(c.text) + 2 * lexical_tokens(" ".join(c.path))) for c in chunks
        ]
        self._lengths = [sum(d.values()) for d in self._docs]
        self._avg = (sum(self._lengths) / len(self._lengths)) if self._lengths else 1.0
        self._df: Counter[str] = Counter()
        for doc in self._docs:
            self._df.update(doc.keys())

    def scores(self, query: str) -> list[float]:
        n = len(self._docs)
        terms = set(lexical_tokens(query))
        out = []
        for doc, length in zip(self._docs, self._lengths):
            score = 0.0
            for term in terms:
                tf = doc.get(term, 0)
                if not tf:
                    continue
                df = self._df[term]
                idf = math.log(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * tf * (_K1 + 1) / (tf + _K1 * (1 - _B + _B * length / self._avg))
            out.append(score)
        return out

    def rank(self, query: str) -> list[int]:
        """全部切片按相关度排序：有命中的在前（分高者先），其余按文档顺序跟在后面。"""
        scores = self.scores(query)
        return sorted(range(len(scores)), key=lambda i: (-scores[i], i))
