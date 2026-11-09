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
        self._stage_boards: dict[str, dict] = {}

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
        self._drop_stage_boards(session["id"])
        return new_name

    def _drop_stage_boards(self, session_id: str) -> None:
        prefix = f"{session_id}:"
        for key in [key for key in self._stage_boards if key.startswith(prefix)]:
            self._stage_boards.pop(key, None)

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
            title="🛡️ Toxido Anti Cheat",
            description=(
                "**Oturum başlatıldı**\n"
                "Başlatma adımları tek mesajda güncellenecek."
            ),
            color=0x2ECC71,
            timestamp=datetime.now(timezone.utc),
        )
        embed.add_field(name="👤 Oyuncu", value=f"`{session['mc_name']}`", inline=True)
        embed.add_field(
            name="🧩 Sürüm", value=f"`{session.get('mc_version') or '?'}`", inline=True
        )
        embed.add_field(
            name="🤖 Ajan", value=f"`{session.get('agent_version') or '?'}`", inline=True
        )
        embed.add_field(
            name="🆔 UUID", value=f"`{session.get('mc_uuid') or '?'}`", inline=False
        )
        embed.add_field(
            name="💻 Cihaz", value=f"`{session.get('device_id') or '?'}`", inline=False
        )
        embed.set_footer(text=f"Toxido AC • Oturum: {session['id']}")
        return embed

    @staticmethod
    def _end_embed(session: dict, reason: str, new_name: str) -> discord.Embed:
        embed = discord.Embed(
            title="🔒 Oturum Kapandı",
            description="**Toxido Anti Cheat oturumu sona erdi.**",
            color=0xE74C3C,
            timestamp=datetime.now(timezone.utc),
        )
        embed.add_field(name="👤 Oyuncu", value=f"`{session['mc_name']}`", inline=True)
        embed.add_field(name="📌 Sebep", value=f"`{reason}`", inline=True)
        embed.add_field(name="📦 Arşiv", value=f"`{new_name}`", inline=True)
        embed.set_footer(text=f"Toxido AC • Oturum: {session['id']}")
        return embed

    async def send_event_alert(
        self, session: dict, event_type: str, payload: dict
    ) -> None:
        if event_type not in {"injection", "triggerbot", "stage"}:
            return
        if not self.enabled:
            log.info("[DRY-RUN] %s uyarisi: %s", event_type, payload)
            return

        if event_type == "stage":
            await self._update_stage_board(session, payload)
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

    async def _update_stage_board(self, session: dict, payload: dict) -> None:
        guild = self.bot.get_guild(self.settings.guild_id)  # type: ignore[union-attr]
        if guild is None:
            log.warning("Discord sunucusu bulunamadi: %s", self.settings.guild_id)
            return

        channel_id = session.get("channel_id")
        channel = guild.get_channel(channel_id) if channel_id else None
        if channel is None:
            log.warning("Asama kanali bulunamadi: %s", channel_id)
            return

        run_id = str(payload.get("run_id") or "na")
        key = f"{session['id']}:{run_id}"
        board = self._stage_boards.get(key)
        if board is None:
            board = {"message": None, "stages": {}, "total": 4, "latest": {}}
            self._stage_boards[key] = board

        try:
            index = int(payload.get("stage_index") or 1)
        except (TypeError, ValueError):
            index = 1
        try:
            total = int(payload.get("stage_total") or 4)
        except (TypeError, ValueError):
            total = 4

        board["stages"][index] = dict(payload)
        board["total"] = max(total, index)
        board["latest"].update(payload)

        embed = self._stage_board_embed(session, board)
        message = board.get("message")
        if message is None:
            board["message"] = await channel.send(embed=embed)
            return

        try:
            await message.edit(embed=embed)
        except discord.NotFound:
            # Kanalda mesaj silinmisse tek bir yeni mesaj olustur.
            board["message"] = await channel.send(embed=embed)
        except discord.HTTPException as exc:
            log.warning("Asama mesaji duzenlenemedi: %s", exc)

    @staticmethod
    def _stage_board_embed(session: dict, board: dict) -> discord.Embed:
        total = max(int(board.get("total") or 4), 4)
        stages = board.get("stages", {})
        latest = board.get("latest", {})
        titles = {
            1: "Ajan baglandi",
            2: "Packet hook kurulumu",
            3: "Packet hook kuruldu",
            4: "TriggerBot hazir",
        }
        raw_statuses = []
        for index in range(1, total + 1):
            item = stages.get(index, {})
            raw_statuses.append(str(item.get("status") or "pending").strip().lower())

        # Asamalar sirali ilerler. Sonraki bir asama aktifse onceki
        # bekleyen satirlar da tamamlanmis sayilir; aksi halde 4/4 yerine
        # 3/4 gorunur.
        max_active = max(
            (index for index, status in enumerate(raw_statuses, start=1)
             if status == "active"),
            default=0,
        )

        lines = []
        statuses = []
        completed = 0
        pending_titles = {
            1: "Ajan bekleniyor",
            2: "Packet hook kurulumu",
            3: "Packet hook kurulumu",
            4: "TriggerBot bekleniyor",
        }
        for index in range(1, total + 1):
            item = stages.get(index, {})
            status = raw_statuses[index - 1]
            promoted = False
            if index <= max_active and status in {"pending", "waiting"}:
                status = "active"
                promoted = True
            if item.get("title"):
                title = str(item["title"])
            elif status == "pending":
                title = pending_titles.get(index, f"Asama {index}")
            else:
                title = titles.get(index, f"Asama {index}")
            stage_ts = item.get("client_ts")
            if promoted and stage_ts is None:
                for candidate in range(index + 1, total + 1):
                    if raw_statuses[candidate - 1] == "active":
                        stage_ts = stages.get(candidate, {}).get("client_ts")
                        break
            seconds = _stage_seconds(stage_ts)
            marker = {
                "active": "✅",
                "waiting": "⏳",
                "failed": "❌",
                "pending": "⚪",
            }.get(status, "⚪")
            time_text = f" • <t:{seconds}:T>" if seconds is not None else ""
            lines.append(f"{marker} **{index}/{total}**  {title}{time_text}")
            statuses.append(status)
            if status == "active":
                completed += 1

        if "failed" in statuses:
            color = 0xE74C3C
        elif "waiting" in statuses or 0 < completed < total:
            color = 0xE67E22
        elif completed == total:
            color = 0x2ECC71
        else:
            color = 0x95A5A6

        bar = "".join(
            {
                "active": "🟩",
                "waiting": "🟨",
                "failed": "🟥",
            }.get(status, "⬜")
            for status in statuses
        )
        embed = discord.Embed(
            title="🛡️ Toxido Anti Cheat • Başlatma",
            description=(
                "**Başlatma durumu**\n\n"
                + "\n".join(lines)
                + f"\n\n{bar}  `{completed}/{total}`"
            ),
            color=color,
            timestamp=datetime.now(timezone.utc),
        )
        embed.add_field(name="👤 Oyuncu", value=f"`{session['mc_name']}`", inline=True)
        embed.add_field(
            name="🧩 Sürüm",
            value=f"`{session.get('mc_version') or '?'}`",
            inline=True,
        )
        embed.add_field(
            name="🧱 Loader",
            value=f"`{latest.get('loader') or '?'}`",
            inline=True,
        )
        embed.add_field(
            name="🤖 Ajan",
            value=f"`{session.get('agent_version') or '?'}`",
            inline=True,
        )
        embed.add_field(
            name="🪝 Hook",
            value=f"`{latest.get('hook') or '-'}`",
            inline=True,
        )
        embed.add_field(
            name="📡 Packet",
            value=f"`{latest.get('packet') or 'waiting'}`",
            inline=True,
        )
        embed.add_field(
            name="🖱️ Input",
            value=f"`{latest.get('input') or 'starting'}`",
            inline=True,
        )
        embed.add_field(
            name="🎯 TriggerBot",
            value=f"`{latest.get('triggerbot') or 'off'}`",
            inline=True,
        )
        reason = latest.get("reason")
        if reason:
            embed.add_field(
                name="ℹ️ Sebep", value=f"`{_clip(str(reason), 200)}`", inline=True
            )
        embed.set_footer(
            text=f"Toxido AC • Oturum: {session['id']} • Son güncelleme"
        )
        return embed

    @staticmethod
    def _event_embed(
        session: dict, event_type: str, payload: dict
    ) -> discord.Embed:
        if event_type == "injection":
            embed = discord.Embed(
                title="⚠️ Şüpheli Enjeksiyon",
                description="**Yüksek güvenli enjeksiyon bulgusu**",
                color=0xE67E22,
                timestamp=datetime.now(timezone.utc),
            )
            embed.add_field(
                name="👤 Oyuncu", value=f"`{session['mc_name']}`", inline=True
            )
            embed.add_field(
                name="🧪 Tür", value=f"`{payload.get('kind', '?')}`", inline=True
            )
            embed.add_field(
                name="📊 Güven",
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
                title="🚨 TriggerBot Tespiti",
                description="**Fiziksel olmayan attack davranışı**",
                color=0xE74C3C,
                timestamp=datetime.now(timezone.utc),
            )
            embed.add_field(
                name="👤 Oyuncu", value=f"`{session['mc_name']}`", inline=True
            )
            embed.add_field(
                name="⚖️ Sonuç",
                value=f"`{payload.get('verdict', '?')}`",
                inline=True,
            )
            embed.add_field(
                name="🎯 Hedef",
                value=f"`{payload.get('target_name') or payload.get('target_type') or '?'}`",
                inline=True,
            )
            embed.add_field(
                name="🖱️ Fiziksel tık",
                value=f"`{payload.get('physical_click', False)}`",
                inline=True,
            )
            embed.add_field(
                name="💉 Injected tık",
                value=f"`{payload.get('injected_click', False)}`",
                inline=True,
            )
            embed.add_field(
                name="⏱️ Tık yaşı",
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
