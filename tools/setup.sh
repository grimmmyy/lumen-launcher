#!/bin/sh
# Sets up Lumen on a Fire TV from a Mac or Linux computer, in one go.
#
#   ./tools/setup.sh 192.168.1.50            # Lumen already installed
#   ./tools/setup.sh 192.168.1.50 lumen.apk  # install it too
#
# Needs ADB (Android platform-tools) and, on the TV, Settings > My Fire TV >
# Developer options > ADB debugging. The first time, the TV asks whether to
# allow this computer: choose Allow. Safe to run again.
set -e
[ -n "$1" ] || { echo "usage: $0 <fire-tv-ip> [lumen.apk]"; exit 1; }
DEVICE="$1"; case "$DEVICE" in *:*) ;; *) DEVICE="$DEVICE:5555";; esac
APK="$2"
PKG=io.github.grimmmyy.lumen
SERVICE="$PKG/$PKG.HomeWatcherService"
step() { printf '\033[36m- %s\033[0m\n' "$1"; }
sh_() { adb -s "$DEVICE" shell "$1" | tr -d '\r'; }

step "Connecting to $DEVICE (choose Allow on the TV if it asks)"
adb connect "$DEVICE"
[ "$(adb -s "$DEVICE" get-state 2>/dev/null)" = device ] || { echo "Couldn't connect. Is ADB debugging on?"; exit 1; }

if [ -n "$APK" ]; then step "Installing $APK"; adb -s "$DEVICE" install -r "$APK"; fi
[ -n "$(sh_ "pm path $PKG")" ] || { echo "Lumen isn't installed. Run again with the APK path."; exit 1; }

step "Home button: opens Lumen"
CURRENT=$(sh_ "settings get secure enabled_accessibility_services")
case "$CURRENT" in
  *"$SERVICE"*) echo "  already on" ;;
  ""|null) sh_ "settings put secure enabled_accessibility_services '$SERVICE'" >/dev/null; echo "  turned on" ;;
  *) sh_ "settings put secure enabled_accessibility_services '$CURRENT:$SERVICE'" >/dev/null; echo "  turned on" ;;
esac
sh_ "settings put secure accessibility_enabled 1" >/dev/null

step "Now Playing: let Lumen see media players (not notifications)"
sh_ "cmd notification allow_listener $PKG/$PKG.NowPlayingService" >/dev/null

step "Recent from anywhere: usage access (when apps were last used, app sizes)"
sh_ "appops set $PKG GET_USAGE_STATS allow" >/dev/null

step "Updates: allow Lumen to install new versions (it always asks first)"
sh_ "appops set $PKG REQUEST_INSTALL_PACKAGES allow" >/dev/null

step "Wallpapers: allow Lumen to read /sdcard/Lumen/wallpapers"
sh_ "pm grant $PKG android.permission.READ_EXTERNAL_STORAGE" >/dev/null || true

step "Opening Lumen, then compiling it ahead of time (about 20 seconds)"
sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null
sh_ "am start -n $PKG/.MainActivity" >/dev/null
# Lumen hands its baseline profile to Android a few seconds after it starts.
sleep 12
sh_ "cmd package compile -m speed-profile -f $PKG"

printf '\n\033[32mDone. Press Home on the remote: Lumen opens. Lumen > Setup shows the status of each step.\033[0m\n'
