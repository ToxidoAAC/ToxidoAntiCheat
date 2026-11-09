# Toxido Anti Cheat

Minecraft istemcisine **mod kurmadan**, JVM Attach API ile baglanan istemci tarafli
anticheat sistemi. Bu depo **Faz 1**'i icerir: Minecraft'i bulma, Java agent'i
oyun JVM'ine yukleme, agent el sikismasi, oturum yasam dongusu, HMAC korumali
API ve Discord kanal yonetimi.

> Faz 2'de instrumentation tabanli gercek kontrol katmani eklenecek. Faz 1'de
> agent henuz oyun icini denetlemez; yalnizca baglanti ve oturum altyapisini
> dogrular.

## Mimari

```
Minecraft JVM (javaw.exe)
        ^  attach + agent yukleme
        |
ac-app (terminal + arka plan servisi) --HMAC HTTPS--> backend (FastAPI)
        ^  loopback IPC (port + token)          |
        |                                       +--> SQLite
   ac-agent.jar                                 +--> discord.py bot
                                                +--> Now/ ve Log/ kategorileri
```

- **ac-app**: Acilista blok `TOXIDO` logosu ve Matrix yagmuru gosteren bir
  terminal ekrani cizer. Enter'a basinca ekran kapanir, anticheat servisi
  arka planda calismaya devam eder. Servis Minecraft JVM'ini
  `VirtualMachine.list()` ile bulur, agent'i yukler, agent ile loopback
  uzerinden konusur, API oturumunu baslatir ve kalp atisi gonderir. Tek
  seferliktir; oyun kapaninca oturumu bitirir ve yeniden baglanmaz.
- **ac-agent**: Oyun JVM'ine dinamik olarak yuklenen Java agent. Uygulamaya
  `hello` gonderir, `hello_ack` alir ve 5 saniyede bir kalp atisi yollar.
- **backend**: FastAPI + `discord.py` + SQLite. Oturum baslatma/bitirme,
  zaman asimi denetimi ve Discord kanal yasam dongusunden sorumludur.

## Gereksinimler

- Windows 10/11 (son kullanici icin yalnizca `40x1d0AC.exe`; Java gerekmez)
- Gelistirme: JDK 21 (projede `C:\Users\Toxido\tools\jdk-21` kullaniliyor),
  Maven 3.9+, Python 3.12+
- Discord botu (gercek mod icin)

Bu makinede araclar `scripts/dev-env.ps1` ile PATH'e eklenir. PowerShell
execution policy kapali oldugu icin her yeni kabukta once sunu calistirin:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
. .\scripts\dev-env.ps1
```

## Derleme

```powershell
mvn -B -DskipTests package
```

Cikti:

- `ac-agent/target/ac-agent.jar`
- `ac-app/target/ac-app.jar` (Gson ve Jansi dahil, fat jar)

## Yerel calistirma (dry-run)

1. Backend'i baslatin:

```powershell
.\scripts\run-backend.ps1 -DryRun
```

2. Test Minecraft'ini baslatin (gercek oyun yoksa):

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp 'tools\fake-minecraft\classes' `
  net.minecraft.client.main.Main `
  --username TestUser `
  --uuid 00000000-0000-0000-0000-000000000000 `
  --version 1.20.1
```

3. Uygulamayi baslatin:

```powershell
.\scripts\run-app.ps1
```

Beklenen akis:

```
Minecraft PID <pid> (TestUser)
Ajan el sikismasi tamam.
Ajan baglandi.
Oturum: <session-id>
Discord kanal id: 900000000000000001
```

Dry-run modunda Discord'a istek gitmez; backend logunda `Now/testuser` ve
oyun kapaninca `Log/testuser-<n>` gorunur. Oturum kayitlari
`backend/data/toxido.db` icindedir.

## Terminal arayuzu

`run-app.ps1` calistiginda terminalde blok `TOXIDO` logosu, iki yaninda zıt
yonlerde donen yesil halkalar ve altinda yukaridan asagi akan Matrix kodlari
gorunur. **Enter** ekrani kapatir; servis arka planda calismaya devam eder.
Ekranda durum/log kalabaligi yoktur.

Servis durumlari ve hatalar su dosyalara yazilir:

| Dosya | Icerik |
|---|---|
| `%APPDATA%\ToxidoAC\ac.log` | Servis durumu, Minecraft/agent/API olaylari |
| `%APPDATA%\ToxidoAC\launcher.log` | Splash'in servisi baslatma kaydi |
| `%APPDATA%\ToxidoAC\service.out` | Arka plan servisinin stdout/stderr ciktisi |

Ayni anda yalnizca bir servis calisir; ikinci baslatma `service.lock`
tarafindan engellenir. Servis, Minecraft kapaninca kendiliginden durur.
Minecraft acik degilken baslatilirsa oyunu bekler. Elle durdurmak icin
`ac-app.jar` ile calisan `javaw.exe` surecini kapatabilirsiniz.

### Masaustu baslatici

Proje kokundeki `40x1d0AC.bat` terminal splash'ini baslatir. Cift tiklamak
icin `Toxido Anti Cheat` kisayolu masaustune eklendi. Kisayol proje kokunu
calisma dizini olarak kullanir; projeyi tasirsaniz kisayolu yeniden
olusturmaniz gerekir. Baslatmadan once `mvn -B -DskipTests package` ile
jar'larin derlenmis olmasi gerekir.

## Tek EXE (Windows)

`dist/40x1d0AC.exe`, Java kurulumu gerektirmeyen tek dosyalik surumdur.
Icinde jlink ile olusturulmus Java 21 runtime, `ac-app.jar`, `ac-agent.jar`
ve `client.properties` paketlenir. Ilk calistirmada icerik
`%LOCALAPPDATA%\ToxidoAC\engine\<payload-id>\` altina cikarilir; sonraki
acilislarda hazir paket kullanilir. Minecraft'a mod kurulmaz.

### EXE olusturma

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\scripts\build-exe.ps1
```

Cikti: `dist\40x1d0AC.exe` (yaklasik 24 MB). Gereksinimler: JDK 21, Maven ve
Windows'un yerlesik .NET Framework `csc.exe` derleyicisi. `jlink` runtime'i
`java.base,java.compiler,java.net.http,java.sql,jdk.attach,jdk.unsupported,
java.logging,java.management` modulleriyle olusturur.

### Tek komutla indirme ve calistirma

EXE **VDS uzerinden dagitilmaz**; VDS yalnizca API ve Discord botunu calistirir.
Istemci GitHub Releases uzerinden yayinlanir:

**https://github.com/ToxidoAAC/ToxidoAntiCheat/releases/tag/v1.0.1**

Tek satir Windows cmd:

```cmd
powershell -NoProfile -Command "iwr https://github.com/ToxidoAAC/ToxidoAntiCheat/releases/download/v1.0.1/40x1d0AC.exe -OutFile $env:TEMP\40x1d0AC.exe; & $env:TEMP\40x1d0AC.exe"
```

Komut dosyayi `%TEMP%` altina indirir ve calistirir. Ilk calistirmada paket
cikarilirken kisa bir `Toxido AC hazirlaniyor...` satiri gorunur; ardindan
terminal splash acilir. **Enter** splash'i kapatir, servis arka planda devam
eder.

Notlar:

- EXE imzalanmadigi icin Windows SmartScreen ilk acilista uyari gosterebilir:
  **More info -> Run anyway**.
- `dist\40x1d0AC.exe` git'e eklenmez; her surumde yeniden uretilir.
- Teshis icin `dist\40x1d0AC.exe --print-path` paketin cikarildigi dizini
  yazdirir.
- Backend'deki `/dl` yolu istege baglidir; kullanilirsa `DOWNLOAD_PATH`
  ayarindaki dosyayi sunar, ancak istemci dagitimi icin onerilmez.

## Gercek Discord modu

`backend/.env.example` dosyasini `backend/.env` olarak kopyalayip doldurun:

```dotenv
DISCORD_TOKEN=...
GUILD_ID=...
NOW_CATEGORY_ID=...
LOG_CATEGORY_ID=...
STAFF_ROLE_ID=
STAFF_USER_ID=
API_KEY=<guclu-bir-anahtar>
API_SECRET=<guclu-bir-sir>
DB_PATH=./data/toxido.db
HOST=127.0.0.1
PORT=8000
DRY_RUN=0
HEARTBEAT_TIMEOUT_SECONDS=45
```

Bot izinleri: **Manage Channels, Send Messages, Read Message History, View
Channels**. Kanal izin overwrite'lari 403 verirse bot rolune **Manage Roles**
da verin. `Now` ve `Log` kategorileri yalnizca staff rolune veya
`STAFF_USER_ID` ile belirtilen kullaniciya gorunmelidir.

### Discord on kontrolu

Backend'i baslatmadan once token, sunucu/kategori/rol veya kullanici
ID'lerini ve bot izinlerini dogrulayin:

```powershell
.\backend\.venv\Scripts\python.exe .\backend\check_discord.py
```

Cikti `SONUC: HAZIR` ise gercek test icin hazirsiniz. Eksik alan varsa
`backend/.env` dosyasini duzeltin.

### Gercek test adimlari

1. `backend/.env` icinde `DRY_RUN=0` yapin.
2. On kontrolu calistirin.
3. Backend'i baslatin (bu kez `-DryRun` kullanmadan):

```powershell
.\scripts\run-backend.ps1
```

4. `http://127.0.0.1:8000/health` cevabinda `"bot_ready": true` gorun.
5. Test Minecraft'ini veya gercek oyunu baslatin, ardindan `.\scripts\run-app.ps1`.
6. Discord'da `Now/<oyuncu>` kanalinin acildigini dogrulayin.
7. Oyunu kapatinca kanalin `Log/<oyuncu>-<n>` olarak tasindigini dogrulayin.

Kanal olusturma 403 hatasi verirse bot rolunun kategori ve kanal izinlerini
kontrol edin; gerekirse **Manage Roles** iznini ekleyin.

Uygulama tarafinda `ac-app/config.properties` (veya ortam degiskenleri) ayni
`api.key` / `api.secret` degerlerini kullanmalidir:

```properties
api.baseUrl=https://ac.example.com
api.key=<ayni-anahtar>
api.secret=<ayni-sir>
heartbeat.intervalMs=5000
agent.timeoutMs=20000
attach.timeoutMs=15000
agent.version=0.1.0
```

Ortam degiskenleri: `AC_API_URL`, `AC_API_KEY`, `AC_API_SECRET`,
`AC_AGENT_JAR`.

## Oturum yasam dongusu

1. Uygulama Minecraft JVM'ini bulur (vanilla, Fabric, Quilt, Forge, NeoForge).
2. Agent'i yukler; agent loopback'e baglanip token ile el sikisir.
3. Uygulama API'ye `POST /api/v1/session/start` gonderir.
4. Backend `Now/<mcname>` kanalini olusturur ve oturumu `active` yazar.
5. Uygulama 5 saniyede bir `POST /heartbeat` gonderir.
6. Oyun kapanirsa `POST /end` cagrilir; kanal `Log/<mcname>-<n>` olarak
   arsivlenir. `n`, ayni oyuncunun onceki kayitlarinin maksimumunun bir
   fazlasidir.
7. Agent 20 saniye sessiz kalirsa uygulama oturumu `agent_silent` ile bitirir.
   Backend 45 saniye heartbeat alamazsa oturumu `heartbeat_timeout` ile kapatir.

Her oturum bagimsizdir; ayni oyuncunun sonraki oturumu yeni bir `Now/<mcname>`
kanali acar.

## API ve guvenlik

- Tum yazma istekleri HMAC-SHA256 ile imzalanir:
  `timestamp + "." + nonce + "." + body`.
- Basliklar: `X-AC-Key`, `X-AC-Timestamp`, `X-AC-Nonce`, `X-AC-Signature`.
- Zaman damgasi ±300 saniye, nonce tek kullanimlik (10 dakika onbellek).
- Oturum token'i yalnizca heartbeat/end icin `Authorization: Bearer` olarak
  gonderilir; veritabaninda SHA-256 ozeti tutulur.
- `GET /dl` (ve `GET /download/40x1d0AC.exe`) `static/40x1d0AC.exe` dosyasini
  indirir; baska bir yol icin `DOWNLOAD_PATH` kullanilabilir.
- Uretimde HTTPS zorunludur (Caddy / Let's Encrypt onerilir).

## Test

```powershell
# Backend ayakta iken HMAC API testi
.\backend\.venv\Scripts\python.exe .\backend\smoke_test.py
```

Test kapsami: oturum baslatma, heartbeat, replay reddi, yanlis imza reddi,
oturum bitirme, idempotent bitirme ve ikinci oturumun log indeksi.

## Bilinen sinirlar

- Istemci tarafli anticheat her zaman atlatilabilir; bu bir tespit/izleme
  katmanidir, sunucu tarafinin yerini tutmaz.
- `-XX:+DisableAttachMechanism` ile baslatilan Minecraft'a baglanilamaz.
- Minecraft kullanici adi launch argumanlarindan okunur; sahte olabilir.
- Uygulama hic calistirilmazsa sistem oturumu goremez. Sunucu tarafinda
  zorunlu bir plugin/entegrasyon olmadan bu gap kapanmaz.
- Faz 1'de oyun ici hile kontrolü yoktur; agent yalnizca baglanti saglar.

## Dizin yapisi

```
ac-agent/      Java agent (AgentMain, AgentClient)
ac-app/        Terminal splash + arka plan servisi (Config, MinecraftProcess,
               AgentBridge, AttachService, ApiClient, AppController,
               TerminalSplash, ServiceMain, LogReporter, ServiceLock)
backend/app/   FastAPI, SQLite, Discord kanal yonetimi, bot
backend/static/ Indirilebilir 40x1d0AC.exe (VDS'e ayrica yuklenir)
40x1d0AC.bat   Masaustu/cift tik baslatici
backend/       requirements, .env.example, smoke_test.py, run.py
scripts/       dev-env, run-backend, run-app, build-exe, Launcher.cs
dist/          40x1d0AC.exe ve dagitim paketleri (git'e eklenmez)
tools/         fake Minecraft ve tani araclari
```

## Faz 2 yol haritasi

- Instrumentation ile sinif/yukleme taramasi (mod, agent, cheat imzalari).
- Süpheli davranis sinyalleri ve periyodik kontrol.
- Agent'in uygulama kapansa bile oturumu guvenli kapatmasi.
- Sunucu tarafi dogrulama ve daha guclu kimlik baglama.
- VDS uzerinde HTTPS, systemd servisi ve yedekleme.
