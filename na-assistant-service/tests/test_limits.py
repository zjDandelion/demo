from fastapi.testclient import TestClient

from conftest import auth_headers, make_settings

from app.main import create_app

URL = "/internal/v1/assistant/parse"


def test_oversized_body_returns_413():
    with TestClient(create_app(make_settings(max_body_bytes=512))) as client:
        response = client.post(URL, content=b"x" * 1024,
                               headers={**auth_headers(), "Content-Type": "application/json"})

    assert response.status_code == 413
    assert response.json()["message"] == "请求体过大"


def test_docs_are_disabled_by_default(client):
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
    assert client.get("/openapi.json").status_code == 404


def test_docs_are_available_in_debug_mode():
    with TestClient(create_app(make_settings(debug=True))) as client:
        assert client.get("/docs").status_code == 200
        assert client.get("/openapi.json").status_code == 200
