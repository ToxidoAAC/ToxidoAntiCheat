"""Faz 1 backend smoke testi (harici bagimlilik kullanmaz).

Calistirma (backend ayaktayken):
    .venv\\Scripts\\python.exe smoke_test.py
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from uuid import uuid4

BASE_URL = os.getenv("AC_API_URL", "http://127.0.0.1:8000").rstrip("/")
API_KEY = os.getenv("API_KEY", "dev-key")
API_SECRET = os.getenv("API_SECRET", "dev-secret-change-me")
DB_PATH = Path(__file__).resolve().parent / "data" / "toxido.db"


def post(
    path: str,
    body: bytes,
    token: str | None = None,
    *,
    secret: str = API_SECRET,
    nonce: str | None = None,
    timestamp: str | None = None,
) -> tuple[int, dict]:
    ts = timestamp or str(int(time.time() * 1000))
    nc = nonce or str(uuid4())
    signature = hmac.new(
        secret.encode("utf-8"),
        f"{ts}.{nc}.".encode("utf-8") + body,
        hashlib.sha256,
    ).hexdigest()

    headers = {
        "Content-Type": "application/json",
        "X-AC-Key": API_KEY,
        "X-AC-Timestamp": ts,
        "X-AC-Nonce": nc,
        "X-AC-Signature": signature,
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = urllib.request.Request(
        f"{BASE_URL}{path}", data=body, headers=headers, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return error.code, payload


def json_bytes(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def main() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        if condition:
            print(f"  OK   {name}")
        else:
            print(f"  FAIL {name} {detail}")
            failures.append(name)

    mc_name = f"smoke{int(time.time())}{uuid4().hex[:4]}"
    base_name = mc_name.lower()
    print(f"1) Oturum baslatma ({mc_name})")
    started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    start_payload = json_bytes(
        {
            "mc_name": mc_name,
            "mc_uuid": "00000000-0000-0000-0000-000000000000",
            "mc_version": "1.20.1",
            "agent_version": "0.1.0",
            "device_id": "smoke-test",
            "started_at": started_at,
        }
    )
    status, first = post("/api/v1/session/start", start_payload)
    check("start 200", status == 200, f"status={status} body={first}")
    if status != 200:
        return 1
    session_id = first["session_id"]
    token = first["token"]
    check("session_id dolu", bool(session_id))
    check("token dolu", bool(token))
    check("kanal id (dry-run)", first.get("channel_id") is not None)

    print("2) Kalp atisi ve replay korumasi")
    heartbeat = json_bytes({"session_id": session_id})
    hb_nonce = str(uuid4())
    hb_ts = str(int(time.time() * 1000))
    status, payload = post(
        "/api/v1/session/heartbeat",
        heartbeat,
        token,
        nonce=hb_nonce,
        timestamp=hb_ts,
    )
    check("heartbeat 200", status == 200, f"status={status} body={payload}")

    status, payload = post(
        "/api/v1/session/heartbeat",
        heartbeat,
        token,
        nonce=hb_nonce,
        timestamp=hb_ts,
    )
    check("replay reddedildi (401)", status == 401, f"status={status} body={payload}")

    status, payload = post(
        "/api/v1/session/heartbeat",
        heartbeat,
        token,
        secret="wrong-secret",
    )
    check("yanlis imza reddedildi (401)", status == 401, f"status={status} body={payload}")

    print("3) Oturum kapatma")
    end_payload = json_bytes({"session_id": session_id, "reason": "smoke_test"})
    status, payload = post("/api/v1/session/end", end_payload, token)
    check("end 200", status == 200, f"status={status} body={payload}")
    check("log_index=1", payload.get("log_index") == 1, str(payload))

    status, payload = post("/api/v1/session/end", end_payload, token)
    check("ikinci end idempotent", status == 200 and payload.get("already_closed") is True, str(payload))

    print("4) Ayni oyuncunun ikinci oturumu -> -2")
    status, second = post("/api/v1/session/start", start_payload)
    check("ikinci start 200", status == 200, f"status={status} body={second}")
    if status == 200:
        second_end = json_bytes({"session_id": second["session_id"], "reason": "smoke_test_2"})
        status, payload = post("/api/v1/session/end", second_end, second["token"])
        check("ikinci end 200", status == 200, f"status={status} body={payload}")
        check("log_index=2", payload.get("log_index") == 2, str(payload))

    print("5) Veritabani kontrolu")
    check("db mevcut", DB_PATH.exists(), str(DB_PATH))
    if DB_PATH.exists():
        with sqlite3.connect(DB_PATH) as connection:
            rows = connection.execute(
                "SELECT base_name, status, end_reason, log_index, channel_name "
                "FROM sessions WHERE mc_name = ? ORDER BY started_at",
                (mc_name,),
            ).fetchall()
        check("iki kayit", len(rows) == 2, str(rows))
        for index, row in enumerate(rows, start=1):
            check(f"kayit {index} ended", row[1] == "ended", str(row))
            check(f"kayit {index} log_index", row[3] == index, str(row))
            check(
                f"kayit {index} kanal adi",
                row[4] == f"{base_name}-{index}",
                str(row),
            )

    print()
    if failures:
        print(f"BASARISIZ: {len(failures)} kontrol -> {', '.join(failures)}")
        return 1
    print("TUM KONTROLLER BASARILI")
    return 0


if __name__ == "__main__":
    sys.exit(main())
