import json

from conftest import auth_headers, model_reply, parse_body

from app import llm

URL = "/internal/v1/assistant/parse"


def test_missing_request_id_is_rejected(client):
    body = parse_body()
    body.pop("requestId")

    response = client.post(URL, json=body, headers=auth_headers())

    assert response.status_code == 400
    assert response.json()["message"] == "请求结构不合契约"


def test_empty_text_is_rejected(client):
    response = client.post(URL, json=parse_body(text=""), headers=auth_headers())

    assert response.status_code == 400


def test_unknown_field_is_rejected(client):
    response = client.post(URL, json=parse_body(unexpected="x"), headers=auth_headers())

    assert response.status_code == 400


def test_catalog_over_limit_is_rejected(client):
    catalog = [{"id": index, "name": f"商品{index}", "price": 1, "stock": 1} for index in range(101)]

    response = client.post(URL, json=parse_body(catalog=catalog), headers=auth_headers())

    assert response.status_code == 400
    assert response.json()["message"] == "目录超过上限"


def test_exclude_over_limit_is_rejected(client):
    response = client.post(URL, json=parse_body(excludeProductIds=list(range(101))), headers=auth_headers())

    assert response.status_code == 400
    assert response.json()["message"] == "排除商品超过上限"


def test_valid_request_is_accepted(client, monkeypatch):
    monkeypatch.setattr(llm, "_call",
                        lambda settings, payload, timeout=None: model_reply(
                            json.dumps({"items": [{"productId": 2, "quantity": 1}]})))

    response = client.post(URL, json=parse_body(), headers=auth_headers())

    assert response.status_code == 200
    assert response.json()["status"] == "OK"
