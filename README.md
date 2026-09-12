# getpixel

Read the colour of any pixel on an Android screen.

Two tools share one capture stack:

1. **`get_pixel`** – a tiny C CLI that prints the RGBA / `#RRGGBB` of one
   pixel at `(x, y)`. Runs on the device (Termux with root) or from an
   `adb shell`.
2. **Pixel Magnifier** – an Android app (no root needed) that shows a floating
   magnifier glass over any app: drag to move, pinch to zoom, the crosshair
   marks the pixel at the glass centre and live-reads its colour. Long-press
   copies `#RRGGBB` to the clipboard.

## get_pixel (C)

```sh
make            # builds ./get_pixel
make test       # runs unit + integration tests (no Android needed)
./get_pixel 200 400
# Pixel at (200, 400) -> R:12 G:34 B:56 A:255 (Hex: #0C2238)
```

Capture uses `screencap`'s raw stream. Invocations are tried in order until one
works: `su -c /system/bin/screencap` (Termux/root), `su -c screencap`,
`/system/bin/screencap` (adb shell), then plain `screencap`.

Requirements: **root** (Termux) or an **adb shell** session:

```sh
# on the phone (Termux, requires root)
pkg install clang && clang -O2 -o get_pixel get_pixel.c capscreen.c
su -c ./get_pixel 200 400

# or from a host with the phone attached
./get_pixel 200 400          # if get_pixel runs in adb shell…
adb shell get_pixel 200 400
```

Out-of-bounds coordinates and truncated streams are reported; see the exit
codes in `capscreen.h` (`-1` exec, `-2` header, `-3` bounds, `-4` EOS, `-5` pixel read).

## Pixel Magnifier (Android app)

```
magnifier/  – Gradle project (Java, no androidx dependencies)
```

### Build

Requires JDK 17 and an Android SDK with platform 34 + build tools 34.

```sh
export ANDROID_HOME=/path/to/sdk
make debug-apk      # magnifier/app/build/outputs/apk/debug/app-debug.apk
make release-apk    # …/release/app-release-unsigned.apk  (unsigned, for F-Droid)
make apk-test       # JVM unit tests for the pure logic
```

or using the wrapper directly:

```sh
cd magnifier && ANDROID_HOME=$ANDROID_HOME ./gradlew assembleDebug
```

### Install & use

1. `adb install magnifier/app/build/outputs/apk/debug/app-debug.apk`
2. Open **Pixel Magnifier**, tap **Start magnifier**.
3. Grant **Display over other apps** and allow notifications (Android 13+).
   The system asks whether to start screen capture – allow it.
4. A glass appears at the top-right. Drag to aim at a pixel, pinch to zoom
   1–12×, long-press to copy the crosshair pixel's hex value.

Permissions used: screen capture (`MediaProjection`), `SYSTEM_ALERT_WINDOW`
for the floating glass, `POST_NOTIFICATIONS`; a **foreground service of type
`mediaProjection`** (required on Android 14+, `AndroidManifest.xml`) keeps the
capture alive while the glass floats over other apps.

How it works: `ImageReader` (full-resolution RGBA) → int ARGB `FrameBuffer` →
`LensView` draws a pixel-exact crop (filtering disabled) under a crosshair.
`ColorUtil.report()` prints the same format as the C CLI, so readouts are
directly comparable across both tools.

### Tests

| Command  | What it runs                                                        |
|----------|---------------------------------------------------------------------|
| `make test`      | C fake-`screencap` stream tests + pure-logic Java tests (javac) |
| `make apk-test`  | Gradle `testDebugUnitTest` (JUnit wrapper over the same logic)  |

## F-Droid

`fdroid/org.getpixel.magnifier.yml` is the build recipe:
- version-tagged releases (`v1.0.0`), built with `subdir: magnifier` +
  `gradle: assembleRelease`, output `app-release-unsigned.apk`.
- release builds are unsigned (F-Droid signs with its own key) and shrink-less
  (`minifyEnabled false`) for deterministic output.
- no binary blobs in the repo; the Gradle wrapper pins the build.

Submission requires the source on a public host (GitHub/GitLab). Open a merge
request against [`fdroid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata)
placing `org.getpixel.magnifier.yml` under `metadata/`, with the `Repo:`
field pointing at your public repo. See the skill
(`fdroid-publish`) for the full workflow.

## License

Apache-2.0 – see `LICENSE`.