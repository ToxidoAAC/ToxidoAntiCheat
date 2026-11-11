param(
    [string]$Profile = 'hadibakim',
    [string]$World = 'ACWorld',
    [int]$MemoryMB = 896,
    [switch]$NoWorld,
    [switch]$NoQuickPlay,
    [int]$Width = 854,
    [int]$Height = 480,
    [string]$GameDir = ''
)

$ErrorActionPreference = 'Stop'
$mc = Join-Path $env:APPDATA '.minecraft'
$versionDir = Join-Path $mc "versions\$Profile"
$jsonPath = Join-Path $versionDir "$Profile.json"
if (-not (Test-Path $jsonPath)) { throw "Profil JSON bulunamadi: $jsonPath" }

$prof = Get-Content $jsonPath -Raw | ConvertFrom-Json
$profileGameDir = if ($GameDir) { $GameDir } elseif ($Profile -eq 'hadibakim') { $versionDir } else { $mc }
$natives = Join-Path $versionDir 'natives'
New-Item -ItemType Directory -Force -Path $natives | Out-Null

function Test-RuleMatch([object]$rule) {
    if ($rule.os) {
        $osName = [string]$rule.os.name
        if ($osName -and $osName -ne 'windows') { return $false }
        if ($osName -eq 'windows' -and $rule.os.version) {
            $winVer = [Environment]::OSVersion.Version.ToString()
            if ($winVer -notmatch [string]$rule.os.version) { return $false }
        }
        if ($rule.os.arch) { return $false }
    }
    if ($rule.features) {
        foreach ($p in $rule.features.PSObject.Properties) {
            $want = [bool]$p.Value
            $have = $false
            if ($p.Name -eq 'has_custom_resolution') { $have = ($Width -gt 0 -and $Height -gt 0) }
            elseif ($p.Name -eq 'has_quick_plays_support') { $have = $false }
            elseif ($p.Name -eq 'is_demo_user') { $have = $false }
            if ($have -ne $want) { return $false }
        }
    }
    return $true
}

function Test-Allowed([object]$rules) {
    $list = @($rules | Where-Object { $null -ne $_ })
    if ($list.Count -eq 0) { return $true }
    $allowed = $false
    foreach ($r in $list) {
        if (Test-RuleMatch $r) { $allowed = ([string]$r.action -eq 'allow') }
    }
    return $allowed
}

$cp = New-Object System.Collections.Generic.List[string]
$missing = New-Object System.Collections.Generic.List[string]
foreach ($lib in $prof.libraries) {
    if (-not (Test-Allowed $lib.rules)) { continue }
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
        if (Test-Path $full) { $cp.Add($full) } else { $missing.Add($path) }
    }
}
$jar = Join-Path $versionDir "$Profile.jar"
if (Test-Path $jar) { $cp.Add($jar) }

$component = if ($prof.javaVersion -and $prof.javaVersion.component) { [string]$prof.javaVersion.component } else { 'java-runtime-epsilon' }
$java = (Get-ChildItem (Join-Path $mc "runtime\$component") -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1).FullName
if (-not $java) {
    $java = (Get-ChildItem (Join-Path $mc 'runtime') -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1).FullName
}
if (-not $java) { throw 'Java runtime bulunamadi' }

$buildDir = Join-Path $PSScriptRoot '..\build'
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
$tag = ($Profile -replace '[^A-Za-z0-9._-]', '_')
$argFile = Join-Path $buildDir "mc-$tag.args"

$assetsIndex = if ($prof.assetIndex -and $prof.assetIndex.id) { [string]$prof.assetIndex.id } else { [string]$prof.assets }

$script:argList = New-Object System.Collections.Generic.List[string]
$script:argList.Add('-Xms256M')
$script:argList.Add("-Xmx${MemoryMB}M")
$script:argList.Add('-XX:MaxMetaspaceSize=256M')
$script:argList.Add('-XX:ReservedCodeCacheSize=64M')
# Heap'i 4 GB altindaki sikismis adres alanindan cikarir; native malloc
# "insufficient memory" hatasini onler (hs_err: CompressedOops/HeapBase).
$script:argList.Add('-XX:-UseCompressedOops')
$script:argList.Add('-XX:TieredStopAtLevel=1')
$script:argList.Add("-XX:ErrorFile=$($buildDir -replace '\\','/')/hs_err_${tag}_%p.log")

function Expand-Placeholders([string]$value) {
    $map = @{
        '${natives_directory}' = ($natives -replace '\\', '/')
        '${launcher_name}' = 'ToxidoTest'
        '${launcher_version}' = '1.0'
        '${classpath}' = ($cp -join ';')
        '${classpath_separator}' = ';'
        '${library_directory}' = ((Join-Path $mc 'libraries') -replace '\\', '/')
        '${version_name}' = $Profile
        '${game_directory}' = ($profileGameDir -replace '\\', '/')
        '${assets_root}' = ((Join-Path $mc 'assets') -replace '\\', '/')
        '${assets_index_name}' = $assetsIndex
        '${auth_player_name}' = 'T0x1d0'
        '${auth_uuid}' = '00000000-0000-0000-0000-000000000000'
        '${auth_access_token}' = '0'
        '${clientid}' = '0'
        '${auth_xuid}' = '0'
        '${user_type}' = 'legacy'
        '${version_type}' = 'release'
        '${resolution_width}' = [string]$Width
        '${resolution_height}' = [string]$Height
    }
    foreach ($key in $map.Keys) { $value = $value.Replace($key, $map[$key]) }
    return $value
}

function Add-ArgumentEntries([object]$entries) {
    foreach ($entry in @($entries | Where-Object { $null -ne $_ })) {
        if ($entry -is [string]) {
            $script:argList.Add((Expand-Placeholders $entry))
            continue
        }
        if (-not (Test-Allowed $entry.rules)) { continue }
        foreach ($value in @($entry.values)) {
            $script:argList.Add((Expand-Placeholders ([string]$value)))
        }
    }
}

if ($prof.arguments -and $prof.arguments.jvm) { Add-ArgumentEntries $prof.arguments.jvm }
$script:argList.Add([string]$prof.mainClass)
if ($prof.arguments -and $prof.arguments.game) {
    Add-ArgumentEntries $prof.arguments.game
} elseif ($prof.minecraftArguments) {
    $tokens = [regex]::Matches([string]$prof.minecraftArguments, '"[^"]*"|\S+') | ForEach-Object { $_.Value.Trim('"') }
    foreach ($token in $tokens) { $script:argList.Add((Expand-Placeholders ([string]$token))) }
} else {
    $legacy = @('--username', 'T0x1d0', '--version', $Profile, '--gameDir', $profileGameDir,
        '--assetsDir', (Join-Path $mc 'assets'), '--assetIndex', $assetsIndex,
        '--uuid', '00000000-0000-0000-0000-000000000000', '--accessToken', '0',
        '--userType', 'legacy', '--versionType', 'release', '--width', [string]$Width, '--height', [string]$Height)
    foreach ($token in $legacy) { $script:argList.Add([string]$token) }
}

$versionText = if ($prof.id) { [string]$prof.id } else { $Profile }
$supportsQuickPlay = $false
if ($versionText -match '(\d+)\.(\d+)') {
    $maj = [int]$matches[1]
    $min = [int]$matches[2]
    $supportsQuickPlay = ($maj -gt 20) -or ($maj -eq 20) -or ($maj -eq 1 -and $min -ge 20)
}
if ($versionText -match '^(\d+)\.' -and [int]$matches[1] -ge 20) { $supportsQuickPlay = $true }

$quickWorld = $World -and -not $NoWorld -and -not $NoQuickPlay -and $supportsQuickPlay

$lines = foreach ($arg in $script:argList) {
    $clean = ($arg -replace '\\', '/') -replace '"', '\"'
    if ($clean -match '^[A-Za-z0-9_./:=+,@%{}~-]+$') { $clean } else { '"' + $clean + '"' }
}
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

"Profil: $Profile"
"GameDir: $profileGameDir"
"Runtime: $component"
"Classpath: $($cp.Count) girdi, eksik kutuphane: $($missing.Count)"
if ($missing.Count -gt 0) { $missing | Select-Object -First 10 | ForEach-Object { "  eksik: $_" } }
"QuickPlay: $(if ($quickWorld) { $World } elseif ($World -and -not $NoWorld) { 'desteklenmiyor, elle sec' } else { 'kapali' })"
"Argfile: $argFile"

$stdout = Join-Path $buildDir "mc-$tag-stdout.log"
$stderr = Join-Path $buildDir "mc-$tag-stderr.log"
$arguments = "@`"$argFile`""
if ($quickWorld) {
    $arguments += " --quickPlaySingleplayer `"$World`""
}
$process = Start-Process -FilePath $java `
    -ArgumentList $arguments `
    -WorkingDirectory $profileGameDir `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -PassThru
"Minecraft PID: $($process.Id)"
