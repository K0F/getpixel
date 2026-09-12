---
name: fdroid-publish
description: >-
  Use when packaging an Android app for F-Droid or "publishing on fdroid":
  building a reproducible unsigned release APK, writing the F-Droid build
  recipe (fdroid/<app-id>.yml), tagging a release, and submitting the recipe
  as a merge request to fdroiddata. Triggers on "pack app and publish on
  fdroid", "fdroid build", "fdroid recipe", "app store fdroid".
---

# Publishing an Android app on F-Droid

F-Droid does **not** accept APKs. It builds apps from source inside its own
Docker images using your project's build files, then signs with its own key.
Your job is to make the source *buildable, reproducible and policy-clean*, and
to submit a **build recipe** (metadata YAML) — not the binary.

Worked example in this repo: `getpixel` (C CLI + `magnifier/` Pixel Magnifier
Android app), metadata at `fdroid/org.getpixel.magnifier.yml`.

## 1. Source and policy prerequisites

- Repo is hosted **publicly** (GitHub/GitLab). F-Droid must `git clone` it.
- A licence file (e.g. `LICENSE`) is present and declared in the recipe.
- No opaque binaries: no prebuilt `.jar/.aar` dependencies, no checked-in
  APKs, no NDK libs without `srclibs`.
- No paid/gated content; allow user-installed apps.

## 2. Make the build F-Droid-friendly (project side)

In `app/build.gradle`:

- `minifyEnabled false` and `signingConfig null` for `release`, so F-Droid can
  produce a deterministic unsigned APK and sign it with its own keystore.
- Use a real `versionName`/`versionCode` in `defaultConfig`, and tag git with
  version tags used for update checks.
- Keep the Gradle **wrapper** committed (`gradlew` + `gradle/wrapper/*`) to
  pin the build tooling.
- Avoid timestamps in generated files; keep resource minification/MANIFEST
  shrinking off for reproducibility.

## 3. Build and verify the release locally

```sh
make apk-test                 # JVM unit tests of pure logic
make release-apk              # → magnifier/app/build/outputs/apk/release/app-release-unsigned.apk
```

Verify the artifact:

```sh
SDK=$HOME/Android/Sdk
$SDK/build-tools/34.0.0/aapt dump badging \
    magnifier/app/build/outputs/apk/release/app-release-unsigned.apk   # pkg, vc/vn, sdk, perms
$SDK/build-tools/34.0.0/apksigner verify \
    --print-certs magnifier/app/build/outputs/apk/release/app-release-unsigned.apk   # must be UNSIGNED
```

The release APK should be **unsigned** (F-Droid signs) and, ideally, bit-for-bit
reproducible: rebuild the same tag in the F-Droid Docker image and compare the
APK hash with `fdroid build` / `apksigner`. In practice F-Droid re-signs, so
their CI verifies the build *from source*, not equality with your APK.

## 4. Write the build recipe

Create `metadata/<applicationId>.yml` (also mirror it as `fdroid/<app-id>.yml`
in your repo for transparency). Key fields (see the included example):

```yaml
Categories: [Graphics, System]
License: Apache-2.0
SourceCode: https://github.com/<owner>/<repo>
IssueTracker: https://github.com/<owner>/<repo>/issues
Description: |            # a couple of sentences, no HTML
  …
RepoType: git
Repo: https://github.com/<owner>/<repo>

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: magnifier
    gradle:
      - assembleRelease
    output: app/build/outputs/apk/release/app-release-unsigned.apk

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: "1.0.0"
CurrentVersionCode: 1
```

Notes:

- `commit` must be the git tag ref, e.g. `v1.0.0` (create it: `git tag v1.0.0`).
- `subdir` points at the Gradle project root when the app is not at repo root.
- `output` is relative to `subdir`; AGP 8 names the unsigned APK
  `app-release-unsigned.apk`.
- Version bumps: increment `versionCode`, tag `v<new>`; keep `AutoUpdateMode:
  Version v%v` + `UpdateCheckMode: Tags` so future versions are picked up.

## 5. Submit to fdroiddata

Build recipes live in the *fdroiddata* project (not your app repo):

1. Fork https://gitlab.com/fdroid/fdroiddata and clone it.
2. Copy your recipe to `metadata/<applicationId>.yml` inside the fork.
3. Ensure `Repo:` points at your **public** source repo.
4. Open a merge request titled like *"Add <App Name>"*. The `fdroid` CI bot
   comments with build findings; fix until the build passes.
5. Once merged, the app ships in the next F-Droid repo update cycle.

There is no "upload APK" step. If your source is only on a private host (e.g.
a LAN Raspberry Pi bare repo), F-Droid cannot build it — mirror to GitHub/GitLab
for the MR.

## 6. Local checks before you submit (optional but recommended)

- `fdroid lint ./metadata/<id>.yml` (from fdroiddata) validates the recipe.
- `fdroid build --test -l org.getpixel.magnifier` runs a local clean build to
  shake out recipe errors before the CI round-trip.
- Confirm `versionCode` of the *built APK* equals the recipe's `versionCode`
  (a mismatch is the most common rejection).