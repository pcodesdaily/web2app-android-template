# Web2App Android template

The Android app that Web2App builds. **Use this template** to create your own
project repository — builds then run in your account, on your own GitHub Actions
minutes.

## What is in here

A Kotlin + Jetpack Compose WebView shell, configured entirely by
`app/src/main/assets/config.json`. Nothing about your app is compiled in: the
name, colours, navigation, permissions and behaviour are all read from that file
at runtime, which is why a rebuild is cheap and a preview can be accurate.

| Path | What it is |
|---|---|
| `app/src/main/assets/config.json` | Your app's configuration. Web2App writes this |
| `app/src/main/java/dev/web2app/shell/` | The Compose shell and WebView wiring |
| `.github/workflows/build.yml` | Builds and signs your app |
| `.github/workflows/provision-key.yml` | Creates your Play upload key, once |
| `scripts/` | Key generation, backup, and signature verification |

## Your signing key never leaves your account

`provision-key.yml` generates a Play **upload key** inside this repository and
stores it in this repository's own secrets. Web2App never sees it and could not
retrieve it. The only thing the dashboard learns is the certificate fingerprint,
which is public information.

Download the backup artifact when that workflow runs, and keep it. If you lose
it you are not locked out — Google Play supports an upload key reset — but the
backup makes it a non-event.

## Versions

Pinned deliberately: compileSdk 37, targetSdk 36 (what Play requires), minSdk 24,
AGP 9.4.0, Gradle 9.6.1, JDK 17, Kotlin via AGP's built-in support.

## Building it yourself

```bash
./gradlew assembleDebug
```

You will need a `local.properties` with `sdk.dir` pointing at your Android SDK,
using **forward slashes** — Java properties files treat a backslash as an escape.
