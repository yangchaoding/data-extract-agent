"""命令行入口。

  extract-agent run INPUT --schema schema.yaml     抽取
  extract-agent chunks INPUT                       只看切片结果，不调用模型
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .agent import ExtractionAgent
from .chunker import chunk_document
from .llm import llm_from_env
from .loader import load_documents
from .schema import load_schema
from .textutil import one_line


def _cmd_chunks(args: argparse.Namespace) -> int:
    for doc in load_documents(args.input):
        chunks = chunk_document(doc, args.chunk_tokens, whole_under=args.context_tokens)
        pages = f"{doc.page_count} 页，" if doc.page_count else ""
        print(f"# {doc.name}：{pages}{len(doc.blocks)} 个版面块，约 {doc.tokens} token，切成 {len(chunks)} 片")
        for c in chunks:
            where = f"第{c.pages}页" if c.pages else "-"
            mark = " [含表格]" if c.has_table else ""
            print(f"{c.id:>5} | {where:>8} | {c.tokens:>5} token | {c.section or '-'}{mark}")
            if args.show:
                print(c.text)
                print("-" * 60)
            else:
                print(f"        {one_line(c.text, 70)}")
    return 0


def _cmd_run(args: argparse.Namespace) -> int:
    schema = load_schema(args.schema)
    docs = load_documents(args.input)
    llm = llm_from_env(args.model, args.base_url)
    agent = ExtractionAgent(
        llm,
        chunk_tokens=args.chunk_tokens,
        context_tokens=args.context_tokens,
        top_k=args.top_k,
        max_rounds=args.max_rounds,
        route=not args.no_route,
        workers=args.workers,
    )
    out_dir: Path | None = None
    if len(docs) > 1:  # 多份文档：每份写一个文件
        out_dir = Path(args.output or "extract_out")
        out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for doc in docs:
        try:
            result = agent.run(doc, schema)
        except Exception as exc:  # noqa: BLE001 - 一份文档失败不影响其余文档
            if out_dir is None:
                raise
            failed += 1
            print(f"{doc.name}: 失败 - {exc}", file=sys.stderr)
            continue
        payload = json.dumps(result.to_dict(), ensure_ascii=False, indent=2)
        if out_dir is not None:
            (out_dir / f"{doc.name}.json").write_text(payload, encoding="utf-8")
        elif args.output:
            Path(args.output).write_text(payload, encoding="utf-8")
        else:
            print(payload)
        s = result.to_dict()["summary"]
        review = f"，需要复核：{'、'.join(s['needs_review'])}" if s["needs_review"] else ""
        print(
            f"{doc.name}: 通过 {s['ok']}，未通过核对 {s['unverified']}，未找到 {s['missing']}{review}",
            file=sys.stderr,
        )
    return 1 if failed else 0


def _add_chunk_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("input", help="识别结果：JSON / Markdown 文件，或放着这些文件的目录")
    p.add_argument("--chunk-tokens", type=int, default=1000, help="每个切片的大小上限（默认 1000）")
    p.add_argument(
        "--context-tokens",
        type=int,
        default=6000,
        help="一次调用里片段总量的上限；整篇文档不超过它时不切片（默认 6000）",
    )


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):  # Windows 控制台默认编码打不出中文
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(
        prog="extract-agent", description="PaddleOCR-VL 识别结果的自动切片与结构化信息抽取"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="抽取")
    _add_chunk_args(run)
    run.add_argument("--schema", required=True, help="抽取 schema（YAML 或 JSON）")
    run.add_argument("-o", "--output", help="输出文件；输入里有多份文档时是输出目录（默认 extract_out）")
    run.add_argument("--model", help="模型名（也可以用环境变量 EXTRACT_LLM_MODEL）")
    run.add_argument("--base-url", help="OpenAI 兼容接口的地址（也可以用 EXTRACT_LLM_BASE_URL）")
    run.add_argument("--top-k", type=int, default=5, help="每个字段每一轮看几个片段（默认 5）")
    run.add_argument("--max-rounds", type=int, default=4, help="一个字段最多找几轮（默认 4）")
    run.add_argument("--no-route", action="store_true", help="不让模型按目录定位，只用关键词检索")
    run.add_argument("--workers", type=int, default=4, help="并发调用数（默认 4）")
    run.set_defaults(func=_cmd_run)

    chunks = sub.add_parser("chunks", help="只看切片结果，不调用模型")
    _add_chunk_args(chunks)
    chunks.add_argument("--show", action="store_true", help="打印每个切片的全文")
    chunks.set_defaults(func=_cmd_chunks)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except (OSError, ValueError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001
        from openai import OpenAIError

        if not isinstance(exc, OpenAIError):
            raise
        print(f"模型接口调用失败：{exc}", file=sys.stderr)  # 认证、额度、网络、模型名写错等
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
