# Toxic local test launcher.
# Starts the local backend if needed, then runs the AC EXE against it.
# Secrets are read from backend\.env and never printed.

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$exe = Join-Path $root 'dist\40x1d0AC.exe'
$backend = Join-Path $root 'backend'
$envFile = Join-Path $backend '.env'
$healthUrl = 'http://127.0.0.1:8000/health'

function Test-Backend {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        return [bool]$response.ok
    } catch {
        return $false
    }
}

if (-not (Test-Path -LiteralPath $exe)) {
    Write-Host "EXE bulunamadi: $exe" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Host "backend\.env bulunamadi: $envFile" -ForegroundColor Red
    exit 1
}

if (-not (Test-Backend)) {
    Write-Host 'Yerel backend baslatiliyor...' -ForegroundColor Yellow
    $python = Join-Path $backend '.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        Write-Host "Python venv bulunamadi: $python" -ForegroundColor Red
        exit 1
    }
    Start-Process -FilePath $python -ArgumentList '-m', 'app.main' `
        -WorkingDirectory $backend -WindowStyle Minimized | Out-Null

    $ok = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Backend) {
            $ok = $true
            break
        }
    }
    if (-not $ok) {
        Write-Host 'Backend 30 saniyede acilmadi. Pencereyi kontrol et.' -ForegroundColor Red
        exit 1
    }
}

$health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
$map = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $map[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
    }
}

$env:AC_API_URL = 'http://127.0.0.1:8000'
$env:AC_API_KEY = $map['API_KEY']
$env:AC_API_SECRET = $map['API_SECRET']

Write-Host "Backend hazir: surum $($health.version), bot_ready=$($health.bot_ready), oturum=$($health.active_sessions)" -ForegroundColor Green
Write-Host 'Anti-cheat baslatiliyor (pencere yok, Discord kanalini izle)...' -ForegroundColor Green
Start-Process -FilePath $exe | Out-Null
Write-Host 'Simdi Minecraft Fabric 26.3 ac, sunucuya gir.' -ForegroundColor Cyan
