param(
    [string]$Output = (Join-Path $PSScriptRoot '..\dist\vds-deploy.zip'),
    [switch]$NoEnv
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $root 'backend'
$stage = Join-Path $root 'build\vds-deploy'
$stageBackend = Join-Path $stage 'backend'

if (-not (Test-Path (Join-Path $backend 'main.py'))) {
    throw "Backend bulunamadi: $backend"
}

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $stageBackend 'app') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stageBackend 'static') | Out-Null

# Uygulama kaynaklari (secret, .venv, data ve __pycache__ dahil edilmez).
Copy-Item (Join-Path $backend 'app\*.py') (Join-Path $stageBackend 'app') -Force
foreach ($name in @(
    'main.py',
    'run.py',
    'requirements.txt',
    'start.sh',
    'check_discord.py',
    'inspect_channels.py'
)) {
    $source = Join-Path $backend $name
    if (Test-Path $source) {
        Copy-Item $source $stageBackend -Force
    }
}
New-Item -ItemType File -Force -Path (Join-Path $stageBackend 'static\.gitkeep') | Out-Null

function Set-DotEnvValue {
    param(
        [string[]]$Lines,
        [string]$Key,
        [string]$Value
    )
    $result = New-Object System.Collections.Generic.List[string]
    $seen = $false
    foreach ($line in $Lines) {
        if ($line -match "^\s*$([regex]::Escape($Key))\s*=") {
            $result.Add("$Key=$Value")
            $seen = $true
        } else {
            $result.Add($line)
        }
    }
    if (-not $seen) {
        $result.Add("$Key=$Value")
    }
    return ,$result.ToArray()
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$vdsValues = @{
    'HOST' = '0.0.0.0'
    'PORT' = '25364'
    'DRY_RUN' = '0'
}

# .env.example: VDS'e uygun, secret icermeyen ornek.
$exampleSource = Join-Path $backend '.env.example'
$exampleLines = @(Get-Content $exampleSource)
foreach ($key in $vdsValues.Keys) {
    $exampleLines = Set-DotEnvValue -Lines $exampleLines -Key $key -Value $vdsValues[$key]
}
[IO.File]::WriteAllLines(
    (Join-Path $stageBackend '.env.example'),
    [string[]]$exampleLines,
    $utf8NoBom
)

# .env: mevcut secret'lari koru, yalnizca VDS baglanti ayarlarini degistir.
if (-not $NoEnv) {
    $envSource = Join-Path $backend '.env'
    if (Test-Path $envSource) {
        $envLines = @(Get-Content $envSource)
        foreach ($key in $vdsValues.Keys) {
            $envLines = Set-DotEnvValue -Lines $envLines -Key $key -Value $vdsValues[$key]
        }
        [IO.File]::WriteAllLines(
            (Join-Path $stageBackend '.env'),
            [string[]]$envLines,
            $utf8NoBom
        )
        Write-Warning 'Pakete gercek Discord/API secretlari iceren .env eklendi. Zip gizlidir, paylasmayin.'
    } else {
        Write-Warning 'backend\.env bulunamadi; pakete .env eklenmedi.'
    }
}

$deployText = @'
VDS DEPLOY - Toxido Anti Cheat backend 0.2.5
=============================================

Bu zip sunucuya yuklenecek backend dosyalarini icerir.
Paketteki backend\.env VDS icin hazirdir:
  HOST=0.0.0.0
  PORT=25364
  DRY_RUN=0
API anahtarlari ve Discord token'i doludur. ZIP GIZLIDIR; kimseyle
paylasmayin, yukledikten sonra yerelde silmeniz onerilir.

1) bot-hosting.cloud panelinde dosya yoneticisini ac (dugum fi1).
2) Zip'i /home/container icine yukle ve ac. Boylece /home/container/backend
   olusur.
3) Sunucudaki eski data/ klasorune DOKUNMA.
4) Baslat: bash backend/start.sh
   (panelin startup komutu python main.py ise: cd backend && python main.py)
5) Saglik: http://65.108.39.249:25364/health
   Beklenen: {"ok":true,"version":"0.2.5","bot_ready":true,...}

Not: Panel SERVER_PORT verirse backend PORT=25364 yerine onu kullanir.
Ayrinti: DEPLOY_VDS.md
'@
[IO.File]::WriteAllText((Join-Path $stage 'DEPLOY.txt'), $deployText, $utf8NoBom)

$deployDoc = Join-Path $root 'DEPLOY_VDS.md'
if (Test-Path $deployDoc) {
    Copy-Item $deployDoc $stage -Force
}

$outputFull = [IO.Path]::GetFullPath($Output)
$outputDir = Split-Path $outputFull -Parent
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
if (Test-Path $outputFull) {
    Remove-Item $outputFull -Force
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory(
    $stage,
    $outputFull,
    [IO.Compression.CompressionLevel]::Optimal,
    $false
)

$hash = (Get-FileHash $outputFull -Algorithm SHA256).Hash
$size = (Get-Item $outputFull).Length
Write-Host ""
Write-Host "Hazir: $outputFull"
Write-Host "Boyut: $([math]::Round($size / 1KB, 1)) KB"
Write-Host "SHA-256: $hash"
Write-Host "Paket icerigi: $stage"
