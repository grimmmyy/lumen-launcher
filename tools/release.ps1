<#
.SYNOPSIS
  Publishes a Lumen release: bumps the version, commits, tags and pushes.

.EXAMPLE
  ./tools/release.ps1 0.11.1

.NOTES
  The pushed v* tag makes GitHub Actions build lumen.apk, sign it with the Lumen
  release key (repository secrets) and publish a GitHub release with the setup
  scripts. Lumen on each TV finds it within a day (Settings > Updates > Check now
  to look right away) and installs it when you confirm.
  Run from a clean working tree on main.
#>
param([Parameter(Mandatory = $true)][string]$Version)

$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Version must look like 0.11.1" }
Set-Location (Split-Path $PSScriptRoot -Parent)
if (git status --porcelain) { throw 'Commit or stash your changes first.' }
if ((git rev-parse --abbrev-ref HEAD) -ne 'main') { throw 'Releases are made from main.' }
if (git tag --list "v$Version") { throw "v$Version already exists." }

$gradle = 'app/build.gradle'
$text = Get-Content $gradle -Raw
$code = [int]([regex]::Match($text, 'versionCode (\d+)').Groups[1].Value) + 1
$text = $text -replace 'versionCode \d+', "versionCode $code" -replace "versionName '[^']*'", "versionName '$Version'"
[IO.File]::WriteAllText((Resolve-Path $gradle), $text)

git add $gradle
git commit -q -m "Release $Version" -m "Version code $code."
git tag -a "v$Version" -m "Lumen $Version"
git push -q
git push -q origin "v$Version"
Write-Host "Pushed v$Version (version code $code). GitHub Actions is building the release:" -ForegroundColor Green
Write-Host "  gh run watch   or   https://github.com/grimmmyy/lumen-launcher/actions"
