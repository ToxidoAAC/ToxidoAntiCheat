from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import aiosqlite


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL,
    mc_name TEXT NOT NULL,
    base_name TEXT NOT NULL,
    mc_uuid TEXT,
    mc_version TEXT,
    agent_version TEXT,
    device_id TEXT,
    status TEXT NOT NULL,
    channel_id INTEGER,
    channel_name TEXT,
    log_index INTEGER,
    started_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    ended_at TEXT,
    end_reason TEXT,
    error TEXT
);

CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);
CREATE INDEX IF NOT EXISTS idx_sessions_base ON sessions(base_name);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    type TEXT NOT NULL,
    payload TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY(session_id) REFERENCES sessions(id)
);
"""


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.conn: aiosqlite.Connection | None = None

    async def connect(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = await aiosqlite.connect(self.path)
        self.conn.row_factory = aiosqlite.Row
        await self.conn.execute("PRAGMA journal_mode=WAL")
        await self.conn.execute("PRAGMA foreign_keys=ON")
        await self.conn.executescript(SCHEMA)
        await self._migrate()
        await self.conn.commit()

    async def _migrate(self) -> None:
        assert self.conn is not None
        async with self.conn.execute("PRAGMA table_info(events)") as cursor:
            columns = {row["name"] for row in await cursor.fetchall()}
        if "event_key" not in columns:
            await self.conn.execute("ALTER TABLE events ADD COLUMN event_key TEXT")
        await self.conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_events_dedupe "
            "ON events(session_id, event_key) WHERE event_key IS NOT NULL"
        )
        async with self.conn.execute("PRAGMA table_info(sessions)") as cursor:
            session_columns = {row["name"] for row in await cursor.fetchall()}
        if "restart_requested" not in session_columns:
            await self.conn.execute(
                "ALTER TABLE sessions ADD COLUMN restart_requested "
                "INTEGER NOT NULL DEFAULT 0"
            )

    async def request_restart(self, session_id: str) -> bool:
        assert self.conn is not None
        cursor = await self.conn.execute(
            "UPDATE sessions SET restart_requested = 1 "
            "WHERE id = ? AND status = 'active'",
            (session_id,),
        )
        await self.conn.commit()
        return cursor.rowcount > 0

    async def take_restart(self, session_id: str) -> bool:
        """Bekleyen /restart istegini bir kez tuketir."""
        assert self.conn is not None
        cursor = await self.conn.execute(
            "UPDATE sessions SET restart_requested = 0 "
            "WHERE id = ? AND restart_requested = 1",
            (session_id,),
        )
        await self.conn.commit()
        return cursor.rowcount > 0

    async def close(self) -> None:
        if self.conn is not None:
            await self.conn.close()
            self.conn = None

    async def create_session(self, data: dict[str, Any]) -> None:
        assert self.conn is not None
        await self.conn.execute(
            """
            INSERT INTO sessions
                (id, token_hash, mc_name, base_name, mc_uuid, mc_version,
                 agent_version, device_id, status, started_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)
            """,
            (
                data["id"],
                data["token_hash"],
                data["mc_name"],
                data["base_name"],
                data.get("mc_uuid"),
                data.get("mc_version"),
                data.get("agent_version"),
                data.get("device_id"),
                data["started_at"],
                data["last_seen_at"],
            ),
        )
        await self.conn.commit()

    async def get_session(self, session_id: str) -> aiosqlite.Row | None:
        assert self.conn is not None
        async with self.conn.execute(
            "SELECT * FROM sessions WHERE id = ?", (session_id,)
        ) as cursor:
            return await cursor.fetchone()

    async def active_sessions(self) -> list[aiosqlite.Row]:
        assert self.conn is not None
        async with self.conn.execute(
            "SELECT * FROM sessions WHERE status = 'active'"
        ) as cursor:
            return list(await cursor.fetchall())

    async def active_sessions_for_name(self, mc_name: str) -> list[aiosqlite.Row]:
        assert self.conn is not None
        async with self.conn.execute(
            """
            SELECT * FROM sessions
             WHERE status = 'active' AND lower(mc_name) = lower(?)
             ORDER BY started_at DESC
            """,
            (mc_name,),
        ) as cursor:
            return list(await cursor.fetchall())

    async def reuse_session(
        self,
        session_id: str,
        token_hash_value: str,
        data: dict[str, Any],
        when: str,
    ) -> None:
        assert self.conn is not None
        await self.conn.execute(
            """
            UPDATE sessions
               SET token_hash = ?, mc_uuid = ?, mc_version = ?,
                   agent_version = ?, device_id = ?, last_seen_at = ?
             WHERE id = ? AND status = 'active'
            """,
            (
                token_hash_value,
                data.get("mc_uuid"),
                data.get("mc_version"),
                data.get("agent_version"),
                data.get("device_id"),
                when,
                session_id,
            ),
        )
        await self.conn.commit()

    async def touch(self, session_id: str, when: str) -> None:
        assert self.conn is not None
        await self.conn.execute(
            "UPDATE sessions SET last_seen_at = ? WHERE id = ? AND status = 'active'",
            (when, session_id),
        )
        await self.conn.commit()

    async def finish_if_active(
        self, session_id: str, status: str, reason: str, when: str
    ) -> bool:
        assert self.conn is not None
        cursor = await self.conn.execute(
            """
            UPDATE sessions
               SET status = ?, end_reason = ?, ended_at = ?
             WHERE id = ? AND status = 'active'
            """,
            (status, reason, when, session_id),
        )
        await self.conn.commit()
        return cursor.rowcount == 1

    async def set_channel(
        self, session_id: str, channel_id: int, channel_name: str
    ) -> None:
        assert self.conn is not None
        await self.conn.execute(
            "UPDATE sessions SET channel_id = ?, channel_name = ? WHERE id = ?",
            (channel_id, channel_name, session_id),
        )
        await self.conn.commit()

    async def set_log_index(
        self, session_id: str, log_index: int, channel_name: str
    ) -> None:
        assert self.conn is not None
        await self.conn.execute(
            "UPDATE sessions SET log_index = ?, channel_name = ? WHERE id = ?",
            (log_index, channel_name, session_id),
        )
        await self.conn.commit()

    async def set_error(self, session_id: str, error: str) -> None:
        assert self.conn is not None
        await self.conn.execute(
            "UPDATE sessions SET error = ? WHERE id = ?", (error, session_id)
        )
        await self.conn.commit()

    async def next_log_index(self, base_name: str) -> int:
        assert self.conn is not None
        async with self.conn.execute(
            "SELECT COALESCE(MAX(log_index), 0) AS m FROM sessions WHERE base_name = ?",
            (base_name,),
        ) as cursor:
            row = await cursor.fetchone()
        return int(row["m"]) + 1

    async def record_event(
        self,
        session_id: str,
        event_type: str,
        payload: dict | None,
        event_key: str | None = None,
    ) -> bool:
        assert self.conn is not None
        cursor = await self.conn.execute(
            "INSERT OR IGNORE INTO events "
            "(session_id, type, payload, created_at, event_key) VALUES (?, ?, ?, ?, ?)",
            (
                session_id,
                event_type,
                json.dumps(payload or {}, ensure_ascii=False),
                utcnow_iso(),
                event_key,
            ),
        )
        await self.conn.commit()
        return cursor.rowcount == 1
