param(
    [string]$Output = "",
    [string]$ApiBaseUrl = ""
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'scripts\dev-env.ps1')

if (-not $Output) {
    $Output = Join-Path $root 'dist\40x1d0AC.exe'
}

$build = Join-Path $root 'build\exe'
$stage = Join-Path $build 'payload'
$runtime = Join-Path $stage 'runtime'
$appDir = Join-Path $stage 'app'
$zipPath = Join-Path $build 'payload.zip'
$iconPath = Join-Path $build 'ToxidoAC.ico'
$launcherSource = Join-Path $build 'Launcher.cs'

Write-Output 'Maven paketi derleniyor...'
Push-Location $root
try {
    & mvn -B -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven basarisiz (exit $LASTEXITCODE)."
    }
}
finally {
    Pop-Location
}

if (Test-Path $build) {
    Remove-Item $build -Recurse -Force
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
Copy-Item (Join-Path $root 'ac-app\target\ac-app.jar') (Join-Path $appDir 'ac-app.jar')
Copy-Item (Join-Path $root 'ac-agent\target\ac-agent.jar') (Join-Path $appDir 'ac-agent.jar')
$clientConfig = Join-Path $appDir 'client.properties'
$clientConfigSource = Join-Path $root 'ac-app\config.local.properties'
if (-not (Test-Path $clientConfigSource)) {
    $clientConfigSource = Join-Path $root 'ac-app\config.properties'
}
Copy-Item $clientConfigSource $clientConfig

if ($ApiBaseUrl) {
    $lines = Get-Content $clientConfig
    $lines = $lines | ForEach-Object {
        if ($_ -match '^api\.baseUrl=') {
            "api.baseUrl=$ApiBaseUrl"
        }
        else {
            $_
        }
    }
    Set-Content -Path $clientConfig -Value $lines -Encoding ASCII
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

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) {
    throw "csc.exe bulunamadi: $csc"
}

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
