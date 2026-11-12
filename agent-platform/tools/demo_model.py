"""教程用的假模型服务：手边没有模型服务时，也能把"按 skill 抽取"的整个流程走一遍。

它在本机起一个接口，形状和 OpenAI 的 /v1/chat/completions 相同。收到抽取请求时不做任何"理解"，
只是按下面写死的正则，在提示词给出的片段里找示例任免表（samples/personnel_form_1.6.json）各栏的值，
连同所在的那段原文一起返回。所以：

  - 它只认识这一份示例表，换别的文档什么都找不到；
  - 程序那一半是真的：读取、切片、拼提示词、核对证据、定位出处、汇总结果，走的都是正式的代码；
  - 它不能说明真实模型的效果。真实模型会把简历重新分行、会漏项、会抄错，这些它都不会。

用法（任意 Python 3，不需要安装别的东西）：

    python agent-platform/tools/demo_model.py            # 默认端口 9599
    python agent-platform/tools/demo_model.py --port 9600

然后另开一个窗口，在 agent-platform 目录下：

    java -jar target/agent-platform-0.1.0-SNAPSHOT.jar extract samples/personnel_form_1.6.json \\
        --skill personnel-appointment --base-url http://127.0.0.1:9599/v1

按 Ctrl+C 停掉。这套规则和 Java 测试里的 PersonnelFormRules 相同，改了一边要改另一边。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 标量字段：整个匹配是证据，第 1 个分组是值。表里空着的栏、表里没有的栏不给规则，结果里是"没找到"
RULES = {
    "姓名": r"\| 姓名 \| (\S+) \| 性别",
    "性别": r"性别 \| (\S+) \|",
    "出生年月（岁）": r"出生年月（岁） \| (\S+) \|",
    "民族": r"\| 民族 \| (\S+) \|",
    "籍贯": r"籍贯 \| (\S+) \|",
    "出生地": r"出生地 \| (\S+) \|",
    "入党时间": r"入党时间 \| (\S+) \|",
    "参加工作时间": r"参加工作时间 \| (\S+) \|",
    "健康状况": r"健康状况 \| (\S+) \|",
    "熟悉专业有何专长": r"熟悉专业有何专长 \| (\S+) \|",
    "全日制教育": r"全日制教育 \| (\S+) \|",
    "全日制教育毕业院校系及专业": r"全日制教育 \| \S+ \| \S+ \| 毕业院校系及专业 \| (\S+) \|",
    "在职教育": r"在职教育 \| (\S+) \|",
    "在职教育毕业院校系及专业": r"在职教育 \| \S+ \| \S+ \| 毕业院校系及专业 \| (\S+) \|",
    "现任职务": r"\| 现任职务 \| (\S+) \|",
    "拟任职务": r"\| 拟任职务 \| (\S+) \|",
    "简历": r"\| 简历 \| (.+?) \| 〃",
    "奖惩情况": r"\| 奖惩情况 \| (\S+) \|",
    "年度考核结果": r"\| 年度考核结果 \| (.+?) \| 〃",
    "任免理由": r"\| 任免理由 \| (\S+) \|",
    "呈报单位": r"\| 呈报单位 \| (\S+) \|",
}
# 列表字段：每个匹配是一项，各分组依次是子字段的值。年龄那一格要是数字，表头那一行才不会被当成一个人
LIST_RULES = {
    "家庭成员及主要社会关系": (
        r"\| 家庭主要成员及重要社会关系 \| (\S+) \| (\S+) \| (\d+) \| (\S+) \| (\S+) \|",
        ["称谓", "姓名", "年龄", "政治面貌", "工作单位及职务"],
    ),
}

CHUNK_RE = re.compile(r'<chunk id="(c\d+)"[^>]*>\n(.*?)\n</chunk>', re.S)


def asked(section: str, name: str) -> bool:
    """这一次有没有问到这个字段。每个字段一行，顶格写着"- 字段名（中文名）：类型"；子字段的行是缩进的。"""
    return re.search(r"^- " + re.escape(name) + r"[：（]", section, re.M) is not None


def answer(user: str) -> str:
    """按规则回答一条抽取请求。不是抽取请求（比如让它按目录挑片段）时回一个空对象。"""
    if "## 要抽取的字段\n" not in user:
        return "{}"
    section = user.split("## 要抽取的字段\n", 1)[1].split("\n\n## 文档片段", 1)[0]
    chunks = CHUNK_RE.findall(user)
    out: dict = {}
    for name, pattern in RULES.items():
        if not asked(section, name):
            continue
        out[name] = None
        for cid, text in chunks:
            m = re.search(pattern, text, re.S)
            if m:
                out[name] = {"value": m.group(1), "evidence": m.group(0), "chunk": cid}
                break
    for name, (pattern, subs) in LIST_RULES.items():
        if not asked(section, name):
            continue
        out[name] = [
            {"value": dict(zip(subs, m.groups())), "evidence": m.group(0), "chunk": cid}
            for cid, text in chunks
            for m in re.finditer(pattern, text)
        ]
    return json.dumps(out, ensure_ascii=False)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # /v1/models：有的客户端会先查一下有哪些模型
        self._send(200, {"object": "list", "data": [{"id": "demo", "object": "model"}]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        user = body["messages"][-1]["content"]
        content = answer(user)
        print(f"收到一次调用：提示词 {len(user)} 字，回复 {len(content)} 字", flush=True)
        self._send(200, {
            "id": "chatcmpl-demo",
            "object": "chat.completion",
            "created": 0,
            "model": body.get("model", "demo"),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": len(user), "completion_tokens": len(content),
                      "total_tokens": len(user) + len(content)},
        })

    def _send(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):  # 不打印默认的访问日志
        pass


def main() -> None:
    for stream in (sys.stdout, sys.stderr):  # Windows 控制台默认编码打不出中文
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="教程用的假模型服务，只认识示例任免表")
    parser.add_argument("--port", type=int, default=9599, help="监听的端口（默认 9599）")
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)  # 只听本机，别的机器连不上
    print(f"假模型服务已启动：http://127.0.0.1:{args.port}/v1 （只认识示例任免表，按 Ctrl+C 停止）", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
