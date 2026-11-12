"""模型客户端的测试：用本机的一个假服务代替真实的 OpenAI 兼容接口，走真实的 HTTP 请求。"""

import pytest

from extract_agent.llm import OpenAICompatLLM, OutputTruncated, llm_from_env, parse_json


def test_request_shape_and_usage(server):
    llm = OpenAICompatLLM("test-model", base_url=server.url, api_key="sk-test", max_tokens=2048)
    assert llm.complete("系统提示", "用户消息") == '{"ok": true}'
    (req,) = server.requests
    assert req["path"] == "/v1/chat/completions" and req["auth"] == "Bearer sk-test"
    assert req["model"] == "test-model" and req["max_tokens"] == 2048
    assert req["messages"] == [
        {"role": "system", "content": "系统提示"},
        {"role": "user", "content": "用户消息"},
    ]
    assert req["temperature"] == 0 and req["response_format"] == {"type": "json_object"}
    llm.complete("s", "u")
    assert llm.usage.to_dict() == {"llm_calls": 2, "prompt_tokens": 22, "completion_tokens": 10}


def test_falls_back_when_the_service_rejects_json_mode(server):
    server.rejects = ("response_format",)
    llm = OpenAICompatLLM("local-model", base_url=server.url, api_key="EMPTY")
    assert llm.complete("s", "u") == '{"ok": true}'
    assert "response_format" not in server.requests[-1]
    before = len(server.requests)
    llm.complete("s", "u")  # 之后不再带这个参数，也就不再有失败的请求
    assert len(server.requests) == before + 1 and "response_format" not in server.requests[-1]


def test_keeps_json_mode_when_only_temperature_is_rejected(server):
    server.rejects = ("temperature",)  # 只接受默认采样参数的模型
    llm = OpenAICompatLLM("reasoning-model", base_url=server.url, api_key="EMPTY")
    assert llm.complete("s", "u") == '{"ok": true}'
    last = server.requests[-1]
    assert "temperature" not in last and last["response_format"] == {"type": "json_object"}
    assert len(server.requests) == 2


def test_bad_request_for_another_reason_is_raised_and_settings_are_kept(server):
    from openai import BadRequestError

    server.rejects = ("messages",)  # 例如输入超过了模型的上下文长度：去掉哪个参数都没用
    llm = OpenAICompatLLM("test-model", base_url=server.url, api_key="EMPTY")
    with pytest.raises(BadRequestError):
        llm.complete("s", "u")
    server.rejects = ()
    llm.complete("s", "u")
    assert server.requests[-1]["temperature"] == 0 and "response_format" in server.requests[-1]


def test_thinking_switch_is_sent_only_when_configured(server):
    OpenAICompatLLM("m", base_url=server.url, api_key="EMPTY").complete("s", "u")
    assert "chat_template_kwargs" not in server.requests[-1]  # 默认不带，由服务自己决定
    OpenAICompatLLM("m", base_url=server.url, api_key="EMPTY", enable_thinking=False).complete("s", "u")
    assert server.requests[-1]["chat_template_kwargs"] == {"enable_thinking": False}
    OpenAICompatLLM("m", base_url=server.url, api_key="EMPTY", enable_thinking=True).complete("s", "u")
    assert server.requests[-1]["chat_template_kwargs"] == {"enable_thinking": True}


def test_thinking_switch_is_dropped_last_when_the_service_rejects_it(server):
    server.rejects = ("chat_template_kwargs",)  # 例如 OpenAI 官方接口：不认识的参数直接 400
    llm = OpenAICompatLLM("m", base_url=server.url, api_key="EMPTY", enable_thinking=False)
    assert llm.complete("s", "u") == '{"ok": true}'
    last = server.requests[-1]
    # 先带着开关把三种组合试完，再去掉它从头试：别的参数不受连累
    assert len(server.requests) == 4 and "chat_template_kwargs" not in last
    assert last["temperature"] == 0 and last["response_format"] == {"type": "json_object"}
    llm.complete("s", "u")  # 记住了，不再先带着试一次
    assert len(server.requests) == 5 and "chat_template_kwargs" not in server.requests[-1]


def test_thinking_switch_survives_when_only_temperature_is_rejected(server):
    server.rejects = ("temperature",)
    llm = OpenAICompatLLM("m", base_url=server.url, api_key="EMPTY", enable_thinking=False)
    llm.complete("s", "u")
    last = server.requests[-1]
    assert len(server.requests) == 2 and "temperature" not in last
    assert last["chat_template_kwargs"] == {"enable_thinking": False}


def test_thinking_switch_from_env(server, monkeypatch):
    monkeypatch.setenv("EXTRACT_LLM_BASE_URL", server.url)
    monkeypatch.delenv("EXTRACT_LLM_ENABLE_THINKING", raising=False)
    llm_from_env().complete("s", "u")
    assert "chat_template_kwargs" not in server.requests[-1]
    for text, expected in (("false", False), ("OFF", False), ("0", False), ("true", True), (" Yes ", True)):
        monkeypatch.setenv("EXTRACT_LLM_ENABLE_THINKING", text)
        llm_from_env().complete("s", "u")
        assert server.requests[-1]["chat_template_kwargs"] == {"enable_thinking": expected}
    monkeypatch.setenv("EXTRACT_LLM_ENABLE_THINKING", "")
    llm_from_env().complete("s", "u")
    assert "chat_template_kwargs" not in server.requests[-1]
    monkeypatch.setenv("EXTRACT_LLM_ENABLE_THINKING", "maybe")
    with pytest.raises(ValueError, match="EXTRACT_LLM_ENABLE_THINKING"):
        llm_from_env()


def test_truncated_output_raises_instead_of_returning_half_a_json(server):
    server.reply, server.finish_reason = '{"items": [{"value": ', "length"
    llm = OpenAICompatLLM("test-model", base_url=server.url, api_key="EMPTY")
    with pytest.raises(OutputTruncated):
        llm.complete("s", "u")


def test_llm_from_env(server, monkeypatch):
    for var in ("EXTRACT_LLM_MODEL", "EXTRACT_LLM_BASE_URL", "EXTRACT_LLM_API_KEY", "EXTRACT_LLM_MAX_TOKENS",
                "EXTRACT_LLM_ENABLE_THINKING", "OPENAI_API_KEY", "OPENAI_BASE_URL"):
        monkeypatch.delenv(var, raising=False)
    # 将开发默认接口替换成本机假服务，验证不设置环境变量也能调用。
    monkeypatch.setattr("extract_agent.llm.DEFAULT_LLM_BASE_URL", server.url)
    llm_from_env().complete("s", "u")
    assert server.requests[-1]["model"] == "cb-llm"
    assert server.requests[-1]["auth"] == "Bearer EMPTY"
    llm_from_env("some-model").complete("s", "u")
    assert server.requests[-1]["model"] == "some-model"
    monkeypatch.setenv("EXTRACT_LLM_MODEL", "env-model")
    monkeypatch.setenv("EXTRACT_LLM_BASE_URL", server.url)
    monkeypatch.setenv("EXTRACT_LLM_MAX_TOKENS", "4096")
    llm_from_env().complete("s", "u")  # 本地服务：没有 key 也能用
    assert server.requests[-1]["model"] == "env-model" and server.requests[-1]["max_tokens"] == 4096
    monkeypatch.setenv("EXTRACT_LLM_BASE_URL", "http://invalid.example/v1")
    llm_from_env("argument-model", server.url).complete("s", "u")
    assert server.requests[-1]["model"] == "argument-model"


def test_parse_json():
    assert parse_json('{"a": 1}') == {"a": 1}
    assert parse_json('```json\n{"a": 1}\n```') == {"a": 1}
    assert parse_json('<think>想一想 {不是答案}</think>{"a": 1}') == {"a": 1}
    assert parse_json('结果：{"a": {"b": 2}} 完毕') == {"a": {"b": 2}}
    for bad in ("", "没有找到", "[1, 2]", '{"a": '):
        with pytest.raises(ValueError):
            parse_json(bad)
