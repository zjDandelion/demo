import hashlib
import logging

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import llm
from .auth import require_token
from .schemas import ParseRequest, ParseResponse, degraded
from .settings import Settings

logger = logging.getLogger("assistant")


def create_app(settings: Settings) -> FastAPI:
    app = FastAPI(
        title="na-assistant-service",
        docs_url="/docs" if settings.debug else None,
        redoc_url="/redoc" if settings.debug else None,
        openapi_url="/openapi.json" if settings.debug else None,
    )
    app.state.settings = settings
    authorize = require_token(settings)

    @app.middleware("http")
    async def limit_body_size(request: Request, call_next):
        raw_length = request.headers.get("content-length")
        if raw_length and raw_length.isdigit() and int(raw_length) > settings.max_body_bytes:
            return JSONResponse(status_code=413, content={"message": "请求体过大"})
        return await call_next(request)

    @app.exception_handler(HTTPException)
    async def http_error(_: Request, error: HTTPException) -> JSONResponse:
        detail = error.detail if isinstance(error.detail, str) else "请求处理失败"
        return JSONResponse(status_code=error.status_code, content={"message": detail})

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_: Request, error: RequestValidationError) -> JSONResponse:
        logger.error("assistant.parse invalid request: %s", _locations(error))
        return JSONResponse(status_code=400, content={"message": "请求结构不合契约"})

    @app.exception_handler(Exception)
    async def unexpected(_: Request, error: Exception) -> JSONResponse:
        logger.exception("assistant.parse unexpected error: %s", type(error).__name__)
        return JSONResponse(status_code=200, content=degraded("UPSTREAM_ERROR").model_dump())

    @app.get("/health")
    def health() -> dict:
        return {"status": "UP"}

    @app.post("/internal/v1/assistant/parse", response_model=ParseResponse)
    def parse(payload: ParseRequest, _: None = Depends(authorize)) -> ParseResponse:
        check_limits(settings, payload)
        logger.info(
            "assistant.parse received requestId=%s user=%s textLength=%d textHash=%s catalog=%d",
            payload.requestId, payload.userId, len(payload.text), _digest(payload.text), len(payload.catalog),
        )
        return llm.parse_with_model(settings, payload)

    return app


def check_limits(settings: Settings, payload: ParseRequest) -> None:
    if len(payload.catalog) > settings.max_catalog:
        raise HTTPException(status_code=400, detail="目录超过上限")
    if len(payload.excludeProductIds) > settings.max_exclude:
        raise HTTPException(status_code=400, detail="排除商品超过上限")
    if len(payload.text) > settings.max_text_length:
        raise HTTPException(status_code=400, detail="文本超过上限")


def _digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:8]


def _locations(error: RequestValidationError) -> str:
    return ",".join(".".join(str(part) for part in item.get("loc", ())) for item in error.errors())


app = create_app(Settings.from_env())
