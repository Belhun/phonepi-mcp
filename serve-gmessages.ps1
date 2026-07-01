# Start Google Messages sync server + web UI + HTTP tool bridge.
# Run from repo root: .\serve-gmessages.ps1

$ErrorActionPreference = "Stop"
$BridgeDir = Join-Path $PSScriptRoot "messages-bridge"
$Exe = Join-Path $BridgeDir "phonepi-gmessages.exe"

if (-not (Test-Path $Exe)) {
    Write-Host "phonepi-gmessages.exe not found; building..." -ForegroundColor Yellow
    & (Join-Path $BridgeDir "build.ps1")
}

Push-Location $BridgeDir
try {
    & $Exe serve @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
