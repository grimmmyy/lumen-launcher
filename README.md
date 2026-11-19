# Lumen

A lightweight, ad-free home screen for Fire TV and Android TV, inspired by Apple TV.

- **Top Shelf** of your favorite apps with large banners and a focus zoom
- **Backdrop that follows focus**: per-app wallpapers, or a gradient taken from the app's colors
- **Continue Watching** row on devices that allow reading the system Watch Next list (see the note below)
- **Apps grid** with everything else; hide anything you don't want to see
- **Clock and date**, no ads, no sponsored rows, no autoplay
- About 170 KB, roughly 70 MB of RAM in use (the stock Fire TV home uses about 180 MB)

## Install (Fire TV)

1. On the Fire TV: Settings → My Fire TV → Developer options → turn on **ADB debugging** and **Apps from unknown sources**.
2. From a computer with [ADB](https://developer.android.com/tools/releases/platform-tools):

```sh
adb connect <fire-tv-ip>:5555
adb install -r lumen.apk
```

Get `lumen.apk` from [Releases](../../releases), or build it yourself (below).

## Make the Home button open Lumen

Fire OS (7 and later) won't let you pick a different default home screen, and its own launcher is
protected. Lumen does not remove or disable anything. Instead, a small accessibility service notices
when the Amazon home screen appears (Home button, boot, wake from standby) and opens Lumen over it.

Fire TV hides the setting, so turn it on with ADB:

```sh
adb shell settings put secure enabled_accessibility_services io.github.grimmmyy.lumen/io.github.grimmmyy.lumen.HomeWatcherService
adb shell settings put secure accessibility_enabled 1
```

> The first command **replaces** the list of enabled accessibility services. If you already use
> others, check with `adb shell settings get secure enabled_accessibility_services` and join them with `:`.

To undo it:

```sh
adb shell settings put secure enabled_accessibility_services ""
```

Need the Amazon home screen on purpose? Lumen settings → **Open Fire TV home** pauses the redirect for one minute.

**Optional:** Amazon's forced-update service has broken launcher workarounds in the past. You can switch it off
(normal updates still install from Settings):

```sh
adb shell pm disable-user --user 0 com.amazon.tv.forcedotaupdater.v2
```

## Using it

| Action | How |
|---|---|
| Open an app | Select its tile |
| Add, move, remove or hide an app | Long-press its tile, or press **Menu** (☰) |
| Back to the top | **Back** or **Home** |
| Settings | System row → **Lumen settings** |

### Wallpapers

Copy images to the device:

```sh
adb push default.jpg /sdcard/Android/data/io.github.grimmmyy.lumen/files/wallpapers/
adb push netflix.jpg /sdcard/Android/data/io.github.grimmmyy.lumen/files/wallpapers/com.netflix.ninja.jpg
```

- `default.jpg` shows for every app.
- `<package>.jpg` shows when that app is focused. PNG and WebP work too; 1920×1080 is ideal.
- Then Lumen settings → **Reload wallpapers and apps**.

### Continue Watching

This row reads Android's system Watch Next list. On Fire OS, the permission it needs is reserved
for Amazon's own system apps, so the row stays hidden there. It appears automatically on Android TV
and Google TV devices where apps publish to Watch Next.

## Build

Requires JDK 17+ and the Android SDK (platform 34).

```sh
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs without extra setup.
Each push to `main` builds an APK in GitHub Actions; pushing a `v*` tag publishes a release.

## License

MIT
