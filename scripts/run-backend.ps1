param(
    [switch]$DryRun
)

. "$PSScriptRoot\dev-env.ps1"
$root = Split-Path $PSScriptRoot -Parent
Set-Location "$root\backend"

if (-not (Test-Path '.venv')) {
    python -m venv .venv
}

& '.\.venv\Scripts\python.exe' -m pip install --upgrade pip
& '.\.venv\Scripts\python.exe' -m pip install -r requirements.txt

if ($DryRun) {
    $env:DRY_RUN = '1'
}

& '.\.venv\Scripts\python.exe' -m app.main
