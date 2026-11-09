from __future__ import annotations

import hashlib
import hmac
import secrets
import time

from fastapi import Depends, HTTPException, Request

from .config import Settings, get_settings

# nonce -> timestamp(ms). Kucuk bir bellek ici replay korumasi.
_seen_nonces: dict[str, int] = {}


def new_session_token() -> str:
    return secrets.token_urlsafe(32)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _cleanup_nonces(now_ms: int) -> None:
    cutoff = now_ms - 600_000
    for nonce, seen_at in list(_seen_nonces.items()):
        if seen_at < cutoff:
            del _seen_nonces[nonce]


async def verify_hmac(
    request: Request, settings: Settings = Depends(get_settings)
) -> None:
    body = await request.body()

    key = request.headers.get("X-AC-Key", "").strip()
    timestamp = request.headers.get("X-AC-Timestamp", "").strip()
    nonce = request.headers.get("X-AC-Nonce", "").strip()
    signature = request.headers.get("X-AC-Signature", "").strip()

    if not all((key, timestamp, nonce, signature)):
        raise HTTPException(status_code=401, detail="Imza basliklari eksik")

    if not hmac.compare_digest(key, settings.api_key):
        raise HTTPException(status_code=401, detail="Gecersiz API anahtari")

    try:
        ts_ms = int(timestamp)
    except ValueError:
        raise HTTPException(status_code=401, detail="Gecersiz timestamp")

    now_ms = int(time.time() * 1000)
    if abs(now_ms - ts_ms) > 300_000:
        raise HTTPException(status_code=401, detail="Timestamp suresi dolmus")

    _cleanup_nonces(now_ms)
    if nonce in _seen_nonces:
        raise HTTPException(status_code=401, detail="Replay tespit edildi")
    _seen_nonces[nonce] = now_ms

    payload = f"{timestamp}.{nonce}.".encode("utf-8") + body
    expected = hmac.new(
        settings.api_secret.encode("utf-8"), payload, hashlib.sha256
    ).hexdigest()

    if not hmac.compare_digest(expected, signature.lower()):
        raise HTTPException(status_code=401, detail="Gecersiz imza")
