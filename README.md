# 📱 Remote App Installer — AOSP System App

> Silently install, update, and uninstall Android apps across a fleet of devices — no user interaction, no Play Store, triggered remotely from a REST API.

---

## 🤔 The Problem

Managing apps on 100s of Android devices is painful.

- Shopfloor tablets running warehouse software need an update → someone physically touches every device
- Android Automotive vehicles need a silent OTA update → complex MDM setup required
- Hospital kiosks need an app replaced → IT team visits each device

**There has to be a better way.**

---

## 💡 The Solution

A custom **AOSP system app** that runs silently in the background on every device, polls a backend server for commands, and executes installs/uninstalls without any user prompt.

```
Your laptop                    Android Device
    │                               │
    │  POST /install                │
    ├──────────────────────────────►│ InstallerService (polling every 30s)
    │                               │         │
    │                               │    Downloads APK
    │                               │         │
    │                               │  PackageInstaller API
    │                               │         │
    │                               │   ✅ App installed silently
    │                               │         │
    │◄──────────────────────────────┤ POST /result
    │  { success: true }            │
```

---

## ✨ Features

- **Silent install** — no user prompt, no Play Store required
- **Silent uninstall** — remove any app remotely
- **Always running** — survives reboots via `BootReceiver`, survives kills via `START_STICKY`
- **Fleet ready** — each device identifies itself with a unique device ID
- **Result reporting** — device reports install success/failure back to server
- **Auto polling** — checks server every 30 seconds automatically
- **Local file support** — install from `file://` path or remote `http://` URL

---

## 🏗️ Architecture

```
┌─────────────────────────────────────┐
│         Node.js Backend             │
│  REST API + APK file hosting        │
│  /install  /uninstall  /history     │
└──────────────┬──────────────────────┘
               │ HTTPS (poll every 30s)
┌──────────────▼──────────────────────┐
│         InstallerService            │  ← Persistent background service
│  Downloads APK, triggers install    │
└──────────────┬──────────────────────┘
               │ PackageInstaller API
┌──────────────▼──────────────────────┐
│      Android PackageManager         │  ← system_server (Binder IPC)
│  Silent install — no user prompt    │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   System App in /system/app         │  ← Platform key signed
│   INSTALL_PACKAGES: granted=true    │  ← Signature permission
└─────────────────────────────────────┘
```

### Why it must be a system app

`INSTALL_PACKAGES` is a **signature-level permission** — the OS only grants it to apps signed with the same platform key it was built with. A regular Play Store app can never hold this permission regardless of what it declares in its manifest.

---

## 🚀 Use Cases

| Scenario | How it helps |
|---|---|
| **Shopfloor tablets** | Push app updates to 200 warehouse devices in one curl command |
| **Android Automotive** | SOTA (Software Over The Air) updates to vehicle fleets |
| **Hospital kiosks** | Replace or update kiosk apps without IT site visits |
| **Retail POS devices** | Update payment app across all store devices instantly |
| **School Chromebook-style deployments** | Manage student Android tablets centrally |

---

## 📋 Requirements

| Requirement | Details |
|---|---|
| OS | Ubuntu 18.04+ |
| Android Studio | Latest stable |
| Java | JDK 11+ |
| Node.js | v16+ |
| Emulator | AOSP system image (not Google Play) |
| Disk | 5GB free for emulator + build |

---

## ⚙️ Setup Guide

### Step 1 — Download AOSP platform test keys

The app must be signed with the AOSP platform key to receive signature permissions. Download the public test keys from Google:

```bash
mkdir ~/platform-keys && cd ~/platform-keys

wget https://android.googlesource.com/platform/build/+archive/refs/heads/main/target/product/security.tar.gz

tar -xzf security.tar.gz
ls *.pk8 *.pem   # platform.pk8 and platform.x509.pem should appear
```

### Step 2 — Set up AOSP emulator

A standard Google Play emulator cannot be rooted. You need an AOSP system image.

```bash
# Download AOSP system image
sdkmanager "system-images;android-33;default;x86_64"

# Create AVD
avdmanager create avd \
  -n AOSP_API33 \
  -k "system-images;android-33;default;x86_64" \
  -d "pixel_6"

# Launch with writable system flag — required for remount
emulator -avd AOSP_API33 -writable-system -no-snapshot &

# Verify root works
adb root
adb shell whoami   # should print: root
```

### Step 3 — Start the Node.js server

```bash
cd RemoteInstallerServer
npm install
node server.js
```

Server starts on `http://0.0.0.0:3000`

### Step 4 — Deploy the system app

The `deploy.sh` script handles everything automatically:

```bash
chmod +x deploy.sh
./deploy.sh
```

It will:
- Launch emulator if not running
- Build APK via Gradle
- Sign with platform key
- Remount system partition
- Push to `/system/app`
- Reboot and wait for boot
- Verify `INSTALL_PACKAGES: granted=true`

### Step 5 — Verify it worked

```bash
# Confirm it's a system app
adb shell pm list packages -s | grep remoteappinstaller

# Confirm permission is granted
adb shell dumpsys package com.droid.remoteappinstaller \
    | grep "INSTALL_PACKAGES"
# Expected: INSTALL_PACKAGES: granted=true

# Confirm it can't be uninstalled
adb uninstall com.droid.remoteappinstaller
# Expected: Failure [DELETE_FAILED_SYSTEM_APP]
```

---

## 🌐 API Reference

Base URL: `http://YOUR_SERVER_IP:3000`

### Queue an install

```bash
POST /install
Content-Type: application/json

{
  "packageName": "com.your.app",
  "apkUrl": "http://YOUR_SERVER_IP:3000/apks/yourapp.apk"
}
```

### Queue an uninstall

```bash
POST /uninstall
Content-Type: application/json

{
  "packageName": "com.your.app"
}
```

### Upload APK to server

```bash
curl -X POST http://YOUR_SERVER_IP:3000/upload \
  -F "apk=@/path/to/yourapp.apk"
```

### Check pending commands

```bash
GET /queue
```

### View install history

```bash
GET /history
```

### Device polls for commands

```bash
GET /commands?deviceId=device-001
```

---

## 🔧 Deploy Script

The `deploy.sh` script is a one-command deploy tool. Update the config at the top to match your setup:

```bash
PROJECT_DIR="/home/yourname/AndroidStudioProjects/RemoteAppInstaller"
PLATFORM_KEY="$HOME/platform-keys/platform-keys/platform.pk8"
PLATFORM_CERT="$HOME/platform-keys/platform-keys/platform.x509.pem"
AVD_NAME="AOSP_API33"
```

Then just run:

```bash
./deploy.sh
```

---

## 📁 Project Structure

```
RemoteAppInstaller/
├── app/src/main/
│   ├── java/com/droid/remoteappinstaller/
│   │   ├── MainActivity.kt          # UI for manual testing
│   │   ├── InstallerService.kt      # Core service — polls + installs
│   │   ├── InstallResultReceiver.kt # Catches install results
│   │   └── BootReceiver.kt          # Starts service on boot
│   ├── res/xml/
│   │   └── network_security_config.xml
│   └── AndroidManifest.xml
├── deploy.sh                        # Automated build + sign + push script
└── RemoteInstallerServer/
    ├── server.js                    # Node.js REST API
    └── apks/                        # APK hosting directory
```

---

## ⚠️ Important Notes

**Platform keys are NOT included** in this repo. Download them separately from AOSP as shown in Setup Step 1. Never commit `.pk8` or `.pem` files to any repository.

**This is for controlled/owned devices only.** This pattern is designed for enterprise, automotive, and kiosk scenarios where the organization owns and manages the devices. Do not use on devices you do not own or have authorization to manage.

**Tested on:**
- Ubuntu 22.04
- Android Studio Hedgehog
- AOSP emulator API 33 (x86_64)
- Node.js v18

---

## 🔗 Related Concepts

- [AOSP System Apps](https://source.android.com/docs/core/architecture)
- [Android PackageInstaller API](https://developer.android.com/reference/android/content/pm/PackageInstaller)
- [Project Treble](https://android-developers.googleblog.com/2017/05/here-comes-treble-modular-base-for.html)
- [Android Automotive SOTA](https://source.android.com/docs/automotive/start/aaos_intro)

---

## 👤 Author

**Joydip Bose** — Android Engineer

[LinkedIn](https://linkedin.com/in/yourprofile) · [GitHub](https://github.com/yourusername) · [Play Store](https://play.google.com/store/apps/developer?id=yourname)

---

## 📄 License

MIT License — feel free to use, modify and share.
