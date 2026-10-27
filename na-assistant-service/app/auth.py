import hmac
from typing import Optional

from fastapi import Header, HTTPException

from .settings import Settings


def require_token(settings: Settings):
    """Route-level dependency: attached per endpoint so no path prefix can bypass it."""

    def dependency(x_service_token: Optional[str] = Header(default=None, alias="X-Service-Token")) -> None:
        expected = settings.internal_token
        if not expected or not x_service_token or not hmac.compare_digest(expected, x_service_token):
            raise HTTPException(status_code=401, detail="unauthorized")

    return dependency
