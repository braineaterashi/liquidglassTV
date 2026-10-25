# Liquid Glass TV Setup & AI Preset

> **Target Device**: TCL 55C745 (G08) Google TV / Android 14 TV  
> **Device IP**: `192.168.31.54:5555`  
> **Launcher**: CleanYourTV (`com.gathin.tvhome`) with Liquid Glass Customizations  

---

## 1. Quick One-Line PowerShell Commands

### Option A: Run Local Restorer Script
Run this single line in Windows PowerShell to connect to your TV, install the APK, and apply all theme settings & wallpaper:
```powershell
powershell -ExecutionPolicy Bypass -File "C:\Users\brain\.gemini\antigravity\scratch\LiquidGlass_Backup\Apply-LiquidGlass.ps1"
```

### Option B: Direct PowerShell One-Liner (Standalone)
If running from anywhere without navigating folders:
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; & $adb connect 192.168.31.54:5555; & $adb -s 192.168.31.54:5555 push "C:\Users\brain\.gemini\antigravity\scratch\LiquidGlass_Backup\config.json" /data/local/tmp/cyt-theme-config.json; & $adb -s 192.168.31.54:5555 shell "cp /data/local/tmp/cyt-theme-config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json 2>/dev/null || content write --uri content://com.gathin.tvhome.theme/config.json < /data/local/tmp/cyt-theme-config.json; rm -f /data/local/tmp/cyt-theme-config.json; am force-stop com.gathin.tvhome && am start -n com.gathin.tvhome/.MainActivity"
```

---

## 2. Configuration (`config.json`)
Saved at: `C:\Users\brain\.gemini\antigravity\scratch\LiquidGlass_Backup\config.json`
```json
{
  "greeting_name": "Ashish ❤️",
  "live_tv_package": "com.tcl.tv",
  "focus_overshoot": true,
  "app_glow": true,
  "dock_apps": [
    "com.netflix.ninja",
    "com.google.android.youtube.tv",
    "com.amazon.amazonvideo.livingroom",
    "in.startv.hotstar",
    "com.sonyliv",
    "com.stremio.one"
  ],
  "show_network_pill": false,
  "ambient_glow": false,
  "always_show_names": true,
  "columns": 6,
  "advanced_effects": true,
  "dock_title": false,
  "app_labels": [
    "com.netflix.ninja=Netflix",
    "com.google.android.youtube.tv=YouTube",
    "com.amazon.amazonvideo.livingroom=Prime Video",
    "in.startv.hotstar=Disney+",
    "com.sonyliv=SonyLIV",
    "com.stremio.one=Stremio",
    "com.tcl.tv=PlayStation 5"
  ],
  "show_inputs_pill": false,
  "dock_glass": true,
  "rows_below_dock": 0,
  "wallpaper_dim": 20,
  "dock_opacity": 40,
  "wallpaper_true_color": true,
  "wallpaper": true,
  "wallpaper_rotation_minutes": 60,
  "wallpaper_order": "shuffle",
  "fill_dock": false,
  "icon_only_buttons": true,
  "background_color": "#030406"
}
```

---

## 3. Key Design & Engineering Rules (For AI Context)
When feeding this to an AI assistant in future conversations:

1. **Brand Authenticity**:
   - Never replace official app banners or icons with simplified/custom vector shapes.
   - Always load authentic app artwork directly from the APK packages.
2. **Liquid Glass Icon Translucency**:
   - `tile_bg.xml` uses `<solid android:color="#2014161C" />` with specular rim `<stroke android:width="1dp" android:color="#2EFFFFFF" />` and `18dp` corner radius.
   - Dynamic banner/icon alpha in `AppAdapter.java`: `0.68f` unfocused, `0.88f` focused.
3. **Dock Row Isolation**:
   - In `DockDecoration.java`: `outRect.bottom = (int) ((rowsBelowDock == 0 ? 70 : 12) * density);`.
   - `rows_below_dock` set to `0` so row 1 stays completely off-screen on home view until scrolling down.
4. **Specular Neon Border Focus**:
   - In `GlassView.java`: 16 smooth concentric rings with high alpha (`0xD8`), rim stroke `2.2dp`, `ambient_glow: false` so only the icon border illuminates with brand accent without bleeding across the wallpaper.
5. **Debloated System State**:
   - Disabled stock Google TV launcher (`com.google.android.apps.tv.launcherx`), setupwraith, TCL telemetry/push services (`com.tcl.guard`, `com.tcl.messagebox`, `com.tcl.waterfall.overseas`), and Privacy Sandbox tracking.
   - Default Home Activity permanently set to `com.gathin.tvhome/.MainActivity`.
6. **Personalized Ashish Greeting**:
   - `"greeting_name": "Ashish ❤️"` in `config.json`. Custom schedule:
     - `GOOD MORNING, ASHISH ❤️` (04:00 – 12:00)
     - `GOOD AFTERNOON, ASHISH ❤️` (12:00 – 16:00)
     - `GOOD EVENING, ASHISH ❤️` (16:00 – 21:00)
     - `GOOD NIGHT, ASHISH ❤️` (21:00 – 04:00)
7. **Static Clock with Seconds Counter & Live Kolkata Weather**:
   - Clock features a live seconds counter (`#clockSeconds` in 22sp `#80FFFFFF` outfit_light with fixed `38dp` width and `tnum` tabular numbers), completely eliminating any horizontal jitter or shaking in the adjacent date/weather column.
   - Minimalist aesthetic Kolkata weather line (`WeatherHelper.java` via Open-Meteo) below the date featuring condition icons that dynamically adapt between day and night: ☀️ Sunny, 🌙 Clear Night, ⛅ Partly Cloudy, 🌧️ Rain, ⛈️ Thunderstorm, 🌫️ Fog, ❄️ Snow (e.g. `⛅  30°C  PARTLY CLOUDY  •  KOLKATA`).
8. **Curated Aesthetic Wallpaper Rotation on Restart**:
   - Launcher modified in `WallpaperPlayer.java` so that on every launch/restart, `WallpaperRotation.pick()` selects a fresh wallpaper different from the previous one.
   - 5 curated high-contrast dark aesthetic wallpapers stored in `wallpapers/`:
     - `01_desert_night.jpg`: Milky Way caravan & rolling dunes
     - `02_cosmic_nebula.jpg`: Indigo & violet nebula with crescent moon
     - `03_aurora_fjord.jpg`: Emerald & violet northern lights over obsidian fjord
     - `04_oled_glass_refraction.jpg`: Deep OLED black fluid refractive glass ribbons
     - `05_coastal_highway.jpg`: Moonlit coastal highway over midnight ocean
9. **Liquid Glass Incoming Call HUD & iPhone ANCS Bluetooth Bridge**:
   - Dual-channel architecture for incoming call alerts on your TV:
     1. **Native Apple ANCS over Bluetooth LE** (`AncsBleManager.java`): TV pairs directly with iPhone; iOS streams incoming call packets (`CategoryID: 1`) and caller ID over Bluetooth Low Energy.
     2. **Local LAN Webhook Server** (`CallWebhookServer.java` on port `8088`): Accepts `GET/POST http://192.168.31.54:8088/call?caller=Name&number=Info` and `/dismiss` for iOS Shortcuts or LAN automations.
   - **Liquid Glass HUD** (`CallBannerManager.java`, `view_call_banner.xml`): Frosted glass translucent banner (`#E010131A` with `#35FFFFFF` specular rim stroke, `22dp` corners, pulsing emerald glowing badge 📞), smooth slide animation, auto-dismissing on call completion.

---

## 4. Backup Assets Directory
- **Folder Path**: `C:\Users\brain\.gemini\antigravity\scratch\LiquidGlass_Backup\`
- **Files Included**:
  - `app-release.apk` (Compiled APK with glass, dock isolation, and neon glow modifications)
  - `config.json` (Full theme settings)
  - `wallpaper.jpg` (Desert Night Milky Way 4K wallpaper)
  - `wallpapers/` (Gallery of 5 dark aesthetic wallpapers for automatic restart & timed rotation)
  - `Apply-LiquidGlass.ps1` (Self-contained PowerShell restorer)
  - `apply_liquid_glass.bat` (Double-click batch restorer)
  - `LIQUID_GLASS_PRESET.md` (This document)
