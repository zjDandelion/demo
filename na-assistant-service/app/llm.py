import json
import logging
import time
from typing import List, Optional, Tuple

import httpx
from pydantic import ValidationError

from .prompt import build_messages
from .schemas import ModelOutput, ParseRequest, ParseResponse, ParsedItem, ParsedQuestion, ParsedUnmatched, Usage, degraded
from .settings import Settings

logger = logging.getLogger(__name__)

MAX_ITEMS = 20
MAX_QUESTIONS = 5
MAX_QUESTION_LENGTH = 100
MAX_UNMATCHED = 10


class UpstreamStatusError(Exception):
    def __init__(self, status_code: int):
        super().__init__(f"upstream status {status_code}")
        self.status_code = status_code


def _call(settings: Settings, payload: dict, timeout: Optional[float] = None) -> dict:
    """Single HTTP call to the OpenAI-compatible endpoint. Monkeypatched in tests."""
    timeout_seconds = settings.timeout_seconds if timeout is None else max(timeout, 0.1)
    with httpx.Client(timeout=timeout_seconds) as client:
        response = client.post(
            f"{settings.base_url}/chat/completions",
            json=payload,
            headers={"Authorization": f"Bearer {settings.api_key}"},
        )
    if response.status_code >= 400:
        raise UpstreamStatusError(response.status_code)
    return response.json()


def parse_with_model(settings: Settings, request: ParseRequest) -> ParseResponse:
    started = time.monotonic()
    if not settings.model_configured:
        logger.warning("assistant.parse requestId=%s status=DEGRADED reason=DISABLED", request.requestId)
        return degraded("DISABLED", _elapsed_ms(started))

    payload = _payload(settings, request)
    try:
        data = _call(settings, payload)
        if not data.get("choices"):
            raise ValueError("missing choices")
        content = data["choices"][0]["message"]["content"]
        output = _parse_output(content)
    except httpx.TimeoutException:
        return _degraded(request, "TIMEOUT", started)
    except (UpstreamStatusError, httpx.HTTPError):
        return _degraded(request, "UPSTREAM_ERROR", started)
    except (ValueError, KeyError, IndexError, TypeError, ValidationError):
        return _degraded(request, "INVALID_OUTPUT", started)

    items, unmatched, questions, dropped = _sanitize(output, request)
    usage = data.get("usage") or {}
    response = ParseResponse(
        status="OK",
        items=items,
        unmatched=unmatched,
        questions=questions,
        usage=Usage(
            promptTokens=int(usage.get("prompt_tokens") or 0),
            completionTokens=int(usage.get("completion_tokens") or 0),
            latencyMs=_elapsed_ms(started),
        ),
    )
    logger.info(
        "assistant.parse requestId=%s status=OK items=%d dropped=%d latencyMs=%d",
        request.requestId, len(items), dropped, response.usage.latencyMs,
    )
    return response


def _payload(settings: Settings, request: ParseRequest) -> dict:
    return {
        "model": settings.model,
        "messages": build_messages(request),
        "temperature": 0,
        "max_tokens": 512,
        "response_format": {"type": "json_object"},
    }


def _parse_output(content: str) -> ModelOutput:
    return ModelOutput.model_validate(_extract_json(content))


def _extract_json(content: str) -> dict:
    if not isinstance(content, str):
        raise ValueError("model content is not text")
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
        text = text.strip()
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    if start < 0:
        raise ValueError("no json object in model output")
    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(text)):
        character = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                parsed = json.loads(text[start:index + 1])
                if not isinstance(parsed, dict):
                    raise ValueError("model output is not an object")
                return parsed
    raise ValueError("unbalanced json object in model output")


def _sanitize(output: ModelOutput, request: ParseRequest) -> Tuple[List[ParsedItem], List[ParsedUnmatched],
                                                                    List[ParsedQuestion], int]:
    catalog_ids = {item.id for item in request.catalog}
    excluded = set(request.excludeProductIds)
    dropped = 0

    quantities = {}
    order: List[int] = []
    for item in output.items:
        if item.productId in excluded or item.productId not in catalog_ids:
            dropped += 1
            continue
        quantity = max(1, min(99, item.quantity))
        if item.productId in quantities:
            quantities[item.productId] = min(99, quantities[item.productId] + quantity)
        else:
            quantities[item.productId] = quantity
            order.append(item.productId)

    items = [ParsedItem(productId=product_id, quantity=quantities[product_id])
             for product_id in order[:MAX_ITEMS]]

    unmatched: List[ParsedUnmatched] = []
    seen_texts = set()
    for entry in output.unmatched:
        text = entry.text.strip()
        if not text or text in seen_texts:
            continue
        seen_texts.add(text)
        reason = entry.reason.strip() or "暂无此商品"
        unmatched.append(ParsedUnmatched(text=text, reason=reason))
        if len(unmatched) >= MAX_UNMATCHED:
            break

    questions: List[ParsedQuestion] = []
    seen_questions = set()
    for entry in output.questions:
        text = entry.text.strip()[:MAX_QUESTION_LENGTH]
        if not text or text in seen_questions:
            continue
        seen_questions.add(text)
        questions.append(ParsedQuestion(text=text))
        if len(questions) >= MAX_QUESTIONS:
            break

    return items, unmatched, questions, dropped


def _degraded(request: ParseRequest, reason: str, started: float) -> ParseResponse:
    logger.warning(
        "assistant.parse requestId=%s status=DEGRADED reason=%s latencyMs=%d",
        request.requestId, reason, _elapsed_ms(started),
    )
    return degraded(reason, _elapsed_ms(started))


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)
