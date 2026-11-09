from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")


def _int(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    discord_token: str
    guild_id: int
    now_category_id: int
    log_category_id: int
    staff_role_id: int
    staff_user_id: int
    api_key: str
    api_secret: str
    db_path: Path
    download_path: Path
    host: str
    port: int
    dry_run: bool
    heartbeat_timeout_seconds: int

    @property
    def discord_configured(self) -> bool:
        return bool(
            self.discord_token
            and self.guild_id
            and self.now_category_id
            and self.log_category_id
            and (self.staff_role_id or self.staff_user_id)
        )

    def validate(self) -> None:
        if self.dry_run:
            return
        if self.api_key == "dev-key" or self.api_secret == "dev-secret-change-me":
            raise RuntimeError(
                "DRY_RUN=0 icin API_KEY ve API_SECRET degistirilmeli."
            )
        if not self.discord_configured:
            raise RuntimeError(
                "DRY_RUN=0 icin Discord token, guild, kategori ve staff rol "
                "veya staff kullanici ayarlari zorunlu."
            )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    db_raw = os.getenv("DB_PATH", str(BASE_DIR / "data" / "toxido.db"))
    db_path = Path(db_raw)
    if not db_path.is_absolute():
        db_path = (BASE_DIR / db_path).resolve()

    download_raw = os.getenv(
        "DOWNLOAD_PATH", str(BASE_DIR / "static" / "ToxidoAC.exe")
    )
    download_path = Path(download_raw)
    if not download_path.is_absolute():
        download_path = (BASE_DIR / download_path).resolve()

    # Barindirma panelleri genellikle dinlenecek portu SERVER_PORT ile verir.
    # SERVER_PORT varsa onu, yoksa yerel PORT degerini kullan.
    server_port = _int("SERVER_PORT", 0)
    port = server_port if server_port > 0 else _int("PORT", 8000)
    host = os.getenv("HOST", "").strip()
    if not host:
        host = "0.0.0.0" if server_port > 0 else "127.0.0.1"

    return Settings(
        discord_token=os.getenv("DISCORD_TOKEN", "").strip(),
        guild_id=_int("GUILD_ID", 0),
        now_category_id=_int("NOW_CATEGORY_ID", 0),
        log_category_id=_int("LOG_CATEGORY_ID", 0),
        staff_role_id=_int("STAFF_ROLE_ID", 0),
        staff_user_id=_int("STAFF_USER_ID", 0),
        api_key=os.getenv("API_KEY", "dev-key").strip(),
        api_secret=os.getenv("API_SECRET", "dev-secret-change-me").strip(),
        db_path=db_path,
        download_path=download_path,
        host=host,
        port=port,
        dry_run=_bool("DRY_RUN", True),
        heartbeat_timeout_seconds=_int("HEARTBEAT_TIMEOUT_SECONDS", 45),
    )
