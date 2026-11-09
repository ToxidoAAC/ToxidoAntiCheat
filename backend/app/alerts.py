from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

from .channels import ChannelManager

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class AlertItem:
    session: dict
    event_type: str
    payload: dict


class EventAlertQueue:
    """Discord uyarilarini istek yolundan cikarip sirayla gonderir.

    Discord kanal basina rate limit uyguladigi icin embed gonderimi saniyeler
    surebilir. Bu kuyruk /event yanitinin bu sureyi beklemesini engeller.
    """

    def __init__(self, channels: ChannelManager, maxsize: int = 500) -> None:
        self.channels = channels
        self.maxsize = maxsize
        self._queue: asyncio.Queue[AlertItem] = asyncio.Queue(maxsize=maxsize)
        self._worker: asyncio.Task[None] | None = None
        self._dropped = 0

    @property
    def pending(self) -> int:
        return self._queue.qsize()

    @property
    def dropped(self) -> int:
        return self._dropped

    def start(self) -> None:
        if self._worker is not None:
            return
        self._worker = asyncio.create_task(
            self._run(), name="toxido-event-alerts"
        )
        log.info("Discord uyari kuyrugu basladi (max=%s)", self.maxsize)

    def enqueue(self, session: dict, event_type: str, payload: dict) -> bool:
        try:
            self._queue.put_nowait(
                AlertItem(session=dict(session), event_type=event_type, payload=dict(payload))
            )
            return True
        except asyncio.QueueFull:
            self._dropped += 1
            log.warning(
                "Discord uyari kuyrugu dolu; olay atlandi: %s (toplam=%s)",
                event_type,
                self._dropped,
            )
            return False

    async def _run(self) -> None:
        while True:
            item = await self._queue.get()
            try:
                await self.channels.send_event_alert(
                    item.session, item.event_type, item.payload
                )
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Discord uyarisi gonderilemedi: %s", item.event_type)
            finally:
                self._queue.task_done()

    async def stop(self) -> None:
        worker = self._worker
        self._worker = None
        if worker is None:
            return
        worker.cancel()
        try:
            await worker
        except asyncio.CancelledError:
            pass
        log.info(
            "Discord uyari kuyrugu durdu (bekleyen=%s, atilan=%s)",
            self.pending,
            self._dropped,
        )
