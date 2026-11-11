param(
    [string]$Profile = 'hadibakim',
    [string]$World = 'Yeni Dünya',
    [int]$MemoryMB = 1536,
    [switch]$NoWorld,
    [int]$Width = 854,
    [int]$Height = 480
)

$ErrorActionPreference = 'Stop'
$mc = Join-Path $env:APPDATA '.minecraft'
$versionDir = Join-Path $mc "versions\$Profile"
$jsonPath = Join-Path $versionDir "$Profile.json"
if (-not (Test-Path $jsonPath)) { throw "Profil JSON bulunamadi: $jsonPath" }

$prof = Get-Content $jsonPath -Raw | ConvertFrom-Json
$gameDir = if ($Profile -eq 'hadibakim') { $versionDir } else { $mc }
$natives = Join-Path $versionDir 'natives'

$cp = New-Object System.Collections.Generic.List[string]
$missing = 0
foreach ($lib in $prof.libraries) {
    $path = $null
    if ($lib.downloads -and $lib.downloads.artifact -and $lib.downloads.artifact.path) {
        $path = $lib.downloads.artifact.path
    } elseif ($lib.name) {
        $parts = $lib.name.Split(':')
        if ($parts.Count -ge 3) {
            $group = $parts[0].Replace('.', '/')
            $art = $parts[1]
            $ver = $parts[2]
            $classifier = if ($parts.Count -ge 4) { '-' + $parts[3] } else { '' }
            $path = "$group/$art/$ver/$art-$ver$classifier.jar"
        }
    }
    if ($path) {
        $full = Join-Path $mc "libraries\$($path.Replace('/', '\'))"
        if (Test-Path $full) { $cp.Add($full) } else { $missing++ }
    }
}
$jar = Join-Path $versionDir "$Profile.jar"
if (Test-Path $jar) { $cp.Add($jar) }

$java = (Get-ChildItem (Join-Path $mc 'runtime\java-runtime-epsilon') -Recurse -Filter java.exe |
    Select-Object -First 1).FullName
if (-not $java) { throw 'Java 25 runtime bulunamadi' }

$argList = New-Object System.Collections.Generic.List[string]
$argList.Add('-Xms256M')
$argList.Add("-Xmx${MemoryMB}M")
$argList.Add('-XX:MaxMetaspaceSize=256M')
$argList.Add('-XX:ReservedCodeCacheSize=64M')
$argList.Add("-Djava.library.path=$(($natives) -replace '\\', '/')")
$argList.Add('-DFabricMcEmu= net.minecraft.client.main.Main')
$argList.Add('-cp')
$argList.Add(($cp -join ';'))
$argList.Add($prof.mainClass)
$argList.Add('--username')
$argList.Add('T0x1d0')
$argList.Add('--version')
$argList.Add($Profile)
$argList.Add('--gameDir')
$argList.Add($gameDir)
$argList.Add('--assetsDir')
$argList.Add((Join-Path $mc 'assets'))
$argList.Add('--assetIndex')
$argList.Add([string]$prof.assetIndex.id)
$argList.Add('--uuid')
$argList.Add('00000000-0000-0000-0000-000000000000')
$argList.Add('--accessToken')
$argList.Add('0')
$argList.Add('--clientId')
$argList.Add('0')
$argList.Add('--xuid')
$argList.Add('0')
$argList.Add('--userType')
$argList.Add('legacy')
$argList.Add('--versionType')
$argList.Add('release')
$argList.Add('--width')
$argList.Add([string]$Width)
$argList.Add('--height')
$argList.Add([string]$Height)
if ($World -and -not $NoWorld) {
    $argList.Add('--quickPlaySingleplayer')
    $argList.Add($World)
}

$buildDir = Join-Path $PSScriptRoot '..\build'
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
$argFile = Join-Path $buildDir 'mc-test.args'
$lines = foreach ($arg in $argList) {
    $clean = ($arg -replace '\\', '/') -replace '"', '\"'
    '"' + $clean + '"'
}
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

"Profil: $Profile"
"GameDir: $gameDir"
"Classpath: $($cp.Count) girdi, eksik kutuphane: $missing"
"Java: $java"
"Argfile: $argFile"

$stdout = Join-Path $buildDir 'mc-stdout.log'
$stderr = Join-Path $buildDir 'mc-stderr.log'
$process = Start-Process -FilePath $java `
    -ArgumentList "@$argFile" `
    -WorkingDirectory $gameDir `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -PassThru
"Minecraft PID: $($process.Id)"
