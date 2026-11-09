from __future__ import annotations

import asyncio
import hmac
import logging
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request

from .channels import ChannelManager, sanitize_channel_name
from .db import Database, utcnow_iso
from .models import (
    EventRequest,
    HeartbeatRequest,
    SessionEndRequest,
    SessionStartRequest,
    SessionStartResponse,
)
from .security import new_session_token, token_hash, verify_hmac

log = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/session",
    tags=["session"],
    dependencies=[Depends(verify_hmac)],
)

# Ayni oyuncu icin es zamanli iki /start cagrisi iki kanal acmasin.
_start_lock = asyncio.Lock()


def _db(request: Request) -> Database:
    return request.app.state.db


def _channels(request: Request) -> ChannelManager:
    return request.app.state.channels


def _alerts(request: Request):
    return request.app.state.alerts


_CONFIDENCE_RANK = {"low": 0, "medium": 1, "high": 2}


def _should_enqueue_alert(
    request: Request, event_type: str, payload: dict
) -> tuple[bool, str]:
    """Discord'a gidecek event'leri tur ve guven seviyesine gore filtrele.

    TriggerBot her zaman gonderilir. Enjeksiyon bulgulari varsayilan olarak
    yalnizca yuksek guvenli ve bilinen turlerde Discord'a gider; boylece
    modlu istemcilerdeki sinif/path yanlis pozitifleri kanali doldurmaz.
    """
    if event_type in {"triggerbot", "stage", "log"}:
        return True, ""

    settings = request.app.state.settings
    if event_type != "injection":
        return False, f"type:{event_type}"
    if not settings.injection_alerts:
        return False, "disabled"

    kind = str(payload.get("kind") or "").strip().lower()
    if kind not in settings.injection_alert_kinds:
        return False, f"kind:{kind or '-'}"

    confidence = str(payload.get("confidence") or "").strip().lower()
    minimum = settings.injection_min_confidence
    if _CONFIDENCE_RANK.get(confidence, -1) < _CONFIDENCE_RANK.get(minimum, 2):
        return False, f"confidence:{confidence or '-'}"
    return True, ""


async def _authenticate(request: Request, session_id: str):
    database = _db(request)
    row = await database.get_session(session_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Oturum bulunamadi")

    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Oturum token'i gerekli")

    token = header.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token_hash(token), row["token_hash"]):
        raise HTTPException(status_code=401, detail="Gecersiz oturum token'i")

    return row


async def _archive_duplicate(request: Request, duplicate) -> None:
    database = _db(request)
    won = await database.finish_if_active(
        duplicate["id"], "ended", "duplicate", utcnow_iso()
    )
    if not won or not duplicate["channel_id"]:
        return
    try:
        log_index = await database.next_log_index(duplicate["base_name"])
        name = await _channels(request).archive_session_channel(
            dict(duplicate), log_index, "duplicate"
        )
        await database.set_log_index(duplicate["id"], log_index, name)
    except Exception:
        log.exception("Yinelenen kanal arsivlenemedi: %s", duplicate["id"])


@router.post("/start", response_model=SessionStartResponse)
async def start_session(payload: SessionStartRequest, request: Request) -> SessionStartResponse:
    database = _db(request)
    now = utcnow_iso()
    base_name = sanitize_channel_name(payload.mc_name)

    async with _start_lock:
        active = await database.active_sessions_for_name(payload.mc_name)
        if active:
            # Ayni oyuncunun onceki oturumu hala aciksa yeni kanal acma;
            # en yeni oturumu devral, daha eski kopyalari Log'a tasi.
            keep = active[0]
            for duplicate in active[1:]:
                await _archive_duplicate(request, duplicate)

            token = new_session_token()
            await database.reuse_session(
                keep["id"],
                token_hash(token),
                {
                    "mc_uuid": payload.mc_uuid,
                    "mc_version": payload.mc_version,
                    "agent_version": payload.agent_version,
                    "device_id": payload.device_id,
                },
                now,
            )
            row = await database.get_session(keep["id"])
            assert row is not None
            session = dict(row)
            channel_id = session.get("channel_id")

            if not channel_id:
                try:
                    channel_id, channel_name = await _channels(
                        request
                    ).create_session_channel(session)
                except Exception as exc:
                    log.exception("Discord kanali olusturulamadi")
                    await database.set_error(keep["id"], str(exc))
                    await database.finish_if_active(
                        keep["id"], "error", "channel_error", utcnow_iso()
                    )
                    raise HTTPException(
                        status_code=502,
                        detail=f"Discord kanali olusturulamadi: {exc}",
                    ) from exc
                await database.set_channel(keep["id"], channel_id, channel_name)

            log.info(
                "Oturum yeniden kullanildi: %s (%s) kanal=%s",
                keep["id"],
                payload.mc_name,
                channel_id,
            )
            return SessionStartResponse(
                session_id=keep["id"],
                token=token,
                heartbeat_interval_ms=5000,
                channel_id=channel_id,
            )

        session_id = uuid4().hex
        token = new_session_token()

        await database.create_session(
            {
                "id": session_id,
                "token_hash": token_hash(token),
                "mc_name": payload.mc_name,
                "base_name": base_name,
                "mc_uuid": payload.mc_uuid,
                "mc_version": payload.mc_version,
                "agent_version": payload.agent_version,
                "device_id": payload.device_id,
                "started_at": payload.started_at or now,
                "last_seen_at": now,
            }
        )

        row = await database.get_session(session_id)
        assert row is not None
        session = dict(row)

        try:
            channel_id, channel_name = await _channels(request).create_session_channel(
                session
            )
        except Exception as exc:
            log.exception("Discord kanali olusturulamadi")
            await database.set_error(session_id, str(exc))
            await database.finish_if_active(
                session_id, "error", "channel_error", utcnow_iso()
            )
            raise HTTPException(
                status_code=502, detail=f"Discord kanali olusturulamadi: {exc}"
            ) from exc

        await database.set_channel(session_id, channel_id, channel_name)
        log.info(
            "Oturum basladi: %s (%s) kanal=%s", session_id, payload.mc_name, channel_id
        )

        return SessionStartResponse(
            session_id=session_id,
            token=token,
            heartbeat_interval_ms=5000,
            channel_id=channel_id,
        )


@router.post("/heartbeat")
async def heartbeat(payload: HeartbeatRequest, request: Request) -> dict:
    row = await _authenticate(request, payload.session_id)
    if row["status"] != "active":
        raise HTTPException(status_code=409, detail="Oturum aktif degil")
    await _db(request).touch(payload.session_id, utcnow_iso())
    return {"ok": True}


@router.post("/end")
async def end_session(payload: SessionEndRequest, request: Request) -> dict:
    row = await _authenticate(request, payload.session_id)
    database = _db(request)

    if row["status"] != "active":
        return {"ok": True, "already_closed": True}

    won = await database.finish_if_active(
        payload.session_id, "ended", payload.reason, utcnow_iso()
    )
    if not won:
        return {"ok": True, "already_closed": True}

    log_index = await database.next_log_index(row["base_name"])
    try:
        name = await _channels(request).archive_session_channel(
            dict(row), log_index, payload.reason
        )
        await database.set_log_index(payload.session_id, log_index, name)
    except Exception:
        log.exception("Kanal arsivlenemedi: %s", payload.session_id)

    log.info(
        "Oturum kapandi: %s sebep=%s log=%s-%s",
        payload.session_id,
        payload.reason,
        row["base_name"],
        log_index,
    )
    return {"ok": True, "log_index": log_index}


@router.post("/event")
async def session_event(payload: EventRequest, request: Request) -> dict:
    row = await _authenticate(request, payload.session_id)
    if row["status"] != "active":
        raise HTTPException(status_code=409, detail="Oturum aktif degil")

    database = _db(request)
    # Event trafigi de istemcinin canli oldugunu kanitlar. Bu guncelleme
    # olmadan yogun event akisi sirasinda oturum yanlislikla timeout'a duser.
    await database.touch(payload.session_id, utcnow_iso())

    inserted = await database.record_event(
        payload.session_id, payload.type, payload.payload, payload.event_key
    )
    queued = False
    filtered = False
    if inserted and payload.type in {"injection", "triggerbot", "stage", "log"}:
        event_payload = payload.payload or {}
        send, reason = _should_enqueue_alert(request, payload.type, event_payload)
        if send:
            # Discord rate limit'i /event yanitini bekletmesin; embed arka
            # planda gonderilir. Olay kaydi her durumda korunur.
            queued = _alerts(request).enqueue(
                dict(row), payload.type, event_payload
            )
        else:
            filtered = True
            _alerts(request).record_filtered(reason)
    return {
        "ok": True,
        "recorded": inserted,
        "alert_queued": queued,
        "alert_filtered": filtered,
    }
