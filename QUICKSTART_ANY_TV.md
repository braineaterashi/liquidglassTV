# CleanYourTV — Liquid Glass Edition & iPhone Call Notification Setup

This repository contains the complete custom **Liquid Glass Edition** of CleanYourTV with:
- **Frosted Liquid Glass UI**: Semi-translucent glass cards, OLED pure black backgrounds, specular borders, and transparent app docks.
- **iPhone Incoming Call Notification Engine**:
  - Connects to paired iPhones via **Apple Notification Center Service (ANCS)** over Bluetooth LE.
  - Automatically pops up an incoming call banner with the caller's name and number on the TV screen.
  - Native display on the **Homescreen** and floating system overlay across any running app (Netflix, YouTube, HDMI/PS5).
  - Background LAN HTTP Webhook bridge on port `8088`.
- **Custom Aesthetic Status Bar**:
  - Greeting text with custom time-of-day phases:
    - `04:00 – 12:00`: **GOOD MORNING, ASHISH ❤️**
    - `12:00 – 16:00`: **GOOD AFTERNOON, ASHISH ❤️**
    - `16:00 – 21:00`: **GOOD EVENING, ASHISH ❤️**
    - `21:00 – 04:00`: **GOOD NIGHT, ASHISH ❤️**
  - Jitter-free static layout: Date and weather maintain a fixed position; seconds tick smoothly without shaking or toggling.
  - Weather condition icons for Kolkata / local weather (clear night, sunny, rainy, cloudy, haze, thunderstorm).
- **Restart Wallpaper Rotation**:
  - Automatically cycles through 5 curated dark OLED 4K wallpapers on every TV restart or launcher reload.

---

## 🚀 Instant 1-Click Deployment (On Any New Google TV / Android TV)

You can set up all these changes on any Google TV / Android TV in under 1 minute from scratch without installing Android Studio or Java.

### Prerequisites:
1. Turn on **Developer Options** and enable **USB / Network Debugging** on the target Google TV:
   - Go to TV **Settings** → **System** → **About** → Click **Android TV OS build** 7 times.
   - Go to TV **Settings** → **System** → **Developer Options** → Enable **USB Debugging** & **Wireless Debugging**.
   - Note the TV's IP address (e.g., `192.168.1.50`).

### Quick Setup:
Run the PowerShell deployment script:
```powershell
cd deploy
.\Apply-LiquidGlass.ps1 -TvIp <TARGET_TV_IP>
```
*(Example: `.\Apply-LiquidGlass.ps1 -TvIp 192.168.31.54`)*

Or double-click `apply_liquid_glass.bat` on Windows and type in the TV's IP address when prompted.

**What the script does automatically**:
1. Connects to the TV over ADB (`<TV_IP>:5555`).
2. Installs the custom `app-release.apk` with all features included.
3. Grants necessary permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `SYSTEM_ALERT_WINDOW`).
4. Pushes theme config, wallpapers, and rotation files into `/sdcard/Android/data/com.gathin.tvhome/files/`.
5. Relaunches CleanYourTV as your active launcher.

---

## 📱 Pairing Your iPhone for Incoming Call Notifications

1. On your Google TV, go to **Settings** → **Remotes & Accessories** → **Pair accessory**.
2. On your iPhone, open **Settings** → **Bluetooth**.
3. Tap your TV in the list to pair.
4. On your iPhone, tap the **(i)** icon next to your TV and ensure **"Share System Notifications"** is turned **ON**.
5. That's it! Any incoming call to your iPhone will now instantly pop up on your TV screen.

---

## 🛠️ Building From Source

If you want to modify the source code:
1. Java 17 and Android SDK (Platform 34 / Android 14) are required.
2. Run `launcher/build_release.bat` or `./gradlew assembleRelease` inside the `launcher/` directory.
3. The generated APK will be output to `launcher/app/build/outputs/apk/release/app-release.apk`.
