# Lumen

A fast, ad-free home screen for Fire TV and Android TV, inspired by Apple TV.

Most TV home screens put recommendations, ads and extra services between you and
what you wanted to watch. Lumen puts your apps first, keeps them where you put
them, and stays smooth on cheap hardware.

- **Top Shelf**: your apps, large, in the order you choose. Positions never move on their own.
- **Recent**: apps you opened lately that aren't on the Top Shelf (tracked by Lumen; no usage permission).
- **Apps**: everything else in a grid. Hide anything you don't want to see.
- **Quick**: one press to Wi-Fi, display & sound, remotes, app management and settings.
- **Continue Watching** on devices that allow it (see below).
- **Backdrop that follows focus**: a soft blur of the focused app, or your own wallpapers.
- **Your layout, kept**: turn the Recent, Apps and Quick rows on or off; nothing autoplays.
- **Larger tiles and text**: an option for viewing from further away or with low vision.
- **Always clear where you are**: the focused tile lifts with a soft shadow and a ring drawn
  outside it (visible even on white artwork), and the title of the row you're in brightens.
- **Focus memory**: each row returns to the tile you left it on; Back and Home return to the top.
- **No ads, no sponsored rows, no account, no network calls** (except poster images for Continue Watching).

## Performance

Measured with [`tools/bench.ps1`](tools/bench.ps1) on an Android 11 TV emulator at 1920×1080, 320 dpi
(the Fire TV Stick 4K Max's resolution and density), software GPU, same apps for both builds:

| | 0.1.1 | 0.2.0 |
|---|---|---|
| Janky frames, moving across a row | 79.8% | **0%** |
| Janky frames, moving down the page | 87.9% | **0%** |
| Janky frames, D-pad held down | 95.1% | **0%** |
| Slowest 1% of frames (p99) | 48–65 ms | **13–14 ms** |
| Cold start | 458 ms | **370 ms** |
| Return to Home (warm start) | 9 ms | 8 ms |
| Memory | 25 MB | 26 MB |

With the Apps grid repeated 9× (a large library), janky frames stay at 0.25–0.5% and p99 at 15 ms.
For comparison, the stock Fire TV home screen uses about 180 MB of memory on the 4K Max.

How:
- **One bitmap per tile.** Each app's banner (or icon and name) is rendered once into a
  bitmap at the exact tile size with rounded corners baked in, and cached on disk. A tile
  is a single lightweight view that draws one bitmap: no scaling, clipping or nested views.
- **One full-screen layer.** The backdrop is composited off the main thread with its
  darkening scrim baked in, so the GPU draws one full-screen image per frame instead of an
  image plus stacked gradients. The window background is dropped once it is covered.
- **No real-time shadows.** Focus is a 1.1× zoom (a GPU property, no redraw), a ring, and a
  shadow that is a pre-blurred alpha mask made once per tile size and drawn only for the
  focused tile. Animated elevation shadows are among the most expensive things a TV GPU can draw.
- **One virtualized list.** The whole page is a single RecyclerView; rows share a pool of
  tile views and only what's on screen (plus half a screen of margin) is bound.
- **Predictable scrolling.** The focused row always settles on the same line, rows only
  scroll near their edges, and animations shorten while a key is held so the screen keeps up.
- **Nothing slow on Home.** The app list lives in memory and in a small snapshot file;
  Lumen only rescans when Android reports a package change. Background work runs on
  low-priority threads. A baseline profile gets the hot path compiled ahead of time.

## Install (Fire TV)

1. On the Fire TV: Settings → My Fire TV → Developer options → turn on **ADB debugging** and **Apps from unknown sources**.
2. From a computer with [ADB](https://developer.android.com/tools/releases/platform-tools):

```sh
adb connect <fire-tv-ip>:5555
adb install -r lumen.apk
```

Get `lumen.apk` from [Releases](../../releases), or build it yourself (below).

> **Check your model.** Some newer Fire TV devices (for example Fire TV Stick 4K Select and
> Fire TV Stick HD 2026) run Vega OS, which does not run Android apps. Lumen needs a Fire OS
> (Android-based) device.

## Make the Home button open Lumen

Fire OS (7 and later) won't let you pick a different default home screen, and its own launcher is
protected. Lumen does not remove or disable anything. Instead, a small accessibility service notices
when the Amazon home screen appears (Home button, boot, wake from standby) and opens Lumen over it.
It only sees which screen opened, only for the Amazon launcher, and cannot read screen content.

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

**It can't trap you in a loop.** If Lumen crashes, Android stops the Home-button service until Lumen is
opened again. On top of that, Lumen has its own guard: 2 crashes within 5 minutes, or 6 Home redirects
within 15 seconds, switches it to safe mode, and the Home button goes to the Amazon home screen. Open Lumen
from Your Apps; once it has stayed on screen for 5 seconds, the Home button opens Lumen again. A factory
reset (hold **Back + Right** on the remote for 10 seconds) always restores the stock setup.

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
| Choose which rows to show, or larger tiles | Quick → **Lumen** → Layout |
| Settings | Quick → **Lumen** |

### Wallpapers

Without wallpapers, the background is a soft blur of the focused app. To use your own:

```sh
adb shell mkdir -p /sdcard/Lumen/wallpapers
adb push default.jpg /sdcard/Lumen/wallpapers/
adb push netflix.jpg /sdcard/Lumen/wallpapers/com.netflix.ninja.jpg
# Lumen needs permission to read photos (or choose Allow in Lumen → Wallpapers):
adb shell pm grant io.github.grimmmyy.lumen android.permission.READ_EXTERNAL_STORAGE
```

- `default.jpg` shows for every app; `<package>.jpg` shows when that app is focused.
- PNG and WebP work too; 1920×1080 is ideal.
- Then Lumen → **Reload wallpapers and apps**.

On Android 13 and later, grant `android.permission.READ_MEDIA_IMAGES` instead.

### Continue Watching

This row reads Android's system Watch Next list. On Fire OS, the permission it needs is reserved
for Amazon's own system apps, so the row stays hidden there. It appears automatically on Android TV
and Google TV devices where apps publish to Watch Next.

## Permissions

| Permission | Why |
|---|---|
| `QUERY_ALL_PACKAGES` | List your installed apps |
| `INTERNET` | Poster images for Continue Watching only |
| `READ_EPG_DATA` | Read Continue Watching (not grantable on Fire OS) |
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` | Optional: your wallpapers in `/sdcard/Lumen/wallpapers` |
| Accessibility service | Optional: make the Home button open Lumen (see above) |

## Build

Requires JDK 17+ and the Android SDK (platform 34).

```sh
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs without extra setup.
Each push to `main` builds an APK in GitHub Actions; pushing a `v*` tag publishes a release.

### Benchmark

```powershell
./tools/bench.ps1 -Serial <adb-serial> -Runs 5 [-Compile]
# Optional: a large library, the Apps grid repeated 8 extra times
adb -s <serial> shell setprop debug.lumen.stress 8
```

Reports cold and warm start, frame timing (jank %, p50–p99) for moving across a row, down the
page and with a key held, and memory. It force-stops Lumen and sends key presses, so don't run it
while someone is watching that TV.

## License

MIT
