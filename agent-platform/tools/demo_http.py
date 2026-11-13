"""阶段五的 HTTP 演示：用仓库的虚构样例走完提交、轮询、取结果和清理。

先启动 tools/demo_model.py，再用 serve 启动 Java 服务。这个脚本只是调用方，
没有自己的抽取逻辑；所有文档读取、路由、排队、抽取、核对和存储都由 Java 服务完成。
仅使用 Python 标准库，不需要安装依赖。默认从脚本所在的项目读取样例，工作目录不限。

    python tools/demo_http.py --base-url http://127.0.0.1:8080 --token demo-token
    python tools/demo_http.py --write-request data/demo-request.json

--write-request 只生成一个批量请求文件，不连接服务。演示模式仅删除自己新建的任务。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid


PROJECT = Path(__file__).resolve().parent.parent
FORM = PROJECT / "samples/personnel_form_1.6.json"
CONTRACT = PROJECT / "src/test/resources/fixtures/paddle/sample_contract"
SKILL = "personnel-appointment"


def document(paths: list[Path]) -> dict:
    """一组文件转成 API 接受的写法，base64 保留上传文件的原始字节和哈希。"""
    return {
        "files": [
            {"name": file.name, "content": base64.b64encode(file.read_bytes()).decode("ascii")}
            for file in paths
        ]
    }


def batch_request() -> dict:
    """任免表和三页合同一起交来，由服务按规则分别选择 skill。"""
    return {
        "skill": "auto",
        "documents": [document([FORM]), document(sorted(CONTRACT.glob("*.json")))],
        "idempotencyKey": "http-demo-" + uuid.uuid4().hex,
    }


class Client:
    """简单的 HTTP 调用方，失败回应也解析成 JSON，便于演示程序检查状态码。"""

    def __init__(self, base_url: str, token: str | None, timeout: float = 15):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def request(self, method: str, endpoint: str, payload: dict | None = None,
                data: bytes | None = None, content_type: str | None = None,
                headers: dict | None = None) -> tuple[int, dict | None]:
        """发送请求，返回状态码及 JSON 内容；204 返回 None。字节请求自动带 Content-Length。"""
        request_headers = dict(headers or {})
        if self.token:
            request_headers["Authorization"] = "Bearer " + self.token
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            content_type = "application/json"
        if data is not None:
            request_headers["Content-Length"] = str(len(data))
        if content_type:
            request_headers["Content-Type"] = content_type
        request = Request(self.base_url + endpoint, data=data, headers=request_headers, method=method)
        try:
            response = urlopen(request, timeout=self.timeout)
        except HTTPError as error:
            response = error
        with response:
            body = response.read()
            return response.status, json.loads(body) if body else None

    def poll(self, task_id: str, timeout: float) -> dict:
        """轮询到任务结束。真实模型较慢时用 --timeout 加长等待时间。"""
        deadline = time.monotonic() + timeout
        while True:
            status, task = self.request("GET", "/api/v1/extractions/" + task_id)
            require(status == 200, f"查询任务失败：HTTP {status} {task}")
            if task["status"] not in ("queued", "running"):
                return task
            if time.monotonic() >= deadline:
                raise RuntimeError(f"任务在 {timeout:g} 秒内没有结束：{task_id}")
            time.sleep(0.2)


def require(condition: bool, message: str) -> None:
    """发现和教程约定不符的结果时立即报错，使脚本可用于部署后的冒烟验证。"""
    if not condition:
        raise RuntimeError(message)


def emit(label: str, payload: dict | list) -> None:
    """只展示读者需要的稳定摘要；任务 ID、时间和临时路径不写进教程输出。"""
    print(label + ": " + json.dumps(payload, ensure_ascii=False, separators=(",", ":")), flush=True)


def multipart_form() -> tuple[bytes, str]:
    """构造标准 multipart 表单，和 curl -F 上传同一个文件的效果相同。"""
    boundary = "agent-demo-" + uuid.uuid4().hex
    parts = []
    for key, value in {"skill": SKILL, "name": "multipart-form"}.items():
        parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n"
                      f"{value}\r\n").encode("utf-8"))
    parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; "
                  f"filename=\"{FORM.name}\"\r\nContent-Type: application/json\r\n\r\n").encode("utf-8"))
    parts.append(FORM.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def metric(client: Client, name: str) -> float:
    """读取某个指标的数值；指标的标签明细可以直接打开 actuator 查看。"""
    status, value = client.request("GET", "/actuator/metrics/" + name)
    require(status == 200, f"指标 {name} 不存在：HTTP {status} {value}")
    return value["measurements"][0]["value"]


def demonstrate(client: Client, timeout: float) -> None:
    """走完阶段五主要接口并清理这次演示创建的任务。"""
    created = []
    try:
        status, skills = client.request("GET", "/api/v1/skills")
        require(status == 200, f"读取 skill 失败：HTTP {status} {skills}")
        emit("skills", [{"name": skill["name"], "version": skill["version"],
                         "extractable": skill["extractable"]} for skill in skills["skills"]])

        payload = batch_request()
        status, chunks = client.request("POST", "/api/v1/chunks", {"documents": payload["documents"][:1]})
        require(status == 200, f"切片预览失败：HTTP {status} {chunks}")
        emit("chunks", [{"name": doc["name"], "pages": doc["pageCount"],
                         "chunks": doc["chunkCount"]} for doc in chunks["documents"]])

        status, routes = client.request("POST", "/api/v1/route", {"documents": payload["documents"]})
        require(status == 200, f"规则路由失败：HTTP {status} {routes}")
        emit("route", [{"name": doc["name"], "skill": doc["routing"]["skill"],
                        "method": doc["routing"]["method"], "score": doc["routing"]["score"]}
                       for doc in routes["documents"]])

        status, task = client.request("POST", "/api/v1/extractions", payload)
        require(status == 202, f"新任务应该回 202：HTTP {status} {task}")
        task_id = task["id"]
        created.append(task_id)
        emit("submit", {"http": status, "documents": task["counts"]["total"]})

        status, replay = client.request("POST", "/api/v1/extractions", payload)
        require(status == 200 and replay["id"] == task_id, "同一个幂等键没有返回原任务")
        emit("idempotency", {"http": status, "sameTask": replay["id"] == task_id})

        status, conflict = client.request("POST", "/api/v1/extractions", {**payload, "skill": SKILL})
        require(status == 409 and conflict["error"]["code"] == "conflict", "不同内容共用幂等键没有被拒绝")
        emit("conflict", {"http": status, "code": conflict["error"]["code"]})

        task = client.poll(task_id, timeout)
        require(task["status"] == "partial", f"批量示例应是一份成功、一份未路由：{task}")
        emit("batch", {"status": task["status"], "documents": [
            {"name": doc["name"], "status": doc["status"]} for doc in task["documents"]
        ], "llmCalls": task["usage"]["llmCalls"]})

        status, result = client.request("GET", f"/api/v1/extractions/{task_id}/documents/0/result")
        require(status == 200, f"读取抽取结果失败：HTTP {status} {result}")
        require(result["data"]["姓名"] == "林致远", "示例任免表的姓名不对")
        require(result["summary"]["ok"] == 22 and result["summary"]["missing"] == 4, "示例抽取统计不对")
        file_hash = hashlib.sha256(FORM.read_bytes()).hexdigest()
        locator = result["fields"]["姓名"]["locator"]
        require(locator["file_hash"] == "sha256:" + file_hash, "结果没有正确指向上传文件的哈希")
        require(result["files"][0]["pages"] == [1, 2], "文件与文档页码的映射不对")
        emit("result", {"name": result["data"]["姓名"], "ok": result["summary"]["ok"],
                        "unverified": result["summary"]["unverified"], "missing": result["summary"]["missing"],
                        "fileHashMatches": True, "filePages": result["files"][0]["pages"]})

        status, unavailable = client.request("GET", f"/api/v1/extractions/{task_id}/documents/1/result")
        require(status == 409, "未确定 skill 的文档不应该有抽取结果")
        emit("unrouted-result", {"http": status, "code": unavailable["error"]["code"]})

        data, content_type = multipart_form()
        status, upload = client.request("POST", "/api/v1/extractions", data=data, content_type=content_type)
        require(status == 202, f"表单提交失败：HTTP {status} {upload}")
        created.append(upload["id"])
        uploaded = client.poll(upload["id"], timeout)
        require(uploaded["status"] == "succeeded", f"表单任务没有成功：{uploaded}")
        emit("multipart", {"http": status, "status": uploaded["status"], "name": uploaded["documents"][0]["name"]})

        status, health = client.request("GET", "/actuator/health")
        require(status == 200 and health["status"] == "UP", f"健康检查没有通过：HTTP {status} {health}")
        emit("health", {"status": health["status"], "storeUsable": health["components"]["tasks"]["details"]["storeUsable"]})
        emit("metrics", {
            "queued": metric(client, "agent.extraction.queued"),
            "running": metric(client, "agent.extraction.running"),
            "llmInFlight": metric(client, "agent.llm.inflight"),
            "llmWaiting": metric(client, "agent.llm.waiting"),
            "llmCalls": metric(client, "agent.extraction.llm.calls"),
        })

        if client.token:
            status, error = Client(client.base_url, None).request("GET", "/api/v1/skills")
            require(status == 401, "配置了令牌的服务允许无令牌访问 API")
            emit("unauthorized", {"http": status, "code": error["error"]["code"]})

        for own_id in list(created):
            status, _ = client.request("DELETE", "/api/v1/extractions/" + own_id)
            require(status == 204, f"删除演示任务失败：HTTP {status}")
            created.remove(own_id)
        emit("delete", {"tasks": 2, "http": 204})
    finally:
        # 演示失败时也只清理自己创建的任务；不按列表批量删除，避免碰到调用方的其他工作。
        for own_id in created:
            try:
                client.request("POST", f"/api/v1/extractions/{own_id}/cancel")
                client.poll(own_id, timeout)
                client.request("DELETE", "/api/v1/extractions/" + own_id)
            except Exception as error:
                print(f"演示任务 {own_id} 未能清理：{error}", file=sys.stderr)


def main() -> None:
    """解析演示参数，Windows 上固定用 UTF-8 输出中文。"""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="阶段五 HTTP 演示，只使用仓库里的虚构样例")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="Java 服务地址，不带 /api/v1")
    parser.add_argument("--token", default=os.environ.get("AGENT_API_TOKEN"), help="服务的 Bearer 令牌，也可用 AGENT_API_TOKEN")
    parser.add_argument("--timeout", type=float, default=90, help="每个任务最长等待秒数（默认 90）")
    parser.add_argument("--write-request", type=Path, help="只把批量请求写入这个 JSON 文件，不连接服务")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 要大于 0")
    if args.write_request:
        args.write_request.parent.mkdir(parents=True, exist_ok=True)
        args.write_request.write_text(json.dumps(batch_request(), ensure_ascii=False, indent=2), encoding="utf-8")
        print("已生成批量请求：" + str(args.write_request))
        return
    demonstrate(Client(args.base_url, args.token), args.timeout)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError) as error:
        print("演示失败：" + str(error), file=sys.stderr)
        sys.exit(1)
