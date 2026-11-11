"""大模型调用：OpenAI 兼容接口（DeepSeek、通义千问、智谱、Moonshot、vLLM、Ollama 等都提供这种接口）。"""

from __future__ import annotations

import json
import os
import re
import threading
from dataclasses import dataclass, field
from typing import Any, Protocol
from urllib.parse import urlsplit


# 开发环境默认配置；命令行参数和环境变量仍可覆盖。
# 这里只填 /v1，客户端会自动追加 /chat/completions。
DEFAULT_LLM_BASE_URL = "http://10.10.10.123:9503/v1"
DEFAULT_LLM_MODEL = "cb-llm"  # 使用 /v1/models 返回的 id，而非底层模型名称。
DEFAULT_LLM_API_KEY = "EMPTY"  # 服务无需密钥，这是客户端要求的占位值。


class LLM(Protocol):
    def complete(self, system: str, user: str) -> str:
        """给定系统提示词和用户消息，返回模型的文本回复。"""
        ...


class OutputTruncated(RuntimeError):
    """模型的输出因为达到长度上限被截断，拿到的 JSON 不完整。"""


@dataclass
class Usage:
    calls: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def add(self, prompt: int, completion: int) -> None:
        with self._lock:
            self.calls += 1
            self.prompt_tokens += prompt
            self.completion_tokens += completion

    def to_dict(self) -> dict:
        return {
            "llm_calls": self.calls,
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
        }


def _bypass_proxy(base_url: str | None) -> bool:
    host = (urlsplit(base_url).hostname or "") if base_url else ""
    return (
        host in ("localhost", "::1", urlsplit(DEFAULT_LLM_BASE_URL).hostname)
        or host.startswith("127.")
    )


class OpenAICompatLLM:
    def __init__(
        self,
        model: str,
        base_url: str | None = None,
        api_key: str | None = None,
        temperature: float | None = 0.0,
        max_tokens: int | None = None,
        json_mode: bool = True,
        timeout: float = 300.0,
    ) -> None:
        from openai import DefaultHttpxClient, OpenAI

        self.model = model
        self.usage = Usage()
        self._temperature = temperature
        self._max_tokens = max_tokens
        self._json_mode = json_mode
        # 本机和开发环境内网模型服务直连，避免被系统代理拦下。
        http_client = DefaultHttpxClient(trust_env=False) if _bypass_proxy(base_url) else None
        self._client = OpenAI(
            api_key=api_key, base_url=base_url, timeout=timeout, max_retries=3, http_client=http_client
        )

    def complete(self, system: str, user: str) -> str:
        from openai import BadRequestError

        params: dict[str, Any] = {
            "model": self.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        }
        if self._max_tokens:
            params["max_tokens"] = self._max_tokens
        # 有的服务不支持自定义 temperature 或 response_format，会直接返回 400。
        # 依次去掉这两个参数重试；哪种组合成功了就记住，之后都用它。
        # 全部失败说明 400 另有原因（比如输入太长），抛出最初的错误，设置保持不变。
        attempts = [(self._temperature, self._json_mode)]
        if self._temperature is not None:
            attempts.append((None, self._json_mode))
        if self._json_mode:
            attempts.append((None, False))
        first_error: BadRequestError | None = None
        for temperature, json_mode in attempts:
            optional: dict[str, Any] = {}
            if temperature is not None:
                optional["temperature"] = temperature
            if json_mode:
                optional["response_format"] = {"type": "json_object"}
            try:
                resp = self._client.chat.completions.create(**params, **optional)
            except BadRequestError as exc:
                first_error = first_error or exc
                continue
            self._temperature, self._json_mode = temperature, json_mode
            break
        else:
            assert first_error is not None
            raise first_error
        if resp.usage is not None:
            self.usage.add(resp.usage.prompt_tokens or 0, resp.usage.completion_tokens or 0)
        else:
            self.usage.add(0, 0)
        choice = resp.choices[0]
        if choice.finish_reason == "length":
            raise OutputTruncated(
                "模型的输出达到长度上限被截断。调大 max_tokens（环境变量 EXTRACT_LLM_MAX_TOKENS），"
                "或者减小 --context-tokens 让每次调用处理的内容少一些"
            )
        return choice.message.content or ""


def llm_from_env(model: str | None = None, base_url: str | None = None) -> OpenAICompatLLM:
    """按 参数 > EXTRACT_LLM_* 环境变量 > OPENAI_* 环境变量 > 开发默认值 取配置。"""
    env = os.environ
    model = model or env.get("EXTRACT_LLM_MODEL") or DEFAULT_LLM_MODEL
    if not model:
        raise ValueError("没有指定模型：用 --model 传入，或设置环境变量 EXTRACT_LLM_MODEL")
    base_url = base_url or env.get("EXTRACT_LLM_BASE_URL") or env.get("OPENAI_BASE_URL") or DEFAULT_LLM_BASE_URL
    api_key = env.get("EXTRACT_LLM_API_KEY") or env.get("OPENAI_API_KEY")
    if not api_key:
        if not base_url:
            raise ValueError("没有 API key：设置环境变量 EXTRACT_LLM_API_KEY")
        api_key = DEFAULT_LLM_API_KEY  # 本地部署的服务一般不校验 key
    max_tokens = env.get("EXTRACT_LLM_MAX_TOKENS")
    return OpenAICompatLLM(
        model, base_url=base_url, api_key=api_key, max_tokens=int(max_tokens) if max_tokens else None
    )


_THINK_RE = re.compile(r"<think>.*?</think>", re.S)
_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)```", re.S)


def parse_json(text: str) -> dict:
    """从模型回复里取出 JSON 对象：容忍思考过程、代码围栏、前后的说明文字。"""
    text = _THINK_RE.sub("", text).strip()
    candidates = [text]
    candidates += _FENCE_RE.findall(text)
    start, end = text.find("{"), text.rfind("}")
    if 0 <= start < end:
        candidates.append(text[start : end + 1])
    for candidate in candidates:
        try:
            data = json.loads(candidate)
        except ValueError:
            continue
        if isinstance(data, dict):
            return data
    raise ValueError("模型的回复不是 JSON 对象")
