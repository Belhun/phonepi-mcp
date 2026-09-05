# Build PhonePi debug APK and install over wireless ADB (USB-free / Tailscale).
# Usage:
#   .\wireless-install.ps1 -PhoneIp 100.105.121.46
#   .\wireless-install.ps1 -PhoneIp 100.105.121.46 -Port 5555
#   .\wireless-install.ps1 -PhoneIp 100.105.121.46 -SkipBuild
#
# If connect fails, pair first (Developer options → Wireless debugging):
#   adb pair <ip>:<pairing_port>    # enter 6-digit code from phone
#   adb connect <ip>:<connection_port>
#
# Tailscale testing: set PhonePi Host to your PC's Tailscale IP (not 127.0.0.1).
# adb reverse is optional over Tailscale; the phone reaches the PC directly.

param(
    [Parameter(Mandatory = $true)]
    [string]$PhoneIp,

    [int]$Port = 5555,

    [switch]$SkipBuild,

    [switch]$SkipReverse
)

$ErrorActionPreference = "Stop"
$androidDir = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"

function Resolve-PhonePiJavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += Get-ChildItem 'F:\Java' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object { $_.FullName }
    $candidates += 'F:\AndroidStudio\android-studio\jbr'
    $candidates += 'C:\Program Files\Android\Android Studio\jbr'
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\java.exe'))) { return $c }
    }
    return $null
}
$resolvedJava = Resolve-PhonePiJavaHome
if ($resolvedJava) { $env:JAVA_HOME = $resolvedJava }
$env:ANDROID_SDK_ROOT = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "F:\AndroidSDKManager" }
$env:PATH = "$env:ANDROID_SDK_ROOT\platform-tools;$env:PATH"

function Test-AdbDevice {
    $lines = & adb devices 2>&1 | Select-String "\tdevice$"
    return [bool]$lines
}

Write-Host "=== Wireless ADB ===" -ForegroundColor Cyan
if (-not (Test-AdbDevice)) {
    Write-Host "Connecting to ${PhoneIp}:${Port} ..."
    & adb connect "${PhoneIp}:${Port}"
    Start-Sleep -Seconds 2
}

if (-not (Test-AdbDevice)) {
    Write-Host ""
    Write-Host "No device connected." -ForegroundColor Red
    Write-Host "Enable Wireless debugging on the phone, then either:"
    Write-Host "  1. Pair: adb pair <ip>:<pairing_port>  (6-digit code on phone)"
    Write-Host "     Then: adb connect <ip>:<connection_port>"
    Write-Host "  2. USB once: adb tcpip 5555, unplug USB, adb connect ${PhoneIp}:5555"
    exit 1
}

& adb devices -l

if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "=== Build debug APK ===" -ForegroundColor Cyan
    Push-Location $androidDir
    try {
        & .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    } finally {
        Pop-Location
    }
}

if (-not $SkipReverse) {
    Write-Host ""
    Write-Host "=== adb reverse (optional for localhost testing) ===" -ForegroundColor Cyan
    & adb reverse tcp:11041 tcp:11041
}

Write-Host ""
Write-Host "=== Install ===" -ForegroundColor Cyan
& adb install -r $apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Done. For Tailscale: Host = PC Tailscale IP, Port = 11041" -ForegroundColor Green
$pcIp = & tailscale ip -4 2>$null
if ($pcIp) {
    Write-Host "This PC Tailscale IP: $pcIp"
}
