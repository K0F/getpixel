# getpixel

Read the colour of a pixel, wherever it is.

Two tools share one colour stack:

- **`get_pixel`** — a tiny C CLI printing RGBA / `#RRGGBB` of one pixel at
  `(x, y)`. Runs on device (Termux + root) or from `adb shell`.
- **Pixel Pick** — an Android camera/gallery colour picker: live crosshair,
  save to a palette, NV12/NV21 chroma fix, and lighting calibration with a
  CIE L\*u\*v* readout (`magnifier/`, plain Java).

## get_pixel

```sh
make            # builds ./get_pixel
make test       # C + pure-Java tests, no Android needed
./get_pixel 200 400
# Pixel at (200, 400) -> R:12 G:34 B:56 A:255 (Hex: #0C2238)
```

Captures via `screencap` (root via `su`, or adb shell). Exit codes in
`capscreen.h`.

## Pixel Pick (Android)

```sh
cd magnifier
make apk-test           # JVM unit tests (JDK 17 + SDK 34)
make debug-apk          # app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk   # grant Camera on first run
```

Camera: drag to aim, tap to save, pinch-free zoom inset, `#rrggbb` + L\*u\*v*
readout. Gallery: tap a photo, + Add to save. Palette persists 12 colours.
MIUI/HyperOS may require *Install via USB* enabled on first sideload.

Calibration is true colorimetric: with 3+ ColorChecker taps the app fits a
least-squares matrix from linear camera RGB to the chart's published CIE XYZ,
Bradford-adapted to the current light, so L\*u\*v* is referenced to the
measured illuminant and corrected colours are D65 appearance. Fewer taps fall
back to per-channel white-balance gains.

## F-Droid

Recipe: `fdroid/org.getpixel.magnifier.yml`. Releases are version-tagged
(`vX.Y.Z`) and built unsigned and minify-off so F-Droid produces a
deterministic build and signs it itself. Submit to `fdroid/fdroiddata`.

## License

Apache-2.0 — see `LICENSE`.