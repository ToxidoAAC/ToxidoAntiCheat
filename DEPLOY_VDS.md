# VDS Dagitimi (bot-hosting.cloud)

## Bilinen VDS bilgileri

- Panel: bot-hosting.cloud
- Dugum: `fi1`
- IP: `65.108.39.249`
- Birincil port: `25364`
- Genel adres: `fi1i.bot-hosting.cloud:25364`
- Planda tek port var. Panel `SERVER_PORT=25364` veriyorsa backend bunu
  otomatik kullanir; vermiyorsa `.env` icinde `PORT=25364` yazilmalidir.

> Bu adres varsayilan olarak **HTTP**'dir. Gercek kullanimda API anahtari ve
> oturum verileri icin HTTPS gerekir. Acil test icin HTTP kabul edilebilir;
> kalici cozum icin Cloudflare Tunnel veya TLS sonlandiran bir reverse proxy
> kullanin.

## 1. Backend dosyalarini VDS'e yukleme

Iki yol var:

1. **SSH/SFTP** (tercih edilen): `backend/` klasorunu sunucuya kopyalayin.
   Sifre yerine SSH anahtari kullanin.
2. **Panel dosya yoneticisi**: `backend/` icerigini zip olarak yukleyip acin.

Sunucuda Python 3.10+ ve `python3-venv` bulunmalidir. `start.sh` ilk calismada
`.venv` olusturur ve `requirements.txt` paketlerini kurar.

## 2. `.env` olusturma

`backend/.env.example` dosyasini `backend/.env` olarak kopyalayin ve doldurun:

```dotenv
DISCORD_TOKEN=...
GUILD_ID=...
NOW_CATEGORY_ID=...
LOG_CATEGORY_ID=...
STAFF_ROLE_ID=...
STAFF_USER_ID=...

API_KEY=<guclu-anahtar>
API_SECRET=<guclu-sir>

DB_PATH=./data/toxido.db
DOWNLOAD_PATH=./static/40x1d0AC.exe
HOST=0.0.0.0
PORT=25364
DRY_RUN=0
HEARTBEAT_TIMEOUT_SECONDS=120
INJECTION_ALERTS=1
INJECTION_ALERT_KINDS=jvm_argument,mod_file,cheat_signature,external_agent
INJECTION_MIN_CONFIDENCE=high
```

- Panel `SERVER_PORT` veriyorsa backend onu `PORT` degerine tercih eder.
- `STAFF_ROLE_ID` veya `STAFF_USER_ID` alanlarindan en az biri dolu olmalidir.
- Panel `SERVER_PORT` vermiyorsa `PORT=25364` yedek olarak kullanilir.
- `.env` dosyasini kimseyle paylasmayin; sunucuda `chmod 600 .env` yapin.
- `API_KEY` ve `API_SECRET`, `ac-app/config.properties` ile ayni olmalidir.

## 3. Baslatma

Dosyalar sunucu kokundeyse panelin startup komutunu su yapin:

```bash
bash start.sh
```

Alternatif olarak panelin varsayilan Python komutu `python main.py` ise
`backend/main.py` de ayni isi yapar. `backend/` klasoru icindeyse:

```bash
bash backend/start.sh
```

Ilk baslatmada pip kurulumu biraz surer. Loglarda su satir gorunmelidir:

```
Backend hazir. dry_run=False db=... bot=on
```

## 4. Saglik kontrolu

VDS uzerinden:

```bash
curl http://127.0.0.1:25364/health
```

Yerel bilgisayardan (alan adi henuz cozulmuyorsa IP kullanin):

```powershell
Invoke-RestMethod http://65.108.39.249:25364/health
```

Beklenen cevap:

```json
{"ok":true,"version":"0.2.4","dry_run":false,"bot_ready":true,"active_sessions":0,"alert_queue":0,"alert_dropped":0,"alert_filtered":0,"heartbeat_timeout_seconds":120,"injection_alerts":true,"injection_alert_kinds":["cheat_signature","external_agent","jvm_argument","mod_file"],"injection_min_confidence":"high"}
```

`alert_queue` Discord uyarilarinin bekleyen sayisidir; `alert_dropped`
kuyruk doldugu icin gonderilemeyen uyari sayisidir. Normalde `alert_dropped`
artmamalidir. `alert_filtered` ise dusuk/orta guvenli veya izin verilmeyen
turdeki enjeksiyon bulgularinin Discord'a gonderilmeden filtrelendigi sayidir;
triggerbot uyarilari bu filtreden etkilenmez.

Baglanti kurulamazsa:

- Panelde portun `25364` ve `SERVER_PORT` oldugunu kontrol edin.
- `.env` icinde `HOST=0.0.0.0` oldugundan emin olun.
- Panel firewall/port yonlendirmesini kontrol edin.

## 5. Discord botunu dogrulama

Sunucuda:

```bash
.venv/bin/python check_discord.py
```

`SONUC: HAZIR` gorunene kadar devam edin.

## 6. ac-app ayari

Uygulama tarafinda gercek anahtarlar `ac-app/config.local.properties` dosyasina
yazilir; `scripts/build-exe.ps1` bunlari jar'a gomer. Bu dosya git'e eklenmez
ve EXE icine kopyalanmaz; dagitilan `client.properties` yalnizca `api.baseUrl`
ve zaman asimlarini tasir.

```properties
api.baseUrl=http://65.108.39.249:25364
api.key=<backend/.env ile ayni>
api.secret=<backend/.env ile ayni>
```

Alternatif olarak uygulamayi baslatmadan once ortam degiskenleri:

```powershell
$env:AC_API_URL = "http://fi1i.bot-hosting.cloud:25364"
$env:AC_API_KEY = "..."
$env:AC_API_SECRET = "..."
.\scripts\run-app.ps1
```

## 7. Tek EXE dagitimi

**40x1d0AC.exe VDS uzerinden indirilmez.** VDS yalnizca FastAPI ve Discord
botunu calistirir. Istemci GitHub Releases uzerinden yayinlanir:

**https://github.com/ToxidoAAC/ToxidoAntiCheat/releases/tag/v1.0.8**

Tek komut:

```cmd
powershell -NoProfile -Command "iwr https://github.com/ToxidoAAC/ToxidoAntiCheat/releases/download/v1.0.8/40x1d0AC.exe -OutFile $env:TEMP\40x1d0AC.exe; Start-Process $env:TEMP\40x1d0AC.exe" & exit
```

Backend'deki `/dl` ve `/download/40x1d0AC.exe` yollari istege baglidir. VDS'te
kullanmak isterseniz `dist\40x1d0AC.exe` dosyasini
`/home/container/static/40x1d0AC.exe` konumuna koyun ve `.env` icindeki
`DOWNLOAD_PATH` degerini kontrol edin. Bu yol varsayilan dagitim yontemi
degildir. EXE imzasiz oldugu icin SmartScreen uyarisi cikabilir.

## 8. HTTPS (kalici cozum)

Panelin verdigi ham port HTTP'dir. Uretimde:

- **Cloudflare Tunnel**: VDS'te `cloudflared` calistirin; `127.0.0.1:25364`
  adresini HTTPS'li bir alan adina baglayin. Hizli test icin
  `cloudflared tunnel --url http://127.0.0.1:25364` kullanilabilir; ancak
  `trycloudflare.com` adresi her yeniden baslatmada degisir.
- **Alan adi + TLS**: Alan adi varsa Cloudflare DNS ile named tunnel veya
  sunucuda TLS sonlandiran reverse proxy kullanin.

HTTPS hazir olana kadar gercek kullanicilarla uzun sureli test yapmayin.

## 9. Yedekleme

SQLite dosyasi:

```
backend/data/toxido.db
```

Sunucu kapanmadan once `sqlite3 toxido.db ".backup ..."` veya dosya kopyasi
alin. `backend/data/` git'e eklenmez.
