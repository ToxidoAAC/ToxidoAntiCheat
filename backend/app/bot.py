from __future__ import annotations

import asyncio
import logging

import discord
from discord.ext import commands

from .config import Settings

log = logging.getLogger(__name__)


class ACBot(commands.Bot):
    def __init__(self) -> None:
        super().__init__(command_prefix="!", intents=discord.Intents.default())
        self.ready_event = asyncio.Event()

    async def on_ready(self) -> None:
        user = self.user
        log.info(
            "Discord botu hazir: %s (id=%s), %d sunucu",
            user,
            user.id if user else "?",
            len(self.guilds),
        )
        self.ready_event.set()


async def start_bot(settings: Settings) -> tuple[ACBot | None, asyncio.Task | None]:
    if settings.dry_run:
        log.warning("DRY_RUN acik; Discord botu baslatilmadi.")
        return None, None

    if not settings.discord_token:
        log.warning("DISCORD_TOKEN bos; Discord botu baslatilmadi.")
        return None, None

    bot = ACBot()
    task = asyncio.create_task(bot.start(settings.discord_token), name="discord-bot")

    try:
        await asyncio.wait_for(bot.ready_event.wait(), timeout=30)
    except Exception:
        log.exception("Discord botu baslatilamadi")
        task.cancel()
        return None, None

    return bot, task
