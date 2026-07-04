# Stop services, rebuild PhonePi, install on device, open Test Console.
# Usage: .\refresh-and-test.ps1

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$androidDir = Join-Path $repoRoot "phonepi-android"
$apk = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
$consoleDir = Join-Path $PSScriptRoot "test-console"

function Stop-PortListener {
    param([int]$Port)
    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $connections) {
        $processId = $c.OwningProcess
        if ($processId -and $processId -ne 0) {
            Write-Host "Stopping process on port $Port (PID $processId)..."
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "=== 1. Stopping test services (ports 11041, 8765) ===" -ForegroundColor Cyan
Stop-PortListener 11041
Stop-PortListener 8765
Start-Sleep -Seconds 1

Write-Host ""
Write-Host "=== 2. Building debug APK ===" -ForegroundColor Cyan
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_SDK_ROOT = "F:\AndroidSDKManager"
Push-Location $androidDir
try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "=== 3. Installing on device (if connected) ===" -ForegroundColor Cyan
$devices = & adb devices 2>&1 | Select-String "device$"
if ($devices) {
    & adb reverse tcp:11041 tcp:11041
    & adb install -r $apk
} else {
    $installHint = 'adb install -r "' + $apk + '"'
    Write-Host "No device attached - skip install. Run: $installHint" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== 4. Starting Test Console ===" -ForegroundColor Cyan
Start-Process "http://127.0.0.1:8765"
Push-Location $consoleDir
try {
    & node server.js
} finally {
    Pop-Location
}
