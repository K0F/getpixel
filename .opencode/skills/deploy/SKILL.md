---
name: deploy
description: >-
  Use when installing or deploying an Android APK to a connected phone
  ("install on phone", "adb install", "deploy to phone", "put the app on the
  device", "install via usb"). Covers device detection over USB, RSA
  authorisation, MIUI/HyperOS install-restriction escapes, and launching the
  installed app. Currently targets the Redmi Note 12 (ruby / 22101316G) from
  this laptop via adb.
---

# Deploying an APK to the phone with adb

Target phone: **Redmi 22101316G** (codename `ruby`, Redmi Note 12 line,
HyperOS). adb serial seen: `mf7ptczdkznfdywo`. The project APK under test is
built by `make debug-apk` →
`magnifier/app/build/outputs/apk/debug/app-debug.apk`.

## 1. Confirm the device is there

```sh
adb kill-server && adb start-server && adb devices -l
```

Healthy state: `...  device usb:2-1.2 product:ruby_eea model:22101316G ...`

If `adb devices` is empty, check the USB layer — the phone must actually
enumerate on the bus:

```sh
lsusb | grep -i xiaomi
# expect: Bus xxx Device xxx: ID 2717:ff08 Xiaomi Inc. Redmi ... (ADB Interface)
```

No Xiaomi entry in `lsusb` means: wrong/charge-only cable, dead USB port, or
USB debugging disabled. Nothing adb-side can fix that; poll in a loop while
the user swaps cables/ports: `for i in $(seq 1 20); do adb devices | grep -c
"device$" && break; sleep 3; done`.

## 2. Authorise

First attach shows `unauthorized` — the phone displays *"Allow USB debugging?"*
with an RSA fingerprint. User must tap **Allow** on the phone, then poll until
status flips to `device`:

```sh
adb devices        # must show "device", not "unauthorized"/"offline"
```

## 3. Install

```sh
adb install -r magnifier/app/build/outputs/apk/debug/app-debug.apk
```

Verify it landed:

```sh
adb shell pm list packages | grep getpixel       # org.getpixel.magnifier
adb shell dumpsys package org.getpixel.magnifier | grep -E "versionName|versionCode" | head
```

## 4. `INSTALL_FAILED_USER_RESTRICTED` (this phone MiUI/HyperOS quirk)

Trigger: *"Install canceled by user"*. Causes & order of fixes:

1. **"Install via USB"** in Developer options must be on. On HyperOS enabling it
   may demand a Mi account sign-in / inserted SIM — the user does this on the
   phone, it cannot be forced from adb.
2. Toggle **"Install via USB"** off and on, or re-accept the on-phone *"Allow
   USB install"* confirmation dialog that the host-side `adb install` fires.
3. Retry via a pushed APK + `pm` (works when the restriction only hits the
   streamed path):

   ```sh
   adb push magnifier/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app-debug.apk
   adb shell pm install -r -t /data/local/tmp/app-debug.apk
   ```

4. Verifier toggles are NOT usable from host shell here (they need
   `WRITE_SECURE_SETTINGS`):

   ```sh
   adb shell settings put global verifier_verify_adb_installs 0   # SecurityException: not available
   ```
   Expect denial — these are a dead end on this setup, mention only as a
   possible fix for other phones where shell has the permission.

Check what the on-screen UI is doing meanwhile: `adb shell dumpsys window |
grep -i mCurrentFocus`.

## 5. Launch and pre-grant permissions

```sh
adb shell am start -n org.getpixel.magnifier/org.getpixel.magnifier.MainActivity
adb shell appops set org.getpixel.magnifier SYSTEM_ALERT_WINDOW allow   # floating glass
adb shell appops set org.getpixel.magnifier POST_NOTIFICATIONS allow    # FGS on 13+
```

Screen capture is still started from the app UI (system dialog), as is the
MediaProjection prompt — those can't be clicked from adb without an
accessibility helper.

## 6. Uninstall / iterate

```sh
adb uninstall org.getpixel.magnifier
make debug-apk && adb install -r …   # redeploy after a code change
```

Keep the phone's "Always allow" + "Install via USB" enabled for the session to
iterate quickly.