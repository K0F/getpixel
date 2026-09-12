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

A streamed install that ends with plain `Success` is the goal. Verify it
landed:

```sh
adb shell pm list packages | grep getpixel       # org.getpixel.magnifier
adb shell dumpsys package org.getpixel.magnifier | grep -E "versionName|versionCode" | head
```

**Proven on this device** (2026-09-12): first attempts failed with
`INSTALL_FAILED_USER_RESTRICTED`. Resolution — the user had to enable *"Install
via USB"* from the phone: firing `adb install` brings up a MIUI full-screen
**"Správce instalací přes USB"** (USB install manager) listing the app with a
toggle; flipping that ON (may demand a Mi-account verify) removes the
restriction, after which a plain retry installs. The host cannot do the flip —
see §4.

## 4. `INSTALL_FAILED_USER_RESTRICTED` (this phone MiUI/HyperOS quirk)

Trigger: *"Install canceled by user"*. What is and isn't possible from the
host on THIS device:

- **Cannot:** `input tap` / `input keyevent` → `SecurityException: needs
  INJECT_EVENTS`; `pm grant` → needs `GRANT_RUNTIME_PERMISSIONS`; the
  verifier `settings put` → needs `WRITE_SECURE_SETTINGS`. Treat all three as
  unavailable and route the step to the phone screen instead.
- **Can:** `uiautomator dump` + read the XML (see §5) to *see* what the phone
  is showing, without needing image input.
- **Works after the toggle is ON:** plain `adb install -r` (streamed path is
  fine; the push + `pm install` workaround was unnecessary once the toggle was
  enabled).

Diagnose the on-phone UI while an install is in flight:

```sh
adb shell dumpsys window | grep -iE "mCurrentFocus|mFocusedApp" | head -3
adb shell uiautomator dump /data/local/tmp/ui.xml && adb shell cat /data/local/tmp/ui.xml
```

Recognisable screens: `com.miui.securitycenter/...PackageManagerActivity` =
the USB install manager (app + toggle); `com.android.vending/...
PlayProtectDialogsActivity` = Google Play Protect showing on first sideload
(usually non-blocking). Extract buttons/text with:

```sh
adb shell cat /data/local/tmp/ui.xml | grep -oE '<node[^>]+>' \
  | grep -oE '(text|content-desc)="[^"]{1,50}"' | sort -u   # this model → tell the user, don't tap
```

## 5. Launch and pre-grant permissions

```sh
adb shell am start -n org.getpixel.magnifier/org.getpixel.magnifier.MainActivity
adb shell appops set org.getpixel.magnifier SYSTEM_ALERT_WINDOW allow   # works from host
```
Confirmed working: `SYSTEM_ALERT_WINDOW` via `appops set ... allow` →
`allow; time=+Ns ago (running)`. `POST_NOTIFICATIONS` is NOT a valid op name on
this device (`Unknown operation string`); grant it via the runtime prompt the
app shows on first launch (permission controller takes focus), and the overlay
"Display over other apps" dialog by the user.

Screen capture + MediaProjection are started from the app UI (system dialogs)
and can't be clicked from adb — hand over to the user there.

## 6. Uninstall / iterate

```sh
adb uninstall org.getpixel.magnifier
make debug-apk && adb install -r …   # redeploy after a code change
```

Keep the phone's "Always allow" + "Install via USB" enabled for the session to
iterate quickly.