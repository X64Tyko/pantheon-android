# Pantheon Android

[![Test & build APKs](https://github.com/X64Tyko/pantheon-android/actions/workflows/build.yml/badge.svg)](https://github.com/X64Tyko/pantheon-android/actions/workflows/build.yml)

Native Android/Android TV client for [Pantheon](https://github.com/X64Tyko/Pantheon) — no WebView, real
Jetpack Compose (Compose for TV on the TV flavor). Talks to your own Pantheon server; it doesn't work standalone.

## Install

There's no Play Store or Amazon Appstore listing yet — grab a signed APK straight from
[the latest release](https://github.com/X64Tyko/pantheon-android/releases/latest) and sideload it.

Four APKs are published, one per flavor combination:

|                          | Google Play devices (has GMS)   | Fire OS / other non-GMS devices |
|--------------------------|---------------------------------|---------------------------------|
| **Phone / tablet**       | `app-google-mobile-release.apk` | `app-amazon-mobile-release.apk` |
| **Android TV / Fire TV** | `app-google-tv-release.apk`     | `app-amazon-tv-release.apk`     |

If you're not sure which to pick: Google Play devices (most phones, most Android TV boxes, Nvidia Shield, Chromecast
with Google TV) want the `google` column. Fire TV Stick and other GMS-less devices want `amazon`. Picking the wrong
store flavor still installs and runs — it just means nothing GMS-specific will ever be wired up on it.

The Amazon/Fire TV flavor builds and passes CI but hasn't been run on real Fire TV hardware yet — the Google flavors
are the verified path.

Sideloading requires enabling "install from unknown sources" for whichever app you're installing from (a file
manager, `adb install`, etc.) — this is a normal Android setting, not anything Pantheon-specific.

## Connect it to your server

On first launch, enter your Pantheon server's address (the same one the web UI runs on) — e.g.
`192.168.1.50:8000` — then sign in with your existing Pantheon account. There's no separate signup; this app
is a client for a server you (or whoever hosts it) already run.

## What's here vs. the web app

Home, Library, Detail, Guide (a full channel × time EPG grid, not just a channel list), and Player are all built out
and manifest-driven — the same `GET /api/tv/manifest` contract the web app's own `/tv` surface consumes, so shelf
layout and theming changes on the server show up here too without an app update.

Watch Together (synchronized group VOD viewing) is web-only for now — it hasn't been ported to this client yet.

## Building it yourself

Standard Gradle project, four flavors (`google`/`amazon` × `mobile`/`tv`):

```bash
./gradlew assembleGoogleMobileDebug   # or assembleGoogleTvDebug, assembleAmazonMobileDebug, assembleAmazonTvDebug
```

`compileSdk`/`targetSdk` 37, `minSdk` 26, JDK 21. CI (`.github/workflows/build.yml`) runs unit tests, builds all
four debug APKs on every push/PR, and publishes signed release APKs to the `latest` GitHub Release on every push to
`master`.

## Issues

This is a companion app to [Pantheon](https://github.com/X64Tyko/Pantheon) itself — see that repo's
[Contributing Guidelines](https://github.com/X64Tyko/Pantheon/blob/master/CONTRIBUTING.md) for how bugs/issues are
handled.
