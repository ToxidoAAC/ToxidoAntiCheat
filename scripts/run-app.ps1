. "$PSScriptRoot\dev-env.ps1"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
chcp 65001 > $null

$configPath = Join-Path $root 'ac-app\config.local.properties'
if (-not (Test-Path $configPath)) {
    $configPath = Join-Path $root 'ac-app\config.properties'
}

& "$env:JAVA_HOME\bin\java.exe" `
    --add-modules jdk.attach `
    "-Dac.config=$configPath" `
    "-Dac.agent.jar=$root\ac-agent\target\ac-agent.jar" `
    -jar "$root\ac-app\target\ac-app.jar"

exit $LASTEXITCODE
