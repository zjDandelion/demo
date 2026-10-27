import json
from pathlib import Path

from conftest import assert_contains

from app.schemas import ParseRequest, ParseResponse

CONTRACT_DIR = Path(__file__).resolve().parents[2] / "tests" / "contract"


def read(name: str) -> dict:
    return json.loads((CONTRACT_DIR / name).read_text(encoding="utf-8"))


def test_request_fixture_round_trips():
    fixture = read("assistant-parse.request.json")

    request = ParseRequest.model_validate(fixture)

    assert request.requestId == "drf_5f2c9a1b"
    assert request.userId == 1
    assert request.text == "帮我拿两个机械键盘，再加一张升降桌"
    assert request.excludeProductIds == [1]
    assert request.catalog[0].id == 1
    assert request.catalog[0].name == "机械键盘 K87"
    assert_contains(fixture, request.model_dump(), "$")


def test_ok_fixture_round_trips():
    fixture = read("assistant-parse.ok.json")

    response = ParseResponse.model_validate(fixture)

    assert response.status == "OK"
    assert response.items[0].productId == 6
    assert response.items[0].quantity == 1
    assert response.unmatched[0].text == "升降桌"
    assert response.usage.promptTokens == 620
    assert response.usage.latencyMs == 870
    assert_contains(fixture, response.model_dump(), "$")


def test_degraded_fixture_round_trips():
    fixture = read("assistant-parse.degraded.json")

    response = ParseResponse.model_validate(fixture)

    assert response.status == "DEGRADED"
    assert response.reason == "TIMEOUT"
    assert response.items == []
    assert response.unmatched == []
    assert response.questions == []
    assert response.usage.latencyMs == 4000
    assert_contains(fixture, response.model_dump(), "$")
