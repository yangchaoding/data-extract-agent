import json

import pytest

from extract_agent.cli import main
from extract_agent.schema import load_schema, schema_from_dict

from support import CONTRACT_RULES, EXAMPLES, RuleLLM, block, page


@pytest.mark.parametrize("name", ["contract", "annual_report", "vat_invoice", "generic"])
def test_example_schemas_are_valid(name):
    schema = load_schema(EXAMPLES / "schemas" / f"{name}.yaml")
    assert schema.fields and schema.description
    for f in schema.fields:
        assert f.query  # 每个字段都有可用来定位的检索词


def test_field_description_shown_to_the_model():
    schema = schema_from_dict({"fields": [
        {"name": "amount", "title": "金额", "type": "number", "required": True,
         "description": "单位为元", "hints": ["合同总价", "总金额"]},
        {"name": "way", "type": "enum", "options": ["诉讼", "仲裁"]},
        {"name": "summary", "verbatim": False},
        {"name": "items", "type": "list", "fields": [{"name": "name", "title": "名称"}, {"name": "qty", "type": "integer"}]},
        {"name": "tags", "type": "list"},
    ]})
    assert schema["amount"].describe() == "- amount（金额）：number，必填。单位为元（原文中可能写作：合同总价、总金额）"
    assert schema["way"].describe() == "- way：enum，可选项：诉讼 / 仲裁"
    assert schema["summary"].describe() == "- summary：string，可以概括"
    assert schema["items"].describe().splitlines() == [
        "- items：list",
        "  每一项是一个对象，包含：",
        "    - name（名称）：string",
        "    - qty：integer",
    ]
    assert schema["tags"].describe().splitlines()[1] == "  每一项是一个字符串"
    assert schema["items"].query == "名称 qty"


@pytest.mark.parametrize(
    "raw, message",
    [
        ({}, "fields"),
        ({"fields": []}, "fields"),
        ({"fields": [{"title": "没有名字"}]}, "name"),
        ({"fields": [{"name": "a", "type": "money"}]}, "type"),
        ({"fields": [{"name": "a", "type": "enum"}]}, "options"),
        ({"fields": [{"name": "a", "fields": [{"name": "b"}]}]}, "list"),
        ({"fields": [{"name": "a", "type": "list", "fields": [{"name": "b", "type": "list"}]}]}, "list"),
        ({"fields": [{"name": "a"}, {"name": "a"}]}, "重复"),
        ({"fields": [{"name": "a", "requried": True}]}, "requried"),  # 属性名拼错
    ],
)
def test_invalid_schema_is_rejected_with_a_reason(raw, message):
    with pytest.raises(ValueError, match=message):
        schema_from_dict(raw)


def test_json_schema_file(tmp_path):
    path = tmp_path / "s.json"
    path.write_text(json.dumps({"name": "x", "fields": [{"name": "a", "top_k": 9}]}), encoding="utf-8")
    schema = load_schema(path)
    assert schema.name == "x" and schema["a"].top_k == 9


def test_cli_chunks_command(capsys):
    assert main(["chunks", str(EXAMPLES / "ocr" / "sample_contract"), "--chunk-tokens", "200", "--context-tokens", "0"]) == 0
    out = capsys.readouterr().out
    assert "sample_contract：3 页" in out and "切成 7 片" in out
    assert "第2-3页" in out and "[含表格]" in out


def test_cli_reports_bad_input(capsys, tmp_path):
    assert main(["chunks", str(tmp_path / "missing.json")]) == 1
    assert "找不到输入" in capsys.readouterr().err
    assert main(["chunks", str(tmp_path)]) == 1
    assert "没有找到" in capsys.readouterr().err


CONTRACT = [str(EXAMPLES / "ocr" / "sample_contract"), "--schema", str(EXAMPLES / "schemas" / "contract.yaml")]


def test_cli_run_end_to_end(server, tmp_path, monkeypatch, capsys):
    """命令行 → 读取 → 切片 → agent → openai SDK → HTTP → 核对 → 结果文件，只有模型是假的。"""
    server.responder = RuleLLM(CONTRACT_RULES).complete
    monkeypatch.setenv("EXTRACT_LLM_API_KEY", "sk-test")
    out = tmp_path / "result.json"
    assert main(["run", *CONTRACT, "--model", "test-model", "--base-url", server.url, "-o", str(out)]) == 0

    result = json.loads(out.read_text(encoding="utf-8"))
    assert result["document"] == "sample_contract" and result["schema"] == "contract"
    assert result["data"]["party_b"] == "青岚软件技术有限公司"
    assert result["data"]["total_amount"] == 1280000
    assert len(result["data"]["payment_schedule"]) == 4
    assert result["fields"]["sign_date"] == {
        "status": "ok", "value": "2024-03-18", "evidence": "签订日期：2024年3月18日", "chunk": "c1",
        "pages": "1", "bbox": [120, 410, 620, 480],
    }
    assert result["summary"] == {
        "ok": 13, "unverified": 0, "missing": 0, "needs_review": [], "chunks": 1,
        "llm_calls": 2, "prompt_tokens": 22, "completion_tokens": 10,
    }
    assert {r["model"] for r in server.requests} == {"test-model"}
    assert {r["auth"] for r in server.requests} == {"Bearer sk-test"}
    assert "sample_contract: 通过 13，未通过核对 0，未找到 0" in capsys.readouterr().err


def test_cli_run_on_a_directory_of_documents(server, tmp_path, monkeypatch, capsys):
    src = tmp_path / "ocr"
    src.mkdir()
    for name, party in (("a", "甲公司"), ("b", "乙公司")):
        data = page(0, [block("text", f"委托方：{party}")], count=1, input_path=f"{name}.pdf")
        (src / f"{name}_0_res.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    schema = tmp_path / "schema.json"
    schema.write_text(json.dumps({"fields": [{"name": "client", "required": True}]}), encoding="utf-8")
    server.responder = RuleLLM({"client": r"委托方：(\S+)"}).complete
    monkeypatch.setenv("EXTRACT_LLM_MODEL", "test-model")
    monkeypatch.setenv("EXTRACT_LLM_BASE_URL", server.url)
    out = tmp_path / "out"
    assert main(["run", str(src), "--schema", str(schema), "-o", str(out)]) == 0
    clients = {f.stem: json.loads(f.read_text(encoding="utf-8"))["data"]["client"] for f in out.glob("*.json")}
    assert clients == {"a": "甲公司", "b": "乙公司"}


def test_cli_run_reports_api_failure(server, monkeypatch, capsys):
    server.rejects = ("messages",)
    monkeypatch.setenv("EXTRACT_LLM_API_KEY", "sk-test")
    assert main(["run", *CONTRACT, "--model", "test-model", "--base-url", server.url]) == 1
    captured = capsys.readouterr()
    assert "模型接口调用失败" in captured.err and captured.out == ""


def test_cli_run_uses_development_defaults(server, tmp_path, monkeypatch, capsys):
    for var in ("EXTRACT_LLM_MODEL", "EXTRACT_LLM_BASE_URL", "EXTRACT_LLM_API_KEY", "EXTRACT_LLM_MAX_TOKENS",
                "OPENAI_API_KEY", "OPENAI_BASE_URL"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setattr("extract_agent.llm.DEFAULT_LLM_BASE_URL", server.url)
    server.responder = RuleLLM(CONTRACT_RULES).complete
    out = tmp_path / "result.json"
    assert main(["run", *CONTRACT, "-o", str(out)]) == 0
    result = json.loads(out.read_text(encoding="utf-8"))
    assert result["data"]["total_amount"] == 1280000
    assert {r["model"] for r in server.requests} == {"cb-llm"}
    assert {r["auth"] for r in server.requests} == {"Bearer EMPTY"}
    assert "sample_contract: 通过 13" in capsys.readouterr().err
