@echo off
setlocal
set "TV_IP=192.168.31.54"
if not "%~1"=="" set "TV_IP=%~1"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

echo === Applying Liquid Glass Theme to TV at %TV_IP% ===

echo [1/4] Connecting to TV at %TV_IP%:5555...
"%ADB%" connect %TV_IP%:5555

echo [2/4] Installing CleanYourTV Liquid Glass APK...
if exist "%~dp0app-release.apk" (
    "%ADB%" -s %TV_IP%:5555 install -r -d "%~dp0app-release.apk"
)

echo [3/4] Staging and pushing Liquid Glass theme and wallpaper...
if exist "%~dp0config.json" (
    "%ADB%" -s %TV_IP%:5555 push "%~dp0config.json" /data/local/tmp/cyt-theme-config.json >nul
    "%ADB%" -s %TV_IP%:5555 shell "mkdir -p /sdcard/Android/data/com.gathin.tvhome/files 2>/dev/null; cp /data/local/tmp/cyt-theme-config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json 2>/dev/null || content write --uri content://com.gathin.tvhome.theme/config.json < /data/local/tmp/cyt-theme-config.json; rm -f /data/local/tmp/cyt-theme-config.json"
)

if exist "%~dp0wallpaper.jpg" (
    "%ADB%" -s %TV_IP%:5555 push "%~dp0wallpaper.jpg" /data/local/tmp/cyt-theme-wallpaper.jpg >nul
    "%ADB%" -s %TV_IP%:5555 shell "cp /data/local/tmp/cyt-theme-wallpaper.jpg /sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg 2>/dev/null || content write --uri content://com.gathin.tvhome.theme/wallpaper.jpg < /data/local/tmp/cyt-theme-wallpaper.jpg; rm -f /data/local/tmp/cyt-theme-wallpaper.jpg"
)

if exist "%~dp0wallpapers" (
    echo   -^> Pushing wallpapers gallery...
    "%ADB%" -s %TV_IP%:5555 shell "mkdir -p /sdcard/Android/data/com.gathin.tvhome/files/wallpapers 2>/dev/null; rm -f /sdcard/Android/data/com.gathin.tvhome/files/wallpapers/* 2>/dev/null; content delete --uri content://com.gathin.tvhome.theme/wallpapers 2>/dev/null"
    for %%F in ("%~dp0wallpapers\*.jpg" "%~dp0wallpapers\*.png" "%~dp0wallpapers\*.webp") do (
        if exist "%%F" (
            "%ADB%" -s %TV_IP%:5555 push "%%F" /data/local/tmp/cyt-theme-wallpapers-%%~nxF >nul
            "%ADB%" -s %TV_IP%:5555 shell "cp /data/local/tmp/cyt-theme-wallpapers-%%~nxF /sdcard/Android/data/com.gathin.tvhome/files/wallpapers/%%~nxF 2>/dev/null || content write --uri content://com.gathin.tvhome.theme/wallpapers/%%~nxF < /data/local/tmp/cyt-theme-wallpapers-%%~nxF; rm -f /data/local/tmp/cyt-theme-wallpapers-%%~nxF"
        )
    )
)

echo [4/4] Restarting launcher...
"%ADB%" -s %TV_IP%:5555 shell "am force-stop com.gathin.tvhome && am start -n com.gathin.tvhome/.MainActivity"

echo === Liquid Glass Theme Applied Successfully! ===
ping 127.0.0.1 -n 3 > nul
