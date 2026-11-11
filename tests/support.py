"""测试用的构造工具：拼 PaddleOCR-VL 格式的识别结果、两种假模型、一个假的模型服务。"""

from __future__ import annotations

import json
import re
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from extract_agent.prompts import ROUTE_SYSTEM

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples"

SETTINGS = {
    "use_doc_preprocessor": False,
    "use_layout_detection": True,
    "use_chart_recognition": False,
    "format_block_content": False,
}


def block(label: str, content: str, bbox=(100, 100, 1100, 160), **extra) -> dict:
    return {"block_label": label, "block_content": content, "block_bbox": list(bbox), **extra}


def page(index: int | None, blocks: list[dict], count: int | None = None, input_path="doc.pdf") -> dict:
    """一页的识别结果，结构与 save_to_json() 保存的文件相同。"""
    return {
        "input_path": input_path,
        "page_index": index,
        "page_count": count,
        "width": 1200,
        "height": 1700,
        "model_settings": SETTINGS,
        "parsing_res_list": [
            {"block_id": i, "block_order": i + 1, "group_id": i, **b} for i, b in enumerate(blocks)
        ],
    }


def html_table(rows: list[list[str]], tag: str = "td") -> str:
    body = "".join("<tr>" + "".join(f"<{tag}>{c}</{tag}>" for c in row) + "</tr>" for row in rows)
    return f"<table>{body}</table>"


_CHUNK_RE = re.compile(r'<chunk id="(c\d+)"[^>]*>\n(.*?)\n</chunk>', re.S)


def shown_chunks(prompt: str) -> dict[str, str]:
    """提示词里给模型看的片段：{id: 文本}。"""
    return dict(_CHUNK_RE.findall(prompt))


def asked_fields(prompt: str) -> list[str]:
    """提示词里要求抽取的字段名。"""
    section = prompt.split("## 要抽取的字段\n", 1)[1].split("\n\n## 文档片段", 1)[0]
    return re.findall(r"^- (\w+)", section, re.M)


class RuleLLM:
    """像一个靠谱的模型那样回复：按每个字段的正则在给出的片段里找，找到就连同原文一起返回。

    rules: {字段名: 正则}。整个匹配是 evidence，第一个分组是值。
    列表字段的规则写成 (正则, [子字段名…])：每个匹配是一项，各分组依次是子字段的值。
    route: 定位请求的回复 {字段名: [片段 id…]}。
    """

    def __init__(self, rules: dict, route: dict | None = None) -> None:
        self.rules = rules
        self.route = route or {}
        self.calls: list[tuple[str, str]] = []

    @property
    def extraction_calls(self) -> list[str]:
        return [user for system, user in self.calls if system != ROUTE_SYSTEM]

    def complete(self, system: str, user: str) -> str:
        self.calls.append((system, user))
        if system == ROUTE_SYSTEM:
            return json.dumps(self.route)
        chunks = shown_chunks(user)
        out = {name: self._answer(self.rules.get(name), chunks) for name in asked_fields(user)}
        return json.dumps(out, ensure_ascii=False)

    @staticmethod
    def _answer(rule, chunks: dict[str, str]):
        if rule is None:
            return None
        if isinstance(rule, tuple):
            pattern, subs = rule
            items = []
            for cid, text in chunks.items():
                for m in re.finditer(pattern, text):
                    value = dict(zip(subs, m.groups())) if subs else m.group(1)
                    items.append({"value": value, "evidence": m.group(0), "chunk": cid})
            return items
        for cid, text in chunks.items():
            m = re.search(rule, text)
            if m:
                return {"value": m.group(1), "evidence": m.group(0), "chunk": cid}
        return None


class ScriptedLLM:
    """按顺序给出预先写好的回复；最后一条会一直重复。"""

    def __init__(self, replies: list) -> None:
        self.replies = list(replies)
        self.calls: list[tuple[str, str]] = []

    def complete(self, system: str, user: str) -> str:
        self.calls.append((system, user))
        reply = self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]
        return reply if isinstance(reply, str) else json.dumps(reply, ensure_ascii=False)


# 示例合同（examples/ocr/sample_contract）对应 examples/schemas/contract.yaml 各字段的规则
CONTRACT_RULES = {
    "contract_name": r"# (软件开发服务合同)",
    "contract_no": r"合同编号：(\S+)",
    "party_a": r"甲方（委托方）：(\S+)",
    "party_b": r"乙方（受托方）：(\S+)",
    "sign_date": r"签订日期：(\d+年\d+月\d+日)",
    "total_amount": r"含税总价为人民币([\d,.]+)元",
    "tax_rate": r"增值税税率为(\d+)%",
    "term_start": r"自(\d+年\d+月\d+日)起至\d+年\d+月\d+日止",
    "term_end": r"自\d+年\d+月\d+日起至(\d+年\d+月\d+日)止",
    "payment_schedule": (r"\| (第.期) \| (.+?) \| (\d+)% \| ([\d,.]+) \|", ["stage", "condition", "ratio", "amount"]),
    "breach_penalty": r"(每逾期一日，应按合同总价的0.05%向甲方支付违约金)",
    "dispute_resolution": r"提交杭州(仲裁)委员会",
    "dispute_venue": r"提交(杭州仲裁委员会)",
}


class _ChatHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        srv = self.server
        srv.requests.append({"path": self.path, "auth": self.headers.get("Authorization"), **body})
        for param in srv.rejects:
            if param in body:
                error = {"message": f"{param} is not supported", "type": "invalid_request_error"}
                return self._send(400, {"error": error})
        system, user = (m["content"] for m in body["messages"])
        content = srv.responder(system, user) if srv.responder else srv.reply
        self._send(200, {
            "id": "chatcmpl-test",
            "object": "chat.completion",
            "created": 0,
            "model": body["model"],
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": srv.finish_reason,
            }],
            "usage": {"prompt_tokens": 11, "completion_tokens": 5, "total_tokens": 16},
        })

    def _send(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


@contextmanager
def openai_server():
    """本机的假模型服务，接口形状与 OpenAI 的 /v1/chat/completions 相同，用来走真实的 HTTP 请求。

    srv.reply 是固定的回复；设了 srv.responder(system, user) 就由它生成回复。
    srv.rejects 里的请求参数一出现就返回 400。收到的请求记在 srv.requests 里。
    """
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _ChatHandler)
    srv.requests, srv.reply, srv.responder, srv.finish_reason, srv.rejects = [], '{"ok": true}', None, "stop", ()
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    srv.url = f"http://127.0.0.1:{srv.server_address[1]}/v1"
    try:
        yield srv
    finally:
        srv.shutdown()
        srv.server_close()
