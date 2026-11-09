param(
    [string]$Output = "",
    [string]$ApiBaseUrl = "",
    [switch]$NoObfuscate,
    [string]$ProguardJar = "",
    [string]$SkidJar = ""
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'scripts\dev-env.ps1')

if (-not $Output) {
    $Output = Join-Path $root 'dist\40x1d0AC.exe'
}

if (-not $ProguardJar) {
    $ProguardJar = Join-Path $env:USERPROFILE 'tools\obfuscation\proguard-7.10.0\lib\proguard.jar'
}
if (-not $SkidJar) {
    $SkidJar = Join-Path $env:USERPROFILE 'tools\obfuscation\skidfuscator-2.0.11.jar'
}

$build = Join-Path $root 'build\exe'
$obfuscation = Join-Path $root 'build\obfuscation'
$stage = Join-Path $build 'payload'
$runtime = Join-Path $stage 'runtime'
$appDir = Join-Path $stage 'app'
$zipPath = Join-Path $build 'payload.zip'
$iconPath = Join-Path $build '40x1d0AC.ico'
$launcherSource = Join-Path $build 'Launcher.cs'
$skidConfig = Join-Path $root 'scripts\obfuscation\skid.conf'
$rawAppJar = Join-Path $root 'ac-app\target\ac-app.jar'
$rawAgentJar = Join-Path $root 'ac-agent\target\ac-agent.jar'
$obfuscatedAppJar = Join-Path $obfuscation 'ac-app-skid.jar'
$obfuscatedAgentJar = Join-Path $obfuscation 'ac-agent-skid.jar'
$clientConfigSource = Join-Path $root 'ac-app\config.local.properties'
if (-not (Test-Path $clientConfigSource)) {
    $clientConfigSource = Join-Path $root 'ac-app\config.properties'
}

if (-not (Test-Path $clientConfigSource)) {
    throw "Istemci config bulunamadi: $clientConfigSource"
}

# Gercek API anahtarlarini Maven'e Base64 olarak gecir; kaynak kodda ve
# client.properties icinde duz metin secret kalmasin.
$secretProps = @{}
Get-Content $clientConfigSource | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        $secretProps[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$mavenArgs = @('-B', '-q', '-DskipTests')
$apiKey = $secretProps['api.key']
$apiSecret = $secretProps['api.secret']
if (-not [string]::IsNullOrWhiteSpace($apiKey) -and
    -not [string]::IsNullOrWhiteSpace($apiSecret)) {
    $keyB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($apiKey))
    $secretB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($apiSecret))
    $mavenArgs += "-Dapi.key.b64=$keyB64"
    $mavenArgs += "-Dapi.secret.b64=$secretB64"
}
else {
    Write-Warning 'api.key/api.secret bulunamadi; gelistirme varsayilanlari kullanilacak.'
}

if (Test-Path $build) {
    Remove-Item $build -Recurse -Force
}
if (Test-Path $obfuscation) {
    Remove-Item $obfuscation -Recurse -Force
}

Write-Output 'Maven paketi derleniyor...'
Push-Location $root
try {
    & mvn @mavenArgs clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven basarisiz (exit $LASTEXITCODE)."
    }
}
finally {
    Pop-Location
}

$selectedAppJar = $rawAppJar
$selectedAgentJar = $rawAgentJar

if (-not $NoObfuscate) {
    if (-not (Test-Path $ProguardJar)) {
        throw "ProGuard bulunamadi: $ProguardJar"
    }
    if (-not (Test-Path $SkidJar)) {
        throw "Skidfuscator bulunamadi: $SkidJar"
    }

    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    New-Item -ItemType Directory -Path $obfuscation -Force | Out-Null

    Write-Output 'ProGuard ile sinif/metot isimleri gizleniyor...'
    Push-Location $root
    try {
        & $java -jar $ProguardJar '@scripts\obfuscation\proguard-app.pro'
        if ($LASTEXITCODE -ne 0) {
            throw "ProGuard (app) basarisiz (exit $LASTEXITCODE)."
        }
        & $java -jar $ProguardJar '@scripts\obfuscation\proguard-agent.pro'
        if ($LASTEXITCODE -ne 0) {
            throw "ProGuard (agent) basarisiz (exit $LASTEXITCODE)."
        }
    }
    finally {
        Pop-Location
    }

    Write-Output 'Skidfuscator ile string/flow obfuscation uygulaniyor...'
    # Skidfuscator kendi calisma dizininde `mappings/` onbellegi olusturur;
    # depoyu kirletmemek icin build/obfuscation altinda calistir.
    $skidConfigLocal = Join-Path $obfuscation 'skid.conf'
    Copy-Item $skidConfig $skidConfigLocal -Force
    Push-Location $obfuscation
    try {
        & $java -Xmx2g -jar $SkidJar obfuscate `
            (Join-Path $obfuscation 'ac-app.jar') `
            "--config=$skidConfigLocal" `
            "--output=$obfuscatedAppJar" `
            '--notrack'
        if ($LASTEXITCODE -ne 0) {
            throw "Skidfuscator (app) basarisiz (exit $LASTEXITCODE)."
        }
        & $java -Xmx2g -jar $SkidJar obfuscate `
            (Join-Path $obfuscation 'ac-agent.jar') `
            "--config=$skidConfigLocal" `
            "--output=$obfuscatedAgentJar" `
            '--notrack'
        if ($LASTEXITCODE -ne 0) {
            throw "Skidfuscator (agent) basarisiz (exit $LASTEXITCODE)."
        }
    }
    finally {
        Pop-Location
    }

    if (-not (Test-Path $obfuscatedAppJar)) {
        throw "Obfuscated app jar bulunamadi: $obfuscatedAppJar"
    }
    if (-not (Test-Path $obfuscatedAgentJar)) {
        throw "Obfuscated agent jar bulunamadi: $obfuscatedAgentJar"
    }
    $selectedAppJar = $obfuscatedAppJar
    $selectedAgentJar = $obfuscatedAgentJar
}
else {
    Write-Warning 'NoObfuscate secildi; ham jar dosyalari paketlenecek.'
}

New-Item -ItemType Directory -Path $appDir -Force | Out-Null

Write-Output 'Java runtime olusturuluyor...'
$modules = 'java.base,java.compiler,java.net.http,java.sql,jdk.attach,jdk.unsupported,java.logging,java.management'
& (Join-Path $env:JAVA_HOME 'bin\jlink.exe') `
    --add-modules $modules `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2 `
    --output $runtime
if ($LASTEXITCODE -ne 0) {
    throw "jlink basarisiz (exit $LASTEXITCODE)."
}

Write-Output 'Uygulama dosyalari hazirlaniyor...'
Copy-Item $selectedAppJar (Join-Path $appDir 'ac-app.jar')
Copy-Item $selectedAgentJar (Join-Path $appDir 'ac-agent.jar')
$clientConfig = Join-Path $appDir 'client.properties'
Copy-Item $clientConfigSource $clientConfig

# Secret'lar jar icine gomuldu; istemciye giden config'de duz metin kalmasin.
$lines = Get-Content $clientConfig | Where-Object {
    $_ -notmatch '^\s*api\.(key|secret)\s*='
}
if ($ApiBaseUrl) {
    $lines = $lines | ForEach-Object {
        if ($_ -match '^api\.baseUrl=') {
            "api.baseUrl=$ApiBaseUrl"
        }
        else {
            $_
        }
    }
}
Set-Content -Path $clientConfig -Value $lines -Encoding ASCII

if ((Get-Content $clientConfig -Raw) -match '(?m)^\s*api\.(key|secret)\s*=') {
    throw 'client.properties icinde hala duz metin api.key/api.secret var.'
}

Write-Output 'Giris ve modul yardimcilari derleniyor...'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) {
    throw "csc.exe bulunamadi: $csc"
}
$inputHelper = Join-Path $build '40x1d0AC-input.exe'
$moduleScanner = Join-Path $build '40x1d0AC-scan.exe'
& $csc /nologo /target:exe /platform:x64 /optimize+ `
    "/out:$inputHelper" `
    (Join-Path $root 'scripts\InputHelper.cs') `
    /r:System.dll /r:System.Core.dll
if ($LASTEXITCODE -ne 0) {
    throw "Giris yardimcisi derlenemedi (exit $LASTEXITCODE)."
}
& $csc /nologo /target:exe /platform:x64 /optimize+ `
    "/out:$moduleScanner" `
    (Join-Path $root 'scripts\ModuleScanner.cs') `
    /r:System.dll /r:System.Core.dll
if ($LASTEXITCODE -ne 0) {
    throw "Modul tarayicisi derlenemedi (exit $LASTEXITCODE)."
}
Copy-Item $inputHelper (Join-Path $appDir '40x1d0AC-input.exe') -Force
Copy-Item $moduleScanner (Join-Path $appDir '40x1d0AC-scan.exe') -Force

Write-Output 'Ajan hook jar hazirlaniyor...'
$hookStage = Join-Path $build 'hook'
if (Test-Path $hookStage) {
    Remove-Item $hookStage -Recurse -Force
}
New-Item -ItemType Directory -Path $hookStage -Force | Out-Null
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
Push-Location $hookStage
try {
    & $jar xf $selectedAgentJar `
        'dev/toxido/anticheat/agent/HookBridge.class' `
        'dev/toxido/anticheat/agent/AttackEvent.class'
    if ($LASTEXITCODE -ne 0) {
        throw "Hook siniflari ajan jar'indan cikarilamadi (exit $LASTEXITCODE)."
    }

    $hookJar = Join-Path $appDir 'ac-agent-hook.jar'
    if (Test-Path $hookJar) {
        Remove-Item $hookJar -Force
    }
    & $jar cf $hookJar `
        'dev/toxido/anticheat/agent/HookBridge.class' `
        'dev/toxido/anticheat/agent/AttackEvent.class'
    if ($LASTEXITCODE -ne 0) {
        throw "Hook jar olusturulamadi (exit $LASTEXITCODE)."
    }
}
finally {
    Pop-Location
}

$hookEntries = & $jar tf (Join-Path $appDir 'ac-agent-hook.jar')
if (-not ($hookEntries -contains 'dev/toxido/anticheat/agent/HookBridge.class') -or
    -not ($hookEntries -contains 'dev/toxido/anticheat/agent/AttackEvent.class')) {
    throw 'Hook jar beklenen siniflari icermiyor.'
}
if ($hookEntries -match 'AgentMain|AgentClient|Phase2') {
    throw 'Hook jar icinde ajan siniflari olmamali.'
}

$payloadId = [Guid]::NewGuid().ToString('N')
Set-Content -Path (Join-Path $stage 'payload.id') -Value $payloadId -Encoding ASCII -NoNewline

Write-Output 'Paket sikistiriliyor...'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stage,
    $zipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false
)

Write-Output 'Ikon kopyalaniyor...'
$iconSource = Join-Path $root 'assets\40x1d0AC.ico'
if (-not (Test-Path $iconSource)) { throw "Ikon bulunamadi: $iconSource" }
Copy-Item $iconSource $iconPath -Force
Write-Output 'Baslatici derleniyor...'
$template = Get-Content (Join-Path $root 'scripts\Launcher.cs') -Raw
$source = $template.Replace('__PAYLOAD_ID__', $payloadId)
[System.IO.File]::WriteAllText(
    $launcherSource,
    $source,
    (New-Object System.Text.UTF8Encoding($true))
)

$dist = Split-Path -Parent $Output
if (-not (Test-Path $dist)) {
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
}

$cscArgs = @(
    '/nologo'
    '/target:exe'
    '/platform:x64'
    '/optimize+'
    "/win32icon:$iconPath"
    "/resource:$zipPath,payload.zip"
    '/r:System.IO.Compression.dll'
    '/r:System.IO.Compression.FileSystem.dll'
    "/out:$Output"
    $launcherSource
)
& $csc $cscArgs
if ($LASTEXITCODE -ne 0) {
    throw "csc basarisiz (exit $LASTEXITCODE)."
}

$file = Get-Item $Output
$hash = (Get-FileHash $Output -Algorithm SHA256).Hash
Write-Output ''
Write-Output "Hazir: $($file.FullName)"
Write-Output ("Boyut: {0:N1} MB" -f ($file.Length / 1MB))
Write-Output "SHA-256: $hash"
Write-Output "Payload: $payloadId"
