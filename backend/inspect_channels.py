import json
import os
import urllib.request
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

TOKEN = os.environ["DISCORD_TOKEN"]
GUILD_ID = os.environ["GUILD_ID"]
NOW_ID = os.environ["NOW_CATEGORY_ID"]
LOG_ID = os.environ["LOG_CATEGORY_ID"]


def api(path: str):
    req = urllib.request.Request(
        f"https://discord.com/api/v10{path}",
        headers={"Authorization": f"Bot {TOKEN}", "User-Agent": "ToxidoAC-Inspect/0.1"},
    )
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


channels = api(f"/guilds/{GUILD_ID}/channels")
for channel in channels:
    if channel.get("parent_id") not in (NOW_ID, LOG_ID):
        continue
    print(f"{channel['id']} | {channel['name']} | parent={channel['parent_id']} | type={channel['type']}")
    for overwrite in channel.get("permission_overwrites", []):
        print(
            "  overwrite "
            f"id={overwrite['id']} type={overwrite['type']} "
            f"allow={overwrite['allow']} deny={overwrite['deny']}"
        )
