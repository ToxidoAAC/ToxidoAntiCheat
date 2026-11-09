. "$PSScriptRoot\dev-env.ps1"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$configPath = Join-Path $root 'ac-app\config.local.properties'
if (-not (Test-Path $configPath)) {
    $configPath = Join-Path $root 'ac-app\config.properties'
}

$java = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
$arguments = "--add-modules jdk.attach `"-Dac.config=$configPath`" " +
    "`"-Dac.agent.jar=$root\ac-agent\target\ac-agent.jar`" " +
    "-jar `"$root\ac-app\target\ac-app.jar`""

Start-Process -FilePath $java -ArgumentList $arguments `
    -WorkingDirectory $root -WindowStyle Hidden
exit 0
