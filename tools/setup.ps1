<#
.SYNOPSIS
  Sets up Lumen on a Fire TV from a Windows computer, in one go.

.EXAMPLE
  ./tools/setup.ps1 -Device 192.168.1.50
  ./tools/setup.ps1 -Device 192.168.1.50 -Apk lumen.apk

.NOTES
  Needs ADB (Android platform-tools) and, on the TV, Settings > My Fire TV >
  Developer options > ADB debugging. The first time, the TV asks whether to
  allow this computer: choose Allow.
  Steps: install (with -Apk), Home button, wallpaper access, then open Lumen
  and compile it ahead of time. Each step says what it did. Safe to run again.
#>
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Apk = ''
)

$ErrorActionPreference = 'Stop'
$pkg = 'io.github.grimmmyy.lumen'
$service = "$pkg/$pkg.HomeWatcherService"
if ($Device -notmatch ':') { $Device = "${Device}:5555" }

function Step([string]$what) { Write-Host "- $what" -ForegroundColor Cyan }
function Shell([string]$cmd) { (adb -s $Device shell $cmd | Out-String).Trim() }

Step "Connecting to $Device (choose Allow on the TV if it asks)"
adb connect $Device | Out-Host
if ((adb -s $Device get-state 2>$null) -ne 'device') { throw "Couldn't connect to $Device. Is ADB debugging on?" }

if ($Apk) {
    Step "Installing $Apk"
    adb -s $Device install -r $Apk | Out-Host
}
if (-not (Shell "pm path $pkg")) { throw "Lumen isn't installed. Run again with -Apk path\to\lumen.apk." }

Step 'Home button: opens Lumen'
$current = Shell 'settings get secure enabled_accessibility_services'
if ($current -and $current -ne 'null' -and $current -match [regex]::Escape($service)) {
    Write-Host '  already on'
} else {
    $list = if ($current -and $current -ne 'null') { "${current}:$service" } else { $service }
    Shell "settings put secure enabled_accessibility_services '$list'" | Out-Null
    Shell 'settings put secure accessibility_enabled 1' | Out-Null
    Write-Host '  turned on'
}

Step 'Wallpapers: allow Lumen to read /sdcard/Lumen/wallpapers'
Shell "pm grant $pkg android.permission.READ_EXTERNAL_STORAGE" | Out-Null

Step 'Opening Lumen, then compiling it ahead of time (about 20 seconds)'
Shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
Shell "am start -n $pkg/.MainActivity" | Out-Null
# Lumen hands its baseline profile to Android a few seconds after it starts.
Start-Sleep 12
Shell "cmd package compile -m speed-profile -f $pkg" | Out-Host

Write-Host ''
Write-Host 'Done. Press Home on the remote: Lumen opens. Lumen > Setup shows the status of each step.' -ForegroundColor Green
