from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.settings import Settings

TOKEN = "test-token"
MODEL_URL = "http://model.local/v1"
MODEL_NAME = "test-model"
API_KEY = "test-key"


def make_settings(**overrides) -> Settings:
    values = {
        "internal_token": TOKEN,
        "base_url": MODEL_URL,
        "model": MODEL_NAME,
        "api_key": API_KEY,
        "timeout_ms": 400,
        "debug": False,
    }
    values.update(overrides)
    return Settings(**values)


@pytest.fixture
def settings() -> Settings:
    return make_settings()


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as test_client:
        yield test_client


def auth_headers(token: str = TOKEN) -> dict:
    return {"X-Service-Token": token}


def parse_body(**overrides) -> dict:
    body = {
        "requestId": "drf_test0001",
        "userId": 1,
        "text": "来两个机械键盘",
        "excludeProductIds": [1],
        "catalog": [
            {
                "id": 2,
                "name": "无线鼠标 M330",
                "description": "静音无线鼠标",
                "category": "外设",
                "tags": "鼠标,无线",
                "price": 129.00,
                "stock": 40,
            }
        ],
    }
    body.update(overrides)
    return body


def model_reply(content: str, prompt_tokens: int = 10, completion_tokens: int = 5) -> dict:
    return {
        "choices": [{"message": {"content": content}}],
        "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens},
    }


def assert_contains(expected, actual, path="$"):
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{path} should be an object"
        for key, value in expected.items():
            assert key in actual, f"{path}.{key} is missing"
            assert_contains(value, actual[key], f"{path}.{key}")
    elif isinstance(expected, list):
        assert isinstance(actual, list), f"{path} should be a list"
        assert len(expected) == len(actual), f"{path} length"
        for index, value in enumerate(expected):
            assert_contains(value, actual[index], f"{path}[{index}]")
    elif isinstance(expected, (int, float)) and not isinstance(expected, bool):
        assert Decimal(str(actual)) == Decimal(str(expected)), f"{path}: {actual} != {expected}"
    else:
        assert expected == actual, f"{path}: {actual} != {expected}"
