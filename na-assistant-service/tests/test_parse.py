import json

import httpx

from conftest import auth_headers, make_settings, model_reply, parse_body

from app import llm
from app.schemas import ParseRequest

URL = "/internal/v1/assistant/parse"


def post(client, **overrides):
    return client.post(URL, json=parse_body(**overrides), headers=auth_headers())


def test_ok_response_maps_items_and_usage(client, monkeypatch):
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(
        json.dumps({"items": [{"productId": 2, "quantity": 3}], "unmatched": [], "questions": []}),
        prompt_tokens=11, completion_tokens=7))

    body = post(client).json()

    assert body["status"] == "OK"
    assert body["items"] == [{"productId": 2, "quantity": 3}]
    assert body["unmatched"] == []
    assert body["questions"] == []
    assert body["usage"]["promptTokens"] == 11
    assert body["usage"]["completionTokens"] == 7
    assert body["usage"]["latencyMs"] >= 0


def test_model_request_uses_fixed_parameters(client, monkeypatch):
    captured = {}

    def fake_call(settings, payload, timeout=None):
        captured["payload"] = payload
        captured["settings"] = settings
        return model_reply(json.dumps({"items": []}))

    monkeypatch.setattr(llm, "_call", fake_call)
    post(client, text="帮我买点水果")

    payload = captured["payload"]
    assert payload["temperature"] == 0
    assert payload["max_tokens"] == 512
    assert payload["response_format"] == {"type": "json_object"}
    assert payload["messages"][-1] == {"role": "user", "content": "帮我买点水果"}
    assert "2|无线鼠标 M330" in payload["messages"][1]["content"]
    assert "已解析商品（禁止再次输出）：1" in payload["messages"][1]["content"]


def test_fenced_json_is_accepted(client, monkeypatch):
    content = "```json\n" + json.dumps({"items": [{"productId": 2, "quantity": 1}]}) + "\n```"
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(content))

    body = post(client).json()

    assert body["status"] == "OK"
    assert body["items"] == [{"productId": 2, "quantity": 1}]


def test_json_embedded_in_prose_is_extracted(client, monkeypatch):
    content = '好的，结果如下：{"items": [{"productId": 2, "quantity": 2}]} 请核对。'
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(content))

    body = post(client).json()

    assert body["status"] == "OK"
    assert body["items"] == [{"productId": 2, "quantity": 2}]


def test_non_json_output_degrades(client, monkeypatch):
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply("抱歉，我无法处理"))

    body = post(client).json()

    assert body["status"] == "DEGRADED"
    assert body["reason"] == "INVALID_OUTPUT"
    assert body["items"] == []


def test_schema_violation_degrades(client, monkeypatch):
    content = json.dumps({"items": [{"productId": 2, "quantity": "many"}]})
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(content))

    body = post(client).json()

    assert body["status"] == "DEGRADED"
    assert body["reason"] == "INVALID_OUTPUT"


def test_missing_choices_degrades(client, monkeypatch):
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: {"usage": {}})

    body = post(client).json()

    assert body["reason"] == "INVALID_OUTPUT"


def test_timeout_degrades(client, monkeypatch):
    def timeout(settings, payload, timeout=None):
        raise httpx.ReadTimeout("slow")

    monkeypatch.setattr(llm, "_call", timeout)

    body = post(client).json()

    assert body["status"] == "DEGRADED"
    assert body["reason"] == "TIMEOUT"
    assert body["items"] == []


def test_connect_error_degrades(client, monkeypatch):
    def unreachable(settings, payload, timeout=None):
        raise httpx.ConnectError("down")

    monkeypatch.setattr(llm, "_call", unreachable)

    body = post(client).json()

    assert body["reason"] == "UPSTREAM_ERROR"


def test_upstream_status_degrades(client, monkeypatch):
    def bad_status(settings, payload, timeout=None):
        raise llm.UpstreamStatusError(500)

    monkeypatch.setattr(llm, "_call", bad_status)

    body = post(client).json()

    assert body["reason"] == "UPSTREAM_ERROR"


def test_missing_configuration_degrades_without_calling_model(monkeypatch):
    def explode(*args, **kwargs):
        raise AssertionError("model must not be called when configuration is missing")

    monkeypatch.setattr(llm, "_call", explode)

    response = llm.parse_with_model(make_settings(api_key=""), ParseRequest.model_validate(parse_body()))

    assert response.status == "DEGRADED"
    assert response.reason == "DISABLED"


def test_excluded_and_unknown_products_are_dropped(client, monkeypatch):
    content = json.dumps({"items": [
        {"productId": 1, "quantity": 1},
        {"productId": 99, "quantity": 1},
        {"productId": 2, "quantity": 1},
    ]})
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(content))

    body = post(client).json()

    assert body["items"] == [{"productId": 2, "quantity": 1}]


def test_duplicate_items_are_summed_and_clamped(client, monkeypatch):
    content = json.dumps({"items": [
        {"productId": 2, "quantity": 60},
        {"productId": 2, "quantity": 60},
    ]})
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(content))

    body = post(client).json()

    assert body["items"] == [{"productId": 2, "quantity": 99}]


def test_quantity_is_clamped_to_positive_range(client, monkeypatch):
    content = json.dumps({"items": [
        {"productId": 2, "quantity": 0},
    ]})
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(content))

    body = post(client).json()

    assert body["items"] == [{"productId": 2, "quantity": 1}]


def test_questions_are_trimmed_and_deduplicated(client, monkeypatch):
    content = json.dumps({
        "items": [],
        "questions": [
            {"text": " 请选择颜色 "},
            {"text": "请选择颜色"},
            {"text": "长" * 150},
        ],
    })
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply(content))

    questions = post(client).json()["questions"]

    assert questions == [{"text": "请选择颜色"}, {"text": "长" * 100}]
