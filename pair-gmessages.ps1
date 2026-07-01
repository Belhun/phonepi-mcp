# Pair PhonePi with Google Messages (QR code).
# Run from repo root: .\pair-gmessages.ps1

$ErrorActionPreference = "Stop"
$BridgeDir = Join-Path $PSScriptRoot "messages-bridge"
$Exe = Join-Path $BridgeDir "phonepi-gmessages.exe"

if (-not (Test-Path $Exe)) {
    Write-Host "phonepi-gmessages.exe not found; building..." -ForegroundColor Yellow
    & (Join-Path $BridgeDir "build.ps1")
}

Push-Location $BridgeDir
try {
    & $Exe pair @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
