# Builds the app, installs it on the connected phone and turns on the auto-play helper.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
}

Write-Host "Building..." -ForegroundColor Cyan
& "$PSScriptRoot\gradlew.bat" --no-daemon assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

Write-Host "Installing..." -ForegroundColor Cyan
& $adb install -r "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"

Write-Host "Enabling the auto-play accessibility service..." -ForegroundColor Cyan
& $adb shell settings put secure enabled_accessibility_services com.space.braveplay/com.space.braveplay.PlayClickerService
& $adb shell settings put secure accessibility_enabled 0
& $adb shell settings put secure accessibility_enabled 1

Write-Host "Granting Bluetooth + battery whitelist..." -ForegroundColor Cyan
& $adb shell pm grant com.space.braveplay android.permission.BLUETOOTH_CONNECT 2>$null
& $adb shell cmd deviceidle whitelist +com.space.braveplay 2>$null
& $adb shell cmd deviceidle whitelist +com.brave.browser 2>$null

Write-Host "Allowing media-button access (needed to forward play/pause to Brave)..." -ForegroundColor Cyan
& $adb shell cmd notification allow_listener com.space.braveplay/com.space.braveplay.MediaListenerService 2>$null

Write-Host "Starting app..." -ForegroundColor Cyan
& $adb shell am start -n com.space.braveplay/.MainActivity

Write-Host "Done. MUSIC ON keeps playing; the last chosen song is remembered." -ForegroundColor Green
