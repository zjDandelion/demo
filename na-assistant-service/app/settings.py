import os
from dataclasses import dataclass


def _int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    internal_token: str = ""
    base_url: str = ""
    model: str = ""
    api_key: str = ""
    timeout_ms: int = 4000
    max_body_bytes: int = 262144
    debug: bool = False
    max_catalog: int = 100
    max_exclude: int = 100
    max_text_length: int = 2000

    @property
    def model_configured(self) -> bool:
        return bool(self.base_url and self.model and self.api_key)

    @property
    def timeout_seconds(self) -> float:
        return max(self.timeout_ms, 100) / 1000.0

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            internal_token=os.getenv("ASSISTANT_INTERNAL_TOKEN", ""),
            base_url=os.getenv("ASSISTANT_BASE_URL", "").rstrip("/"),
            model=os.getenv("ASSISTANT_MODEL", ""),
            api_key=os.getenv("ASSISTANT_API_KEY", ""),
            timeout_ms=_int_env("ASSISTANT_TIMEOUT_MS", 4000),
            max_body_bytes=_int_env("ASSISTANT_MAX_BODY_BYTES", 262144),
            debug=os.getenv("ASSISTANT_DEBUG", "0") == "1",
            max_catalog=_int_env("ASSISTANT_MAX_CATALOG", 100),
            max_exclude=_int_env("ASSISTANT_MAX_EXCLUDE", 100),
            max_text_length=_int_env("ASSISTANT_MAX_TEXT_LENGTH", 2000),
        )
