from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable

import discord
from discord import app_commands
from discord.ext import commands

from .config import Settings

log = logging.getLogger(__name__)

RestartHandler = Callable[[], Awaitable[int]]


class ACBot(commands.Bot):
    def __init__(self, settings: Settings) -> None:
        super().__init__(command_prefix="!", intents=discord.Intents.default())
        self.settings = settings
        self.ready_event = asyncio.Event()
        self.restart_handler: RestartHandler | None = None

    async def setup_hook(self) -> None:
        self.tree.add_command(self._restart_command())
        try:
            if self.settings.guild_id:
                # Sunucuya ozel senkron aninda gorunur; global komutlar
                # Discord tarafinda saatlerce gecikmeli yayilir.
                guild = discord.Object(id=self.settings.guild_id)
                self.tree.copy_global_to(guild=guild)
                synced = await self.tree.sync(guild=guild)
            else:
                synced = await self.tree.sync()
            log.info("Slash komutlari senkronlandi: %d", len(synced))
            if not synced:
                log.warning(
                    "Hicbir slash komutu senkronlanmadi; Discord uygulama izinlerini kontrol edin."
                )
        except Exception:
            log.exception("Slash komutlari senkronlanamadi")

    def _restart_command(self) -> app_commands.Command:
        @app_commands.command(
            name="restart",
            description="Onceki panoyu birakip anti-cheat tespitini bastan baslatir.",
        )
        @app_commands.guild_only()
        async def restart(interaction: discord.Interaction) -> None:
            if not self._is_staff(interaction):
                await interaction.response.send_message(
                    "Bu komutu kullanma yetkin yok.", ephemeral=True
                )
                return

            handler = self.restart_handler
            if handler is None:
                await interaction.response.send_message(
                    "Yeniden baslatma servisi hazir degil.", ephemeral=True
                )
                return

            try:
                count = await handler()
            except Exception:
                log.exception("/restart istegi basarisiz")
                await interaction.response.send_message(
                    "Yeniden baslatma istegi gonderilemedi.", ephemeral=True
                )
                return

            if count:
                await interaction.response.send_message(
                    f"🔄 Yeniden baslatma istendi ({count} oturum). "
                    "Pano birkac saniye icinde sifirlanacak.",
                    ephemeral=True,
                )
            else:
                await interaction.response.send_message(
                    "Aktif oturum yok; once anti-cheat uygulamasini baslat.",
                    ephemeral=True,
                )

        return restart

    def _is_staff(self, interaction: discord.Interaction) -> bool:
        if self.settings.staff_user_id and interaction.user.id == self.settings.staff_user_id:
            return True
        role_id = self.settings.staff_role_id
        if not role_id:
            return False
        roles = getattr(interaction.user, "roles", ())
        return any(getattr(role, "id", 0) == role_id for role in roles)

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

    bot = ACBot(settings)
    task = asyncio.create_task(bot.start(settings.discord_token), name="discord-bot")

    try:
        await asyncio.wait_for(bot.ready_event.wait(), timeout=30)
    except Exception:
        log.exception("Discord botu baslatilamadi")
        task.cancel()
        return None, None

    return bot, task
