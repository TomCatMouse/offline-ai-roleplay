# Advance the project version to the next one (run this AFTER a release is published).
#
# Version rule (user requirement):
#   The version number advances ONLY when a release has actually been pushed to
#   GitHub. Run this script right after a successful publish-release.ps1 run, then
#   commit and push. Before that, every local build keeps the current version --
#   no matter how many times you pack.
#
# Usage:
#   .\scripts\bump-version.ps1              # patch +1 and versionCode +1 (normal case)
#   .\scripts\bump-version.ps1 -Minor       # minor +1, patch reset to 0
#   .\scripts\bump-version.ps1 -Major       # major +1, minor and patch reset to 0
#   .\scripts\bump-version.ps1 -DryRun      # print what would change, write nothing
#
# It only rewrites versionName / versionCode inside app/build.gradle.kts and prints
# the suggested git commands. It does NOT build, commit or push anything.
#
# Commit message convention (user requirement, 2026-10-13):
#   Keep this post-release commit NEUTRAL and short:
#       chore: bump version to <next version>
#   Do NOT write a release narrative in the subject (no "1.0.5 released, next ...").
#   That subject lands in the public commit list and in the release page's
#   "commits since this release" comparison, and was explicitly not wanted there.
#
# This file is intentionally pure ASCII (PowerShell 5.1 reads .ps1 as ANSI unless a
# BOM is present, so non-ASCII text here could be mangled).

param(
    [switch]$Minor,
    [switch]$Major,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$gradlePath = Join-Path $root "app\build.gradle.kts"

$content = [System.IO.File]::ReadAllText($gradlePath, [System.Text.Encoding]::UTF8)
$vnMatch = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
$vcMatch = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
if (-not $vnMatch.Success -or -not $vcMatch.Success) {
    throw "cannot parse versionName / versionCode from app\build.gradle.kts"
}
$vn = $vnMatch.Groups[1].Value
$vc = [int]$vcMatch.Groups[1].Value

$parts = @($vn.Split('.'))
while ($parts.Count -lt 3) { $parts += "0" }
$maj = [int]$parts[0]
$min = [int]$parts[1]
$pat = [int]$parts[2]

if ($Major) {
    $kind = "major"; $maj = $maj + 1; $min = 0; $pat = 0
} elseif ($Minor) {
    $kind = "minor"; $min = $min + 1; $pat = 0
} else {
    $kind = "patch"; $pat = $pat + 1
}
$newVn = "$maj.$min.$pat"
$newVc = $vc + 1

Write-Host "=== bump version ===" -ForegroundColor Cyan
Write-Host "current : versionName=$vn versionCode=$vc"
Write-Host "kind    : $kind"
Write-Host "next    : versionName=$newVn versionCode=$newVc" -ForegroundColor Green

if ($DryRun) {
    Write-Host "dry run : nothing written" -ForegroundColor Yellow
    exit 0
}

$newContent = $content -replace 'versionCode\s*=\s*\d+', ('versionCode = ' + $newVc)
$newContent = $newContent -replace 'versionName\s*=\s*"[^"]+"', ('versionName = "' + $newVn + '"')
[System.IO.File]::WriteAllText($gradlePath, $newContent, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "updated : app\build.gradle.kts" -ForegroundColor Green
Write-Host "next steps:" -ForegroundColor Cyan
Write-Host "  git add app/build.gradle.kts"
Write-Host ("  git commit -m `"chore: bump version to " + $newVn + "`"")
Write-Host "  git push origin main"
