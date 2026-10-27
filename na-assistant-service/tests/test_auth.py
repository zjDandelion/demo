from conftest import TOKEN, auth_headers, parse_body

URL = "/internal/v1/assistant/parse"


def test_missing_token_is_rejected(client):
    response = client.post(URL, json=parse_body())

    assert response.status_code == 401
    assert response.json()["message"] == "unauthorized"


def test_wrong_token_is_rejected(client):
    response = client.post(URL, json=parse_body(), headers=auth_headers("wrong"))

    assert response.status_code == 401


def test_token_in_query_is_not_accepted(client):
    response = client.post(URL, json=parse_body(), params={"x_service_token": TOKEN})

    assert response.status_code == 401


def test_health_needs_no_token(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_encoded_path_does_not_bypass_auth(client):
    response = client.post("/internal/v1/assistant/%70arse", json=parse_body())

    assert response.status_code == 401


def test_trailing_slash_redirect_still_requires_token(client):
    response = client.post(URL + "/", json=parse_body())

    assert response.status_code == 401
