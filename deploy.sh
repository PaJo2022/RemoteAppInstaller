#!/bin/bash

# ── Config ────────────────────────────────────────────────────────────────
PROJECT_DIR="/home/joydip/AndroidStudioProjects/RemoteAppInstaller"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
SIGNED_APK="$HOME/RemoteInstaller-signed.apk"
PLATFORM_KEY="$HOME/platform-keys/platform-keys/platform.pk8"
PLATFORM_CERT="$HOME/platform-keys/platform-keys/platform.x509.pem"
SYSTEM_APK_PATH="/system/app/RemoteInstaller/RemoteInstaller.apk"
PACKAGE_NAME="com.droid.remoteappinstaller"
AVD_NAME="AOSP_API33"

# ── Colors ────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[✓] $1${NC}"; }
warn() { echo -e "${YELLOW}[!] $1${NC}"; }
error(){ echo -e "${RED}[✗] $1${NC}"; exit 1; }

# ── Step 1: Check/Launch emulator ─────────────────────────────────────────
echo ""
warn "Checking emulator..."
adb devices | grep emulator > /dev/null 2>&1
if [ $? -ne 0 ]; then
    warn "No emulator found — launching $AVD_NAME..."
    emulator -avd "$AVD_NAME" -writable-system -no-snapshot > /dev/null 2>&1 &
    EMULATOR_PID=$!
    log "Emulator launched (PID: $EMULATOR_PID)"

    # Wait for emulator to appear in adb devices
    warn "Waiting for emulator to connect..."
    for i in $(seq 1 30); do
        adb devices | grep emulator > /dev/null 2>&1
        if [ $? -eq 0 ]; then
            log "Emulator connected"
            break
        fi
        echo -n "."
        sleep 3
        if [ $i -eq 30 ]; then
            error "Emulator failed to connect after 90 seconds"
        fi
    done
    echo ""

    # Wait for full boot
    warn "Waiting for emulator to fully boot..."
    sleep 5
    while true; do
        BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [ "$BOOT" = "1" ]; then
            break
        fi
        echo -n "."
        sleep 3
    done
    echo ""
    log "Emulator fully booted"
else
    log "Emulator already running"
fi

# ── Step 2: Build APK ─────────────────────────────────────────────────────
echo ""
warn "Building APK..."
cd "$PROJECT_DIR"
./gradlew assembleDebug --quiet
if [ $? -ne 0 ]; then
    error "Build failed. Fix errors in Android Studio first."
fi
log "Build successful"

# ── Step 3: Sign APK ──────────────────────────────────────────────────────
echo ""
warn "Signing APK with platform key..."
apksigner sign \
    --key "$PLATFORM_KEY" \
    --cert "$PLATFORM_CERT" \
    --out "$SIGNED_APK" \
    "$APK_PATH"
if [ $? -ne 0 ]; then
    error "Signing failed. Check platform key paths."
fi
log "APK signed: $SIGNED_APK"

# ── Step 4: Root and remount ──────────────────────────────────────────────
echo ""
warn "Rooting adb..."
adb root > /dev/null 2>&1
sleep 2

warn "Remounting system partition..."
adb remount > /dev/null 2>&1
if [ $? -ne 0 ]; then
    error "Remount failed. Make sure emulator launched with -writable-system flag."
fi
log "System partition remounted"

# ── Step 5: Push APK ──────────────────────────────────────────────────────
echo ""
warn "Pushing APK to system..."
adb shell mkdir -p /system/app/RemoteInstaller
adb push "$SIGNED_APK" "$SYSTEM_APK_PATH"
if [ $? -ne 0 ]; then
    error "Push failed."
fi
adb shell chmod 644 "$SYSTEM_APK_PATH"
adb shell chown root:root "$SYSTEM_APK_PATH"
log "APK pushed to $SYSTEM_APK_PATH"

# ── Step 6: Reboot ────────────────────────────────────────────────────────
echo ""
warn "Rebooting emulator..."
adb reboot

# ── Step 7: Wait for boot ─────────────────────────────────────────────────
echo ""
warn "Waiting for boot..."
sleep 5
while true; do
    BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [ "$BOOT" = "1" ]; then
        break
    fi
    echo -n "."
    sleep 3
done
echo ""
log "Device booted"

# ── Step 8: Verify ────────────────────────────────────────────────────────
echo ""
warn "Verifying installation..."
sleep 3

PACKAGE=$(adb shell pm list packages | grep "$PACKAGE_NAME")
if [ -z "$PACKAGE" ]; then
    error "Package not found after reboot"
fi
log "Package found: $PACKAGE"

INSTALL_PERM=$(adb shell dumpsys package "$PACKAGE_NAME" | grep "INSTALL_PACKAGES: granted=true")
if [ -z "$INSTALL_PERM" ]; then
    warn "INSTALL_PACKAGES not granted — check platform key signing"
else
    log "INSTALL_PACKAGES: granted=true"
fi

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  Deploy complete! App is live as system app.  ${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""