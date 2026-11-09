"""Discord ayarlarini ve bot izinlerini calistirmadan once dogrular.

Kullanim (proje kokunden):
    .\\backend\\.venv\\Scripts\\python.exe .\\backend\\check_discord.py
"""

from __future__ import annotations

import asyncio
import sys

import discord

from app.config import get_settings


async def run() -> int:
    settings = get_settings()
    if settings.dry_run:
        print("DRY_RUN=1. Gercek test icin backend/.env icinde DRY_RUN=0 yapin.")
        return 1
    if not settings.discord_token:
        print("DISCORD_TOKEN bos.")
        return 1

    client = discord.Client(intents=discord.Intents.default())
    result = {"ok": False}

    @client.event
    async def on_ready() -> None:
        try:
            guild = client.get_guild(settings.guild_id)
            if guild is None:
                print(f"Sunucu bulunamadi: {settings.guild_id}")
                return

            print(f"Sunucu: {guild.name} ({guild.id})")

            now = guild.get_channel(settings.now_category_id)
            log_category = guild.get_channel(settings.log_category_id)
            role = guild.get_role(settings.staff_role_id)

            member = None
            if settings.staff_user_id:
                member = guild.get_member(settings.staff_user_id)
                if member is None:
                    try:
                        member = await guild.fetch_member(settings.staff_user_id)
                    except discord.NotFound:
                        member = None

            now_ok = isinstance(now, discord.CategoryChannel)
            log_ok = isinstance(log_category, discord.CategoryChannel)
            role_ok = role is not None if settings.staff_role_id else True
            user_ok = member is not None if settings.staff_user_id else True
            has_staff = bool(settings.staff_role_id or settings.staff_user_id)

            print(
                "Now kategorisi: "
                + (f"{now.name} ({now.id})" if now_ok else "BULUNAMADI")
            )
            print(
                "Log kategorisi: "
                + (
                    f"{log_category.name} ({log_category.id})"
                    if log_ok
                    else "BULUNAMADI"
                )
            )
            if settings.staff_role_id:
                print(
                    "Staff rolu: "
                    + (f"{role.name} ({role.id})" if role_ok and role else "BULUNAMADI")
                )
            else:
                print("Staff rolu: ATLANDI (STAFF_ROLE_ID bos)")
            if settings.staff_user_id:
                print(
                    "Staff kullanicisi: "
                    + (
                        f"{member} ({member.id})"
                        if user_ok and member
                        else "BULUNAMADI"
                    )
                )
            else:
                print("Staff kullanicisi: ATLANDI (STAFF_USER_ID bos)")
            if not has_staff:
                print("STAFF_ROLE_ID veya STAFF_USER_ID dolu olmali.")

            me = guild.me
            if me is None:
                print("Bot uyesi sunucuda bulunamadi.")
                return

            permissions = me.guild_permissions
            permission_names = (
                "manage_channels",
                "send_messages",
                "read_message_history",
                "view_channel",
            )
            permissions_ok = True
            for name in permission_names:
                value = bool(getattr(permissions, name, False))
                print(f"Izin {name}: {'OK' if value else 'EKSIK'}")
                permissions_ok = permissions_ok and value

            result["ok"] = (
                now_ok
                and log_ok
                and has_staff
                and role_ok
                and user_ok
                and permissions_ok
            )
        finally:
            await client.close()

    try:
        await client.start(settings.discord_token)
    except discord.LoginFailure:
        print("Discord token gecersiz.")
        return 1

    print()
    print("SONUC: " + ("HAZIR" if result["ok"] else "EKSIK AYAR/IZIN VAR"))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(run()))
