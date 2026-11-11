#!/usr/bin/env python3
"""
Toxido Anti Cheat - surum katalogu ureteci.

Kaynaklar:
  - ac-agent/.../VanillaProfiles.java  (1.18 - 1.21.11 obfuscated adlar)
  - Fabric intermediary v2 jar'leri    (obf -> intermediary)
  - Forge mcp_config joined.tsrg       (obf -> SRG)
  - 26.x client jar'lari               (resmi/Mojang adlari, javap ile dogrulandi)

Cikti:
  ac-agent/src/main/resources/version-catalog.json
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AGENT = ROOT / "ac-agent" / "src" / "main" / "java" / "dev" / "toxido" / "anticheat" / "agent"
OUT = ROOT / "ac-agent" / "src" / "main" / "resources" / "version-catalog.json"
CACHE = Path(os.environ.get("TEMP", str(Path.home()))) / "opencode" / "mccatalog" / "cache"
CACHE.mkdir(parents=True, exist_ok=True)

FIELDS = [
    "minecraftClass", "worldField", "networkHandlerField", "networkHandlerClass",
    "connectionMethod", "connectionClass", "channelField", "attackClass",
    "attackMethod", "packetClass", "localPlayerClass", "screenClass",
    "screenField", "optionsClass", "optionsField", "keyMappingClass",
    "attackKeyField", "keyNameField", "keyDownMethod", "livingClass",
    "playerEntityClass", "armorStandClass", "entityClass", "packetEntityField",
    "packetActionField", "packetAttackActionField",
]

# Üye adının hangi sınıfın içinde aranacağı. Alan adları VersionProfile'daki
# anlamlarla birebir eşleşir (worldField = Minecraft.player gibi).
MEMBER_OWNER = {
    "worldField": "minecraftClass",
    "networkHandlerField": "localPlayerClass",
    "connectionMethod": "networkHandlerClass",
    "channelField": "connectionClass",
    "attackMethod": "attackClass",
    "screenField": "minecraftClass",
    "optionsField": "minecraftClass",
    "attackKeyField": "optionsClass",
    "keyNameField": "keyMappingClass",
    "keyDownMethod": "keyMappingClass",
    "packetEntityField": "packetClass",
    "packetActionField": "packetClass",
    "packetAttackActionField": "packetClass",
}

OFFICIAL_CLASSES = {
    "minecraftClass": "net.minecraft.client.Minecraft",
    "networkHandlerClass": "net.minecraft.client.multiplayer.ClientPacketListener",
    "connectionClass": "net.minecraft.network.Connection",
    "attackClass": "net.minecraft.client.multiplayer.MultiPlayerGameMode",
    "localPlayerClass": "net.minecraft.client.player.LocalPlayer",
    "screenClass": "net.minecraft.client.gui.screens.Screen",
    "optionsClass": "net.minecraft.client.Options",
    "keyMappingClass": "net.minecraft.client.KeyMapping",
    "livingClass": "net.minecraft.world.entity.LivingEntity",
    "playerEntityClass": "net.minecraft.world.entity.player.Player",
    "armorStandClass": "net.minecraft.world.entity.decoration.ArmorStand",
    "entityClass": "net.minecraft.world.entity.Entity",
}

INTERACT_PACKET = "net.minecraft.network.protocol.game.ServerboundInteractPacket"
ATTACK_PACKET = "net.minecraft.network.protocol.game.ServerboundAttackPacket"

V26 = ["26.1", "26.1.1", "26.1.2", "26.2", "26.3"]
V26_GUI_SCREEN = {"26.2", "26.3"}


def download(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 1000:
        return dest
    last = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "ToxidoCatalog/1.0"})
            with urllib.request.urlopen(req, timeout=180) as r:
                data = r.read()
            if len(data) < 1000:
                raise RuntimeError(f"short response {len(data)}")
            dest.write_bytes(data)
            return dest
        except Exception as exc:  # noqa: BLE001
            last = exc
    raise RuntimeError(f"download failed: {url}: {last}")


def parse_vanilla_profiles() -> dict[str, list[str]]:
    text = (AGENT / "VanillaProfiles.java").read_text(encoding="utf-8")
    pattern = re.compile(r'Map\.entry\("([^"]+)",\s*new VersionProfile\((.*?)\)\)', re.S)
    rows: dict[str, list[str]] = {}
    for match in pattern.finditer(text):
        version = match.group(1)
        values = re.findall(r'"([^"]*)"', match.group(2))
        if len(values) != 27:
            raise RuntimeError(f"{version}: expected 27 strings, got {len(values)}")
        rows[version] = values[1:]
    return rows


def parse_tiny(jar: Path):
    classes: dict[str, str] = {}
    fields: dict[str, dict[str, str]] = {}
    field_descs: dict[str, dict[str, str]] = {}
    methods: dict[str, dict[str, list[tuple[str, str]]]] = {}
    with zipfile.ZipFile(jar) as zf:
        data = zf.read("mappings/mappings.tiny").decode("utf-8")
    current = None
    for raw in data.splitlines():
        if not raw or raw.startswith("#") or raw.startswith("tiny\t"):
            continue
        if raw.startswith("c\t"):
            parts = raw.split("\t")
            if len(parts) >= 3:
                current = parts[1]
                classes[current] = parts[2]
        elif raw.startswith("\t"):
            parts = raw.lstrip("\t").split("\t")
            if not parts or current is None:
                continue
            if parts[0] == "f" and len(parts) >= 4:
                fields.setdefault(current, {})[parts[2]] = parts[3]
                field_descs.setdefault(current, {})[parts[2]] = parts[1]
            elif parts[0] == "m" and len(parts) >= 4:
                methods.setdefault(current, {}).setdefault(parts[2], []).append((parts[1], parts[3]))
    return {"classes": classes, "fields": fields, "field_descs": field_descs, "methods": methods}


def parse_tsrg(zip_path: Path):
    classes: dict[str, str] = {}
    fields: dict[str, dict[str, str]] = {}
    methods: dict[str, dict[str, list[tuple[str, str]]]] = {}
    with zipfile.ZipFile(zip_path) as zf:
        names = [n for n in zf.namelist() if n.endswith("joined.tsrg")]
        if not names:
            raise RuntimeError(f"joined.tsrg yok: {zip_path}")
        data = zf.read(names[0]).decode("utf-8")
    current = None
    for raw in data.splitlines():
        if not raw or raw.startswith("#") or raw.startswith("tsrg2"):
            continue
        if not raw.startswith("\t"):
            parts = raw.split()
            if len(parts) >= 2:
                current = parts[0]
                classes[current] = parts[1]
        else:
            # TSRG2: alan satiri   \t<obf> <srg> <id>
            #        metot satiri  \t<obf> <desc> <srg> <id>
            #        parametre/static satirlari iki sekme ile baslar; atla.
            if raw.startswith("\t\t"):
                continue
            parts = raw.strip().split()
            if not parts or current is None:
                continue
            if len(parts) == 3:
                fields.setdefault(current, {})[parts[0]] = parts[1]
            elif len(parts) >= 4:
                methods.setdefault(current, {}).setdefault(parts[0], []).append((parts[1], parts[2]))
    return {"classes": classes, "fields": fields, "methods": methods}


def map_class(mapping, obf: str):
    return mapping["classes"].get(obf)


def map_field(mapping, cls: str, obf: str):
    return mapping["fields"].get(cls, {}).get(obf)


def find_field_by_type(mapping, cls: str, desc: str):
    for obf, field_desc in mapping.get("field_descs", {}).get(cls, {}).items():
        if field_desc == desc:
            return obf
    return None


def map_method(mapping, cls: str, obf: str, descriptors: list[str]):
    entries = mapping["methods"].get(cls, {}).get(obf, [])
    for desc, name in entries:
        if desc in descriptors:
            return name
    if len(entries) == 1:
        return entries[0][1]
    return None


def descriptor_candidates(field: str, obf: dict[str, str]) -> list[str]:
    if field == "connectionMethod":
        return [f"()L{obf['connectionClass']};"]
    if field == "attackMethod":
        return [
            f"(L{obf['playerEntityClass']};L{obf['entityClass']};)V",
            f"(L{obf['localPlayerClass']};L{obf['entityClass']};)V",
        ]
    if field == "keyDownMethod":
        return ["()Z"]
    return []


def make_row(version: str, loader: str, mapping_kind: str, values: dict, source: str,
             verified: bool, loader_verified: bool, missing: list[str] | None = None,
             notes: list[str] | None = None) -> dict:
    row = {
        "id": f"{loader}-{version}",
        "version": version,
        "loader": loader,
        "mapping": mapping_kind,
        "verified": verified,
        "loaderVerified": loader_verified,
        "source": source,
        "missing": missing or [],
        "notes": notes or [],
    }
    row.update(values)
    return row


def base_26(version: str) -> dict:
    values = {
        "minecraftClass": OFFICIAL_CLASSES["minecraftClass"],
        "worldField": "player",
        "networkHandlerField": "connection",
        "networkHandlerClass": OFFICIAL_CLASSES["networkHandlerClass"],
        "connectionMethod": "getConnection",
        "connectionClass": OFFICIAL_CLASSES["connectionClass"],
        "channelField": "channel",
        "attackClass": OFFICIAL_CLASSES["attackClass"],
        "attackMethod": "attack",
        "packetClass": ATTACK_PACKET,
        "localPlayerClass": OFFICIAL_CLASSES["localPlayerClass"],
        "screenClass": OFFICIAL_CLASSES["screenClass"],
        "screenField": None if version in V26_GUI_SCREEN else "screen",
        "optionsClass": OFFICIAL_CLASSES["optionsClass"],
        "optionsField": "options",
        "keyMappingClass": OFFICIAL_CLASSES["keyMappingClass"],
        "attackKeyField": "keyAttack",
        "keyNameField": "key",
        "keyDownMethod": "isDown",
        "livingClass": OFFICIAL_CLASSES["livingClass"],
        "playerEntityClass": OFFICIAL_CLASSES["playerEntityClass"],
        "armorStandClass": OFFICIAL_CLASSES["armorStandClass"],
        "entityClass": OFFICIAL_CLASSES["entityClass"],
        "packetEntityField": "entityId",
        "packetActionField": None,
        "packetAttackActionField": None,
        # Ek katalog alanlari (VersionProfile'da yok; kod baglaninca kullanilacak)
        "screenAccess": "gui" if version in V26_GUI_SCREEN else "field",
        "guiField": "gui" if version in V26_GUI_SCREEN else None,
        "guiScreenField": "screen" if version in V26_GUI_SCREEN else None,
        "packetKind": "attack",
        "entityIdAccessor": "entityId",
        "channelMethod": None,
        "connectionField": None,
        "minecraftConnectionMethod": "getConnection",
        "pipelineHandler": "packet_handler",
        "pipelineHandlerVerified": version == "26.3",
    }
    return values


def build_official_row(version: str, loader: str, mapping_kind: str, source: str,
                       loader_verified: bool, notes: list[str] | None = None) -> dict:
    values = dict(base_26(version))
    values["packetClass"] = ATTACK_PACKET
    values["screenField"] = None if version in V26_GUI_SCREEN else "screen"
    values["screenAccess"] = "gui" if version in V26_GUI_SCREEN else "field"
    values["guiField"] = "gui" if version in V26_GUI_SCREEN else None
    values["guiScreenField"] = "screen" if version in V26_GUI_SCREEN else None
    return make_row(version, loader, mapping_kind, values, source, True, loader_verified, notes=notes)


def build_from_obf(version: str, loader: str, mapping_kind: str, obf_values: list[str],
                   mapping, source: str, loader_verified: bool,
                   official_members: bool, connection_field_obf: str | None = None) -> dict:
    obf = dict(zip(FIELDS, obf_values))
    values: dict[str, object] = {}
    missing: list[str] = []

    for field in FIELDS:
        obf_name = obf[field]
        if field in OFFICIAL_CLASSES:
            if official_members:
                values[field] = OFFICIAL_CLASSES[field]
            else:
                mapped = map_class(mapping, obf_name)
                values[field] = mapped
                if not mapped:
                    missing.append(field)
            continue
        if field == "packetClass":
            values[field] = ATTACK_PACKET if version in V26 else INTERACT_PACKET
            if not official_members:
                mapped = map_class(mapping, obf_name)
                if mapped:
                    values[field] = mapped
                else:
                    missing.append(field)
            continue
        owner = obf[MEMBER_OWNER[field]]
        if field == "connectionMethod":
            mapped = map_method(mapping, owner, obf_name,
                                descriptor_candidates(field, obf))
            values[field] = mapped
            values["connectionField"] = (
                map_field(mapping, owner, connection_field_obf)
                if connection_field_obf else None
            )
            if not mapped and not values["connectionField"]:
                missing.append(field)
            elif connection_field_obf and not values["connectionField"]:
                missing.append("connectionField")
            continue
        if field in ("attackMethod", "keyDownMethod"):
            mapped = map_method(mapping, owner, obf_name,
                                descriptor_candidates(field, obf))
            values[field] = mapped
            if not mapped:
                missing.append(field)
            continue
        mapped = map_field(mapping, owner, obf_name)
        values[field] = mapped
        if not mapped:
            missing.append(field)

    # Ek alanlar
    values.setdefault("connectionField", None)
    values["screenAccess"] = "field"
    values["guiField"] = None
    values["guiScreenField"] = None
    values["packetKind"] = "interact"
    values["entityIdAccessor"] = None
    values["channelMethod"] = "channel"
    values["minecraftConnectionMethod"] = None
    values["pipelineHandler"] = "packet_handler"
    values["pipelineHandlerVerified"] = version == "1.21.11"

    notes = []
    if version in ("1.18", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
                   "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
                   "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
                   "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"):
        notes.append("pipeline handler adi 'packet_handler'; Connection.configurePacketHandler tarafindan eklenir.")
    return make_row(version, loader, mapping_kind, values, source,
                    verified=not missing, loader_verified=loader_verified,
                    missing=missing, notes=notes)


def main() -> int:
    vanilla = parse_vanilla_profiles()
    versions = list(vanilla.keys()) + V26
    print(f"VanillaProfiles: {len(vanilla)} surum")
    print(f"Toplam: {len(versions)} surum")

    # Indirilecek kaynaklar (26.x haric; 26.x jar'lari zaten javap ile dogrulandi)
    jobs = []
    for v in vanilla:
        inter = CACHE / f"intermediary-{v}-v2.jar"
        if not (inter.exists() and inter.stat().st_size > 1000):
            jobs.append((f"https://maven.fabricmc.net/net/fabricmc/intermediary/{v}/intermediary-{v}-v2.jar", inter))
        mcp = CACHE / f"mcp_config-{v}.zip"
        if not (mcp.exists() and mcp.stat().st_size > 1000):
            jobs.append((f"https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/{v}/mcp_config-{v}.zip", mcp))

    if jobs:
        print(f"Indirilecek: {len(jobs)} dosya")
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
            futures = {ex.submit(download, url, dest): (url, dest) for url, dest in jobs}
            for fut in concurrent.futures.as_completed(futures):
                url, dest = futures[fut]
                try:
                    fut.result()
                    print(f"  OK  {dest.name}")
                except Exception as exc:  # noqa: BLE001
                    print(f"  HATA {dest.name}: {exc}", file=sys.stderr)
    else:
        print("Tum kaynaklar onbellekte.")

    catalog = {
        "schemaVersion": 1,
        "generatedAt": "2026-11-11",
        "description": "Toxido Anti Cheat surum/loader katalogu. Runtime'da arama yok; satirdaki adresler dogrudan kullanilir.",
        "catalogNotes": [
            "1.18-1.21.11 vanilla satirlari obfuscated adlar; Fabric satirlari intermediary; Forge satirlari Mojang sinif + SRG uye.",
            "26.x istemci jar'lari unobfuscated; tum loader satirlari resmi/Mojang adlarini kullanir.",
            "Fabric 26.x icin intermediary yayinlanmiyor; resmi adlar varsayildi (loaderVerified=false).",
            "NeoForge 1.20.1 SRG, 1.20.2+ Mojang uyeleri kullanir (varsayim; loaderVerified=false).",
            "pipelineHandler='packet_handler' Connection.configurePacketHandler tarafindan eklenir; baglanti/dunya hazir olmadan pipeline'da yoktur.",
            "26.1-26.1.2 screen Minecraft.screen; 26.2-26.3 Minecraft.gui.screen (screenAccess=gui).",
            "connectionMethod yoksa connectionField kullanilir (ozellikle Fabric 1.18-1.19.3).",
        ],
        "fieldOrder": FIELDS,
        "versions": {},
    }

    for version in versions:
        entry: dict[str, object] = {}
        if version in V26:
            entry["vanilla"] = build_official_row(version, "vanilla", "mojang", "26.x client jar (javap)", True)
            entry["fabric"] = build_official_row(
                version, "fabric", "mojang", "26.x client jar (javap); intermediary yok",
                False, ["Fabric intermediary 26.x yayinlanmiyor; resmi adlar varsayildi."])
            entry["forge"] = build_official_row(
                version, "forge", "mojang", "forge-26.3-userdev.jar; resmi adlar",
                version == "26.3", ["Forge 26.x resmi/Mojang adlarini kullaniyor."])
            entry["neoforge"] = build_official_row(
                version, "neoforge", "mojang", "neoforge-26.3-userdev.jar; resmi adlar",
                version == "26.3", ["NeoForge 26.x resmi/Mojang adlarini kullaniyor."])
        else:
            obf = vanilla[version]
            inter = parse_tiny(CACHE / f"intermediary-{version}-v2.jar")
            tsrg = parse_tsrg(CACHE / f"mcp_config-{version}.zip")
            obf_map = dict(zip(FIELDS, obf))
            connection_field_obf = find_field_by_type(
                inter, obf_map["networkHandlerClass"], f"L{obf_map['connectionClass']};")
            vanilla_values = dict(zip(FIELDS, obf))
            vanilla_values.update({
                "connectionField": connection_field_obf,
                "screenAccess": "field",
                "guiField": None,
                "guiScreenField": None,
                "packetKind": "interact",
                "entityIdAccessor": None,
                "channelMethod": "channel",
                "minecraftConnectionMethod": None,
                "pipelineHandler": "packet_handler",
                "pipelineHandlerVerified": version == "1.21.11",
            })
            entry["vanilla"] = make_row(
                version, "vanilla", "obfuscated", vanilla_values,
                "VanillaProfiles.java", True, True)
            entry["fabric"] = build_from_obf(
                version, "fabric", "intermediary", obf,
                inter, f"intermediary-{version}-v2.jar", True, False,
                connection_field_obf=connection_field_obf)
            entry["forge"] = build_from_obf(
                version, "forge", "mojang+srg", obf,
                tsrg, f"mcp_config-{version}.zip + resmi sinif adlari", True, True,
                connection_field_obf=connection_field_obf)
            if version == "1.20.1":
                entry["neoforge"] = build_from_obf(
                    version, "neoforge", "mojang+srg", obf,
                    tsrg, "NeoForge 1.20.1 = Forge SRG", False, True,
                    connection_field_obf=connection_field_obf)
            elif version in ("1.18", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2",
                             "1.19.3", "1.19.4", "1.20"):
                entry["neoforge"] = {
                    "id": f"neoforge-{version}",
                    "version": version,
                    "loader": "neoforge",
                    "supported": False,
                    "reason": "NeoForge bu Minecraft surumu icin yok (ilk surum 1.20.1).",
                }
            else:
                # 1.20.2 - 1.21.11: NeoForge resmi/Mojang uyeleri kullaniyor (varsayim).
                values = dict(zip(FIELDS, obf))
                values["minecraftClass"] = OFFICIAL_CLASSES["minecraftClass"]
                values["networkHandlerClass"] = OFFICIAL_CLASSES["networkHandlerClass"]
                values["connectionClass"] = OFFICIAL_CLASSES["connectionClass"]
                values["attackClass"] = OFFICIAL_CLASSES["attackClass"]
                values["packetClass"] = INTERACT_PACKET
                values["localPlayerClass"] = OFFICIAL_CLASSES["localPlayerClass"]
                values["screenClass"] = OFFICIAL_CLASSES["screenClass"]
                values["optionsClass"] = OFFICIAL_CLASSES["optionsClass"]
                values["keyMappingClass"] = OFFICIAL_CLASSES["keyMappingClass"]
                values["livingClass"] = OFFICIAL_CLASSES["livingClass"]
                values["playerEntityClass"] = OFFICIAL_CLASSES["playerEntityClass"]
                values["armorStandClass"] = OFFICIAL_CLASSES["armorStandClass"]
                values["entityClass"] = OFFICIAL_CLASSES["entityClass"]
                values["worldField"] = "player"
                values["networkHandlerField"] = "connection"
                values["connectionMethod"] = "getConnection"
                values["channelField"] = "channel"
                values["attackMethod"] = "attack"
                values["screenField"] = "screen"
                values["optionsField"] = "options"
                values["attackKeyField"] = "keyAttack"
                values["keyNameField"] = "key"
                values["keyDownMethod"] = "isDown"
                values["packetEntityField"] = "entityId"
                values["packetActionField"] = "action"
                values["packetAttackActionField"] = "ATTACK_ACTION"
                values["screenAccess"] = "field"
                values["guiField"] = None
                values["guiScreenField"] = None
                values["packetKind"] = "interact"
                values["entityIdAccessor"] = None
                values["channelMethod"] = "channel"
                values["connectionField"] = None
                values["minecraftConnectionMethod"] = None
                values["pipelineHandler"] = "packet_handler"
                values["pipelineHandlerVerified"] = version == "1.21.11"
                entry["neoforge"] = make_row(
                    version, "neoforge", "mojang", values,
                    "NeoForge 1.20.2+ resmi/Mojang adlari (varsayim)",
                    True, False,
                    notes=["NeoForge 1.20.2+ Mojang uyeleri kullaniyor varsayildi; runtime testi bekliyor."])
        catalog["versions"][version] = entry

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Yazildi: {OUT} ({OUT.stat().st_size} bayt)")

    # Ozet dogrulama
    total = sum(len(v) for v in catalog["versions"].values())
    unsupported = sum(1 for v in catalog["versions"].values() for r in v.values() if r.get("supported") is False)
    missing_rows = [(ver, loader, row.get("missing")) for ver, loaders in catalog["versions"].items()
                    for loader, row in loaders.items() if row.get("missing")]
    print(f"Toplam satir: {total}; desteklenmeyen: {unsupported}; eksik alanli: {len(missing_rows)}")
    for item in missing_rows[:10]:
        print("  EKSIK", item)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
