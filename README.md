# getpixel

Read the colour of a pixel, wherever it is.

Two tools share one colour stack:

1. **`get_pixel`** – a tiny C CLI that prints the RGBA / `#RRGGBB` of one
   pixel at `(x, y)`. Runs on the device (Termux with root) or from an
   `adb shell`.
2. **Pixel Pick** – an Android app that picks **live camera** colours or
   samples a **gallery photo**, saving picked colours to a persistent palette.

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

## Pixel Pick (Android app)

```
magnifier/  – Gradle project (Java, no androidx dependencies)
```

An HTML-style **`#rrggbb`** colour picker:

- **Camera** – live preview (CAMERA permission), crosshair + magnified inset at
  the focus point. **Drag** anywhere to move the crosshair, **pinch** to zoom
  the lens (1–12×), **tap** to save the focused colour to the palette.
- **Gallery** – open a photo, **tap** to set the target, press **+ Add** to save.
  EXIF orientation is honoured; photos are down-scaled for sampling.
- **Palette** – up to 12 saved colours, persisted across launches. Tap a swatch
  to copy its hex, long-press to remove. Use the **NV12/NV21** chip if the live
  colors look swapped (persisted per device), and **Gallery/Camera** to switch.

How it works: `CameraController` streams Camera2 `YUV_420_888`/`RGBA_8888`
frames into an int-ARGB `FrameBuffer`; `CameraPickerView` draws the rotated,
letterboxed preview (pure `CamMath` keeps preview and crosshair pixel-exact)
and a magnified crop via `LensMath`. `Yuv420` decodes chroma with bounds-safe
reads and a fixed, calibratable NV12/NV21 ordering. `Palette` stores hex codes
in one `SharedPreferences` string.

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
2. Grant **Camera** when asked (first launch).
3. Point the camera at something; the readout shows the pivot pixel's
   `#rrggbb`. If red/blue look swapped, tap the **NV21/NV12** chip.
4. Drag to aim, tap to save; switch to **Gallery** to sample a photo.

On MIUI/HyperOS devices the very first install may need *Install via USB*
enabled in the system USB-install manager.

### Tests

| Command  | What it runs                                                        |
|----------|---------------------------------------------------------------------|
| `make test`      | C fake-`screencap` stream tests + pure-logic Java tests (javac) |
| `make apk-test`  | Gradle `testDebugUnitTest` (JUnit wrapper over the same logic)  |

The pure-logic suite (`ColorUtil`, `LensMath`, `CamMath`, `FrameBuffer`,
`Palette`) runs with plain `javac`/`java` too.

## F-Droid

`fdroid/org.getpixel.magnifier.yml` is the build recipe:
- version-tagged releases (`v1.0.0`, `v1.1.0`, `v1.1.1`, …), built with
  `subdir: magnifier` + `gradle: assembleRelease`, output
  `app-release-unsigned.apk`.
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