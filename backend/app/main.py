from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse

from .alerts import EventAlertQueue
from .bot import start_bot
from .channels import ChannelManager
from .config import get_settings
from .db import Database, utcnow_iso
from .routes_sessions import router

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("toxido")


async def _timeout_loop(app: FastAPI) -> None:
    settings = app.state.settings
    database: Database = app.state.db
    channels: ChannelManager = app.state.channels

    while True:
        await asyncio.sleep(10)
        try:
            rows = await database.active_sessions()
            now = datetime.now(timezone.utc)
            for row in rows:
                last_seen = datetime.fromisoformat(row["last_seen_at"])
                if last_seen.tzinfo is None:
                    last_seen = last_seen.replace(tzinfo=timezone.utc)
                idle = (now - last_seen).total_seconds()
                if idle <= settings.heartbeat_timeout_seconds:
                    continue

                won = await database.finish_if_active(
                    row["id"], "lost", "heartbeat_timeout", utcnow_iso()
                )
                if not won:
                    continue

                log_index = await database.next_log_index(row["base_name"])
                try:
                    name = await channels.archive_session_channel(
                        dict(row), log_index, "heartbeat_timeout"
                    )
                    await database.set_log_index(row["id"], log_index, name)
                except Exception:
                    log.exception("Timeout arsivleme hatasi: %s", row["id"])

                log.warning(
                    "Oturum zaman asimi: %s (%s) %.0f sn",
                    row["id"],
                    row["mc_name"],
                    idle,
                )
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("Timeout dongusu hatasi")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    settings.validate()

    database = Database(settings.db_path)
    await database.connect()

    bot, bot_task = await start_bot(settings)
    channels = ChannelManager(bot, settings)
    alerts = EventAlertQueue(channels)
    alerts.start()

    app.state.settings = settings
    app.state.db = database
    app.state.bot = bot
    app.state.bot_task = bot_task
    app.state.channels = channels
    app.state.alerts = alerts

    timeout_task = asyncio.create_task(_timeout_loop(app), name="session-timeout")

    log.info(
        "Backend hazir. dry_run=%s db=%s bot=%s",
        settings.dry_run,
        settings.db_path,
        "on" if bot else "off",
    )

    try:
        yield
    finally:
        timeout_task.cancel()
        try:
            await timeout_task
        except asyncio.CancelledError:
            pass

        await alerts.stop()

        if bot is not None:
            await bot.close()
        if bot_task is not None:
            bot_task.cancel()
            try:
                await bot_task
            except (asyncio.CancelledError, Exception):
                pass

        await database.close()


app = FastAPI(title="Toxido Anti Cheat API", version="0.2.1", lifespan=lifespan)
app.include_router(router)


@app.get("/dl")
@app.get("/download/40x1d0AC.exe")
async def download_exe() -> FileResponse:
    path = app.state.settings.download_path
    if not path.is_file():
        raise HTTPException(
            status_code=404,
            detail="40x1d0AC.exe bulunamadi. Sunucuda static/40x1d0AC.exe yok.",
        )
    return FileResponse(
        path,
        media_type="application/octet-stream",
        filename="40x1d0AC.exe",
    )


@app.get("/health")
async def health() -> dict:
    state = app.state
    database: Database = state.db
    active = await database.active_sessions()
    bot = state.bot
    alerts: EventAlertQueue = state.alerts
    return {
        "ok": True,
        "version": app.version,
        "dry_run": state.settings.dry_run,
        "bot_ready": bool(bot is not None and bot.is_ready()),
        "active_sessions": len(active),
        "alert_queue": alerts.pending,
        "alert_dropped": alerts.dropped,
        "heartbeat_timeout_seconds": state.settings.heartbeat_timeout_seconds,
    }


if __name__ == "__main__":
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        timeout_keep_alive=65,
    )
