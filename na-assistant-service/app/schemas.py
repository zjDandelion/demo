from decimal import Decimal
from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class CatalogItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: int
    name: str = Field(min_length=1, max_length=200)
    description: Optional[str] = Field(default=None, max_length=500)
    category: Optional[str] = Field(default=None, max_length=100)
    tags: Optional[str] = Field(default=None, max_length=500)
    price: Decimal
    stock: int


class ParseRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str = Field(min_length=1, max_length=64)
    userId: Optional[int] = None
    text: str = Field(min_length=1, max_length=4000)
    excludeProductIds: List[int] = Field(default_factory=list)
    catalog: List[CatalogItem] = Field(default_factory=list)


class ParsedItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    productId: int
    quantity: int


class ParsedUnmatched(BaseModel):
    model_config = ConfigDict(extra="ignore")

    text: str = Field(default="", max_length=200)
    reason: str = Field(default="暂无此商品", max_length=200)


class ParsedQuestion(BaseModel):
    model_config = ConfigDict(extra="ignore")

    text: str = Field(default="", max_length=200)


class Usage(BaseModel):
    promptTokens: int = 0
    completionTokens: int = 0
    latencyMs: int = 0


class ModelOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    items: List[ParsedItem] = Field(default_factory=list)
    unmatched: List[ParsedUnmatched] = Field(default_factory=list)
    questions: List[ParsedQuestion] = Field(default_factory=list)


class ParseResponse(BaseModel):
    status: str
    reason: Optional[str] = None
    items: List[ParsedItem] = Field(default_factory=list)
    unmatched: List[ParsedUnmatched] = Field(default_factory=list)
    questions: List[ParsedQuestion] = Field(default_factory=list)
    usage: Usage = Field(default_factory=Usage)


def degraded(reason: str, latency_ms: int = 0) -> ParseResponse:
    return ParseResponse(status="DEGRADED", reason=reason, usage=Usage(latencyMs=latency_ms))
