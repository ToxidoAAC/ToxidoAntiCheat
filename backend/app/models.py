from __future__ import annotations

from pydantic import BaseModel, Field


class SessionStartRequest(BaseModel):
    mc_name: str = Field(min_length=1, max_length=32)
    mc_uuid: str | None = None
    mc_version: str | None = None
    agent_version: str | None = None
    device_id: str | None = None
    started_at: str | None = None


class SessionStartResponse(BaseModel):
    session_id: str
    token: str
    heartbeat_interval_ms: int = 5000
    channel_id: int | None = None


class HeartbeatRequest(BaseModel):
    session_id: str


class SessionEndRequest(BaseModel):
    session_id: str
    reason: str = "game_closed"


class EventRequest(BaseModel):
    session_id: str
    type: str
    event_key: str | None = None
    payload: dict | None = None
