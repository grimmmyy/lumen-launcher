<#
.SYNOPSIS
  Measures Lumen's launch latency, scrolling smoothness and memory on one device.

.EXAMPLE
  ./tools/bench.ps1 -Serial emulator-5580
  ./tools/bench.ps1 -Serial 192.168.1.160:5555 -Runs 5 -Compile

.NOTES
  -Serial is required so the benchmark never runs on a device by accident.
  It force-stops Lumen and sends D-pad key presses, so don't run it while
  someone is watching that TV. Android turns off an app's accessibility
  service when the app is force-stopped, so the Home-button setting is saved
  first and put back at the end.
#>
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Package = 'io.github.grimmmyy.lumen',
    [int]$Runs = 5,
    [switch]$Compile,
    [string]$Label = ''
)

$ErrorActionPreference = 'Stop'
$activity = "$Package/.MainActivity"

# adb's harmless stderr warnings (e.g. "Broken pipe" after grep -m1) would stop the
# script under Windows PowerShell 5.1; drop them and keep stdout.
function Invoke-Device([string]$cmd) {
    $ErrorActionPreference = 'Continue'
    adb -s $Serial shell $cmd 2>$null
}

# `am start -W` waits forever while the display is off, so wake the device before each launch.
function Wake { Invoke-Device 'input keyevent KEYCODE_WAKEUP' | Out-Null }

function Get-Median([double[]]$values) {
    $s = $values | Sort-Object
    if ($s.Count -eq 0) { return 0 }
    if ($s.Count % 2) { return $s[[int][math]::Floor($s.Count / 2)] }
    return ($s[$s.Count / 2 - 1] + $s[$s.Count / 2]) / 2
}

function Get-StartTime([string[]]$output, [switch]$Warm) {
    $text = $output -join "`n"
    # For a HOT launch (task brought to front), TotalTime repeats a stale value; WaitTime is real.
    # Fire OS labels hot returns "COLD", so warm starts always use WaitTime.
    $field = if ($Warm -or $text -match 'LaunchState:\s*HOT') { 'WaitTime' } else { 'TotalTime' }
    $m = [regex]::Match($text, "${field}:\s*(\d+)")
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return $null
}

function Get-Frames([string]$tag) {
    $g = (Invoke-Device "dumpsys gfxinfo $Package") -join "`n"
    $num = { param($pattern) $m = [regex]::Match($g, $pattern); if ($m.Success) { [double]$m.Groups[1].Value } else { [double]::NaN } }
    [pscustomobject]@{
        Test        = $tag
        Frames      = & $num 'Total frames rendered:\s*(\d+)'
        'Janky %'   = & $num 'Janky frames:\s*\d+\s*\(([\d.]+)%\)'
        'p50 ms'    = & $num '50th percentile:\s*(\d+)ms'
        'p90 ms'    = & $num '90th percentile:\s*(\d+)ms'
        'p95 ms'    = & $num '95th percentile:\s*(\d+)ms'
        'p99 ms'    = & $num '99th percentile:\s*(\d+)ms'
        'Slow UI'   = & $num 'Number Slow UI thread:\s*(\d+)'
        'Slow draw' = & $num 'Number Slow issue draw commands:\s*(\d+)'
    }
}

$version = ((Invoke-Device "dumpsys package $Package | grep -m1 versionName") -replace '.*versionName=', '').Trim()
$model = (Invoke-Device 'getprop ro.product.model').Trim()
Write-Host "Lumen $version on $model ($Serial) $Label" -ForegroundColor Cyan

# Force-stopping Lumen removes its Home-button service from the enabled list; restore it after.
$a11y = (Invoke-Device 'settings get secure enabled_accessibility_services' | Out-String).Trim()
$a11yOn = (Invoke-Device 'settings get secure accessibility_enabled' | Out-String).Trim()
function Restore-HomeButton {
    if ($a11y -and $a11y -ne 'null') {
        Invoke-Device "settings put secure enabled_accessibility_services $a11y" | Out-Null
        Invoke-Device "settings put secure accessibility_enabled $a11yOn" | Out-Null
    }
}

try {
# Warm-up: first launch renders and caches artwork; not counted.
Wake; Invoke-Device "am start -W -n $activity" | Out-Null
Start-Sleep 4

if ($Compile) {
    # Lumen hands its baseline profile to Android a few seconds after it starts
    # (androidx.profileinstaller). Compiling before that compiles almost nothing:
    # measured on a Fire TV Stick, the hot path was then still JIT-compiled.
    Write-Host 'Compiling with the baseline profile...'
    Start-Sleep 8
    Invoke-Device "cmd package compile -m speed-profile -f $Package" | Out-Null
}

# Cold start: process killed, caches on disk (what you get after a reboot or OOM kill).
$cold = @()
for ($i = 0; $i -lt $Runs; $i++) {
    Invoke-Device "am force-stop $Package" | Out-Null
    Start-Sleep -Milliseconds 800
    $t = Get-StartTime $(Wake; Invoke-Device "am start -W -n $activity")
    if ($t) { $cold += $t }
    Start-Sleep 2
}

# Warm start: process alive, returning from another app (pressing Home).
# Put the Home-button service back first: while it is bound, Android keeps Lumen at
# foreground priority when hidden. Without it (as after the force-stops above) a hidden
# Lumen is throttled like any background app and returns ~130 ms slower on a Fire TV.
Restore-HomeButton
Start-Sleep 2
$warm = @()
for ($i = 0; $i -lt $Runs; $i++) {
    Wake; Invoke-Device 'am start -W -a android.settings.SETTINGS' | Out-Null
    Start-Sleep 1
    $t = Get-StartTime $(Wake; Invoke-Device "am start -W -n $activity") -Warm
    if ($t) { $warm += $t }
    Start-Sleep 1
}

# Scrolling: paced presses (normal use), then a burst (a held D-pad key).
Wake; Invoke-Device "am start -W -n $activity" | Out-Null
Start-Sleep 3
Invoke-Device "dumpsys gfxinfo $Package reset" | Out-Null
Invoke-Device 'for i in 1 2 3 4 5 6; do input keyevent 22; sleep 0.35; done; for i in 1 2 3 4 5 6; do input keyevent 21; sleep 0.35; done' | Out-Null
Start-Sleep 1
$across = Get-Frames 'Across a row'

Invoke-Device "dumpsys gfxinfo $Package reset" | Out-Null
Invoke-Device 'for i in 1 2 3 4 5 6 7 8; do input keyevent 20; sleep 0.45; done; for i in 1 2 3 4 5 6 7 8; do input keyevent 19; sleep 0.45; done' | Out-Null
Start-Sleep 1
$down = Get-Frames 'Down the page'

Invoke-Device "dumpsys gfxinfo $Package reset" | Out-Null
Invoke-Device 'input keyevent 22 22 22 22 22 22 22 22 21 21 21 21 21 21 21 21 20 20 20 20 20 20 20 20 19 19 19 19 19 19 19 19' | Out-Null
Start-Sleep 2
$burst = Get-Frames 'Held-key burst'

function Get-PssMb {
    $mem = (Invoke-Device "dumpsys meminfo $Package") -join "`n"
    $pss = [regex]::Match($mem, 'TOTAL PSS:\s*(\d+)')
    if ($pss.Success) { [math]::Round([int]$pss.Groups[1].Value / 1024, 1) } else { 'n/a' }
}
$pssMb = Get-PssMb
# What Lumen holds while you watch something else: another app in front for a few seconds.
Wake; Invoke-Device 'am start -W -a android.settings.SETTINGS' | Out-Null
Start-Sleep 6
$bgMb = Get-PssMb
Wake; Invoke-Device "am start -W -n $activity" | Out-Null

Write-Host ''
Write-Host ("Cold start   median {0} ms   (runs: {1})" -f (Get-Median $cold), ($cold -join ', '))
Write-Host ("Warm start   median {0} ms   (runs: {1})" -f (Get-Median $warm), ($warm -join ', '))
Write-Host ("Memory       {0} MB PSS on screen, {1} MB while another app is in front" -f $pssMb, $bgMb)
@($across, $down, $burst) | Format-Table -AutoSize | Out-String | Write-Host
} finally {
    Restore-HomeButton
}
