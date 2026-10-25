[CmdletBinding()]
param (
    [Parameter(Position = 0, Mandatory = $false)]
    [string]$TvIp = "192.168.31.54",
    [Parameter(Mandatory = $false)]
    [switch]$SkipApkInstall
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($TvIp)) { $TvIp = "192.168.31.54" }

Write-Host "=== Applying Liquid Glass Theme to TV at $TvIp ===" -ForegroundColor Cyan

# 1. Locate ADB
$adb = "adb.exe"
$sdkAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (Test-Path $sdkAdb) {
    $adb = $sdkAdb
}

# 2. Connect to TV
$target = "$($TvIp):5555"
Write-Host "[1/4] Connecting to TV at $target..." -ForegroundColor Yellow
& $adb connect $target

# 3. Install release APK (contains liquid glass layout, dock isolation, vivid glow)
$pkg = "com.gathin.tvhome"
$apkPath = Join-Path $PSScriptRoot "app-release.apk"
if (-not $SkipApkInstall -and (Test-Path $apkPath)) {
    Write-Host "[2/4] Installing CleanYourTV Liquid Glass APK..." -ForegroundColor Yellow
    & $adb -s $target install -r -d $apkPath
    Write-Host "  -> Granting Call Overlay & Bluetooth permissions..." -ForegroundColor Yellow
    & $adb -s $target shell "pm grant $pkg android.permission.BLUETOOTH_CONNECT 2>/dev/null || true"
    & $adb -s $target shell "pm grant $pkg android.permission.BLUETOOTH_SCAN 2>/dev/null || true"
    & $adb -s $target shell "pm grant $pkg android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true"
    & $adb -s $target shell "pm grant $pkg android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null || true"
    & $adb -s $target shell "appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true"
} else {
    Write-Host "[2/4] Skipping APK install..." -ForegroundColor DarkGray
}

# 4. Push theme config and wallpaper
Write-Host "[3/4] Staging and pushing theme settings and wallpaper..." -ForegroundColor Yellow
$pkg = "com.gathin.tvhome"
$dir = "/sdcard/Android/data/$pkg/files"
$uri = "content://$pkg.theme"
$tmp = "/data/local/tmp/cyt-theme"

$cfgPath = Join-Path $PSScriptRoot "config.json"
if (Test-Path $cfgPath) {
    & $adb -s $target push $cfgPath "$tmp-config.json" | Out-Null
    & $adb -s $target shell "mkdir -p $dir 2>/dev/null; cp $tmp-config.json $dir/config.json 2>/dev/null || content write --uri $uri/config.json < $tmp-config.json; rm -f $tmp-config.json"
}

$wallPath = Join-Path $PSScriptRoot "wallpaper.jpg"
if (Test-Path $wallPath) {
    & $adb -s $target push $wallPath "$tmp-wallpaper.jpg" | Out-Null
    & $adb -s $target shell "cp $tmp-wallpaper.jpg $dir/wallpaper.jpg 2>/dev/null || content write --uri $uri/wallpaper.jpg < $tmp-wallpaper.jpg; rm -f $tmp-wallpaper.jpg"
}

# Push full wallpaper gallery for rotation
$wallpapersDir = Join-Path $PSScriptRoot "wallpapers"
if (Test-Path $wallpapersDir -PathType Container) {
    Write-Host "  -> Staging wallpapers rotation gallery..." -ForegroundColor Yellow
    & $adb -s $target shell "mkdir -p $dir/wallpapers 2>/dev/null; rm -f $dir/wallpapers/* 2>/dev/null; content delete --uri $uri/wallpapers 2>/dev/null"
    Get-ChildItem $wallpapersDir -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' } | ForEach-Object {
        $staged = "$tmp-wallpapers-$($_.Name)"
        & $adb -s $target push $_.FullName $staged | Out-Null
        & $adb -s $target shell "cp $staged $dir/wallpapers/$($_.Name) 2>/dev/null || content write --uri $uri/wallpapers/$($_.Name) < $staged; rm -f $staged"
    }
}

$revision = [guid]::NewGuid().ToString('N')
& $adb -s $target shell "printf '%s' '$revision' > $tmp-theme_revision; cp $tmp-theme_revision $dir/theme_revision 2>/dev/null || content write --uri $uri/theme_revision < $tmp-theme_revision; rm -f $tmp-theme_revision"

# 5. Restart Launcher
Write-Host "[4/4] Restarting CleanYourTV launcher..." -ForegroundColor Yellow
& $adb -s $target shell "am force-stop $pkg; am start -n $pkg/.MainActivity"

Write-Host "=== Done! Liquid Glass theme is live on your TV ===" -ForegroundColor Green
