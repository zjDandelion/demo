import json
import logging

from conftest import API_KEY, TOKEN, auth_headers, model_reply, parse_body

from app import llm

URL = "/internal/v1/assistant/parse"


def test_logs_never_contain_user_text_or_secrets(client, monkeypatch, caplog):
    monkeypatch.setattr(llm, "_call",
                        lambda settings, payload, timeout=None: model_reply(json.dumps({"items": []})))
    secret_text = "机密口令-abc123"

    with caplog.at_level(logging.INFO):
        response = client.post(URL, json=parse_body(text=secret_text), headers=auth_headers())

    assert response.status_code == 200
    assert secret_text not in caplog.text
    assert "abc123" not in caplog.text
    assert TOKEN not in caplog.text
    assert API_KEY not in caplog.text
    assert "textHash=" in caplog.text
    assert "requestId=drf_test0001" in caplog.text


def test_degraded_logs_reason_only(client, monkeypatch, caplog):
    monkeypatch.setattr(llm, "_call", lambda settings, payload, timeout=None: model_reply("not json"))
    secret_text = "机密口令-def456"

    with caplog.at_level(logging.INFO):
        client.post(URL, json=parse_body(text=secret_text), headers=auth_headers())

    assert secret_text not in caplog.text
    assert "reason=INVALID_OUTPUT" in caplog.text
