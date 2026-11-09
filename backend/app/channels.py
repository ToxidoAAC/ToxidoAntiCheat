from __future__ import annotations

import logging
import re
from datetime import datetime, timezone

import discord

from .config import Settings

log = logging.getLogger(__name__)


class ChannelError(Exception):
    pass


def sanitize_channel_name(name: str) -> str:
    value = name.strip().lower()
    value = re.sub(r"[^a-z0-9_-]+", "-", value)
    value = re.sub(r"-{2,}", "-", value).strip("-")
    return (value or "unknown")[:90]


class ChannelManager:
    def __init__(self, bot: discord.Client | None, settings: Settings) -> None:
        self.bot = bot
        self.settings = settings
        self._fake_id = 900_000_000_000_000_000

    @property
    def enabled(self) -> bool:
        return (
            not self.settings.dry_run
            and self.bot is not None
            and self.bot.is_ready()
        )

    def _next_fake_id(self) -> int:
        self._fake_id += 1
        return self._fake_id

    async def create_session_channel(self, session: dict) -> tuple[int, str]:
        base_name = session["base_name"]

        if not self.enabled:
            fake_id = self._next_fake_id()
            log.info("[DRY-RUN] Now/%s kanali acildi (id=%s)", base_name, fake_id)
            return fake_id, base_name

        guild = self.bot.get_guild(self.settings.guild_id)  # type: ignore[union-attr]
        if guild is None:
            raise ChannelError(f"Sunucu bulunamadi: {self.settings.guild_id}")

        category = guild.get_channel(self.settings.now_category_id)
        if not isinstance(category, discord.CategoryChannel):
            raise ChannelError(
                f"Now kategorisi bulunamadi: {self.settings.now_category_id}"
            )

        channel = await guild.create_text_channel(
            name=base_name,
            category=category,
            overwrites=self._overwrites(guild),
            topic=self._topic(session),
            reason="Toxido AC oturumu basladi",
        )
        await channel.send(embed=self._start_embed(session))
        return channel.id, channel.name

    async def archive_session_channel(
        self, session: dict, log_index: int, reason: str
    ) -> str:
        base_name = session["base_name"]
        new_name = f"{base_name}-{log_index}"

        if not self.enabled:
            log.info("[DRY-RUN] Log/%s olarak arsivlendi (sebep=%s)", new_name, reason)
            return new_name

        guild = self.bot.get_guild(self.settings.guild_id)  # type: ignore[union-attr]
        if guild is None:
            raise ChannelError(f"Sunucu bulunamadi: {self.settings.guild_id}")

        channel_id = session.get("channel_id")
        channel = guild.get_channel(channel_id) if channel_id else None
        if channel is None:
            raise ChannelError(f"Kanal bulunamadi: {channel_id}")

        category = guild.get_channel(self.settings.log_category_id)
        if not isinstance(category, discord.CategoryChannel):
            raise ChannelError(
                f"Log kategorisi bulunamadi: {self.settings.log_category_id}"
            )

        await channel.edit(
            name=new_name,
            category=category,
            reason=f"Toxido AC oturumu kapandi: {reason}",
        )
        await channel.send(embed=self._end_embed(session, reason, new_name))
        return new_name

    def _overwrites(self, guild: discord.Guild) -> dict:
        overwrites: dict = {
            guild.default_role: discord.PermissionOverwrite(view_channel=False),
            guild.me: discord.PermissionOverwrite(
                view_channel=True,
                send_messages=True,
                read_message_history=True,
                manage_channels=True,
            ),
        }
        if self.settings.staff_role_id:
            role = guild.get_role(self.settings.staff_role_id)
            if role is not None:
                overwrites[role] = discord.PermissionOverwrite(
                    view_channel=True,
                    send_messages=True,
                    read_message_history=True,
                )
        if self.settings.staff_user_id:
            overwrites[discord.Object(id=self.settings.staff_user_id)] = (
                discord.PermissionOverwrite(
                    view_channel=True,
                    send_messages=True,
                    read_message_history=True,
                )
            )
        return overwrites

    @staticmethod
    def _topic(session: dict) -> str:
        parts = [
            "Toxido AC",
            f"oyuncu={session['mc_name']}",
            f"uuid={session.get('mc_uuid') or '?'}",
            f"surum={session.get('mc_version') or '?'}",
            f"ajan={session.get('agent_version') or '?'}",
        ]
        return " | ".join(parts)[:1024]

    @staticmethod
    def _start_embed(session: dict) -> discord.Embed:
        embed = discord.Embed(
            title="Toxido Anti Cheat",
            description="Oturum basladi",
            color=0x2ECC71,
            timestamp=datetime.now(timezone.utc),
        )
        embed.add_field(name="Oyuncu", value=f"`{session['mc_name']}`", inline=True)
        embed.add_field(
            name="Surum", value=f"`{session.get('mc_version') or '?'}`", inline=True
        )
        embed.add_field(
            name="Ajan", value=f"`{session.get('agent_version') or '?'}`", inline=True
        )
        embed.add_field(
            name="UUID", value=f"`{session.get('mc_uuid') or '?'}`", inline=False
        )
        embed.add_field(
            name="Cihaz", value=f"`{session.get('device_id') or '?'}`", inline=False
        )
        embed.set_footer(text=f"Oturum: {session['id']}")
        return embed

    @staticmethod
    def _end_embed(session: dict, reason: str, new_name: str) -> discord.Embed:
        embed = discord.Embed(
            title="Toxido Anti Cheat",
            description="Oturum kapandi",
            color=0xE74C3C,
            timestamp=datetime.now(timezone.utc),
        )
        embed.add_field(name="Oyuncu", value=f"`{session['mc_name']}`", inline=True)
        embed.add_field(name="Sebep", value=f"`{reason}`", inline=True)
        embed.add_field(name="Arsiv", value=f"`{new_name}`", inline=True)
        embed.set_footer(text=f"Oturum: {session['id']}")
        return embed

    async def send_event_alert(
        self, session: dict, event_type: str, payload: dict
    ) -> None:
        if event_type not in {"injection", "triggerbot", "stage"}:
            return
        if not self.enabled:
            log.info("[DRY-RUN] %s uyarisi: %s", event_type, payload)
            return

        guild = self.bot.get_guild(self.settings.guild_id)  # type: ignore[union-attr]
        if guild is None:
            log.warning("Discord sunucusu bulunamadi: %s", self.settings.guild_id)
            return

        channel_id = session.get("channel_id")
        channel = guild.get_channel(channel_id) if channel_id else None
        if channel is None:
            log.warning("Uyari kanali bulunamadi: %s", channel_id)
            return

        await channel.send(embed=self._event_embed(session, event_type, payload))

    @staticmethod
    def _stage_embed(session: dict, payload: dict) -> discord.Embed:
        status = str(payload.get("status") or "?").strip().lower()
        if status == "active":
            color = 0x2ECC71
        elif status == "waiting":
            color = 0xE67E22
        else:
            color = 0xE74C3C

        index = payload.get("stage_index", "?")
        total = payload.get("stage_total", "?")
        title = payload.get("title") or "Asama"
        client_ts = payload.get("client_ts")
        embed = discord.Embed(
            title=f"Asama {index}/{total}: {title}",
            color=color,
            timestamp=_stage_datetime(client_ts),
        )
        embed.add_field(
            name="Oyuncu", value=f"`{session['mc_name']}`", inline=True
        )
        embed.add_field(
            name="Durum", value=f"`{_stage_label(status)}`", inline=True
        )
        seconds = _stage_seconds(client_ts)
        if seconds is not None:
            embed.add_field(
                name="Zaman",
                value=f"<t:{seconds}:T> • <t:{seconds}:f>",
                inline=False,
            )
        for key, label in (
            ("version", "Surum"),
            ("loader", "Loader"),
            ("hook", "Hook"),
            ("input", "Input"),
            ("triggerbot", "TriggerBot"),
            ("reason", "Sebep"),
        ):
            value = payload.get(key)
            if value is not None and str(value).strip() not in {"", "-"}:
                embed.add_field(
                    name=label, value=f"`{_clip(str(value), 200)}`", inline=True
                )
        embed.set_footer(text=f"Oturum: {session['id']}")
        return embed

    @staticmethod
    def _event_embed(
        session: dict, event_type: str, payload: dict
    ) -> discord.Embed:
        if event_type == "stage":
            return ChannelManager._stage_embed(session, payload)

        if event_type == "injection":
            embed = discord.Embed(
                title="Supheli Enjeksiyon Tespiti",
                color=0xE67E22,
                timestamp=datetime.now(timezone.utc),
            )
            embed.add_field(
                name="Oyuncu", value=f"`{session['mc_name']}`", inline=True
            )
            embed.add_field(
                name="Tur", value=f"`{payload.get('kind', '?')}`", inline=True
            )
            embed.add_field(
                name="Guven",
                value=f"`{payload.get('confidence', '?')}`",
                inline=True,
            )
            for key, label in (
                ("name", "Ad"),
                ("path", "Dosya"),
                ("sha256", "SHA-256"),
                ("signature", "Imza"),
                ("description", "Aciklama"),
                ("evidence", "Kanit"),
            ):
                value = payload.get(key)
                if value:
                    embed.add_field(
                        name=label, value=_clip(str(value)), inline=False
                    )
            exists = payload.get("exists")
            if exists is not None:
                embed.add_field(
                    name="Dosya durumu",
                    value=("`mevcut`" if exists else "`silinmis`"),
                    inline=True,
                )
        else:
            embed = discord.Embed(
                title="TriggerBot Tespiti",
                color=0xE74C3C,
                timestamp=datetime.now(timezone.utc),
            )
            embed.add_field(
                name="Oyuncu", value=f"`{session['mc_name']}`", inline=True
            )
            embed.add_field(
                name="Sonuc",
                value=f"`{payload.get('verdict', '?')}`",
                inline=True,
            )
            embed.add_field(
                name="Hedef",
                value=f"`{payload.get('target_name') or payload.get('target_type') or '?'}`",
                inline=True,
            )
            embed.add_field(
                name="Fiziksel tik",
                value=f"`{payload.get('physical_click', False)}`",
                inline=True,
            )
            embed.add_field(
                name="Injected tik",
                value=f"`{payload.get('injected_click', False)}`",
                inline=True,
            )
            embed.add_field(
                name="Tik yasi",
                value=f"`{payload.get('input_age_ms', '?')} ms`",
                inline=True,
            )
            for key, label in (
                ("hook", "Hook"),
                ("reason", "Sebep"),
                ("evidence", "Kanit"),
            ):
                value = payload.get(key)
                if value:
                    embed.add_field(
                        name=label, value=_clip(str(value)), inline=False
                    )

        embed.set_footer(text=f"Oturum: {session['id']}")
        return embed


def _stage_seconds(value) -> int | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number <= 0:
        return None
    if number > 10_000_000_000:
        number /= 1000.0
    return int(number)


def _stage_datetime(value) -> datetime:
    seconds = _stage_seconds(value)
    if seconds is None:
        return datetime.now(timezone.utc)
    try:
        return datetime.fromtimestamp(seconds, timezone.utc)
    except (OverflowError, OSError, ValueError):
        return datetime.now(timezone.utc)


def _stage_label(status: str) -> str:
    return {
        "active": "tamamlandi",
        "waiting": "bekliyor",
        "failed": "basarisiz",
    }.get(status, status or "?")


def _clip(value: str, limit: int = 1000) -> str:
    return value if len(value) <= limit else value[: limit - 3] + '...'
