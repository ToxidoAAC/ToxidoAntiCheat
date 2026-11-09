from __future__ import annotations

import os
import sqlite3
import time
from pathlib import Path

from smoke_test import json_bytes, post

BASE = os.getenv("AC_API_URL", "http://127.0.0.1:18082").rstrip("/")
DB = Path(__file__).resolve().parent / "data" / "dedupe-test.db"
name = f"dedupe{int(time.time())}"

payload = {
    "mc_name": name,
    "mc_uuid": "00000000-0000-0000-0000-000000000099",
    "mc_version": "1.20.1",
    "agent_version": "0.1.0",
    "device_id": "dedupe-test-device",
}

status, first = post("/api/v1/session/start", json_bytes(payload))
print("start1", status, first)
assert status == 200, first

status, second = post("/api/v1/session/start", json_bytes(payload))
print("start2", status, second)
assert status == 200, second
assert second["session_id"] == first["session_id"], "session was not reused"
assert second["channel_id"] == first["channel_id"], "channel was not reused"

status, old_heartbeat = post(
    "/api/v1/session/heartbeat",
    json_bytes({"session_id": first["session_id"]}),
    first["token"],
)
print("old heartbeat", status, old_heartbeat)
assert status == 401, old_heartbeat

status, heartbeat = post(
    "/api/v1/session/heartbeat",
    json_bytes({"session_id": first["session_id"]}),
    second["token"],
)
print("new heartbeat", status, heartbeat)
assert status == 200, heartbeat

status, ended = post(
    "/api/v1/session/end",
    json_bytes({"session_id": first["session_id"], "reason": "game_closed"}),
    second["token"],
)
print("end", status, ended)
assert status == 200, ended

conn = sqlite3.connect(DB)
rows = conn.execute(
    "select id, status, channel_id, channel_name from sessions where mc_name = ?",
    (name,),
).fetchall()
conn.close()
print("db rows", rows)
assert len(rows) == 1, rows
assert rows[0][1] == "ended", rows
print("DEDUPE_OK")
