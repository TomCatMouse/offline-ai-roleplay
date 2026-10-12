# Publish GitHub release: tag + upload APK + changelog
#
# Usage:
#   .\scripts\publish-release.ps1
#   .\scripts\publish-release.ps1 -Version 1.0.1
#   .\scripts\publish-release.ps1 -Version 1.0.1 -NotesOnly
#   .\scripts\publish-release.ps1 -Repo owner/name     (default: origin remote)
#       ^ only refresh the release body (changelog) of an existing release;
#         the tag and the uploaded APK are left untouched
#
# Steps:
#   1. read version from build.gradle.kts (or -Version)
#   2. pick the changelog for this version:
#        a. CHANGELOG.md section "## vX.Y.Z"   (authoritative; optional, skipped if absent)
#        b. fallback: git log since the previous tag: fix:/feat: commits become
#           BUG fixes / Features, every other subject is listed as-is
#   3. delete existing release/tag with the same name
#   4. create an ANNOTATED tag vX.Y.Z on main HEAD
#      (message = the full release notes: update record + device support + model link)
#   5. create release (body = same notes) and upload the APK
#
# IMPORTANT - GitHub asset name limitation:
#   GitHub release asset names must be ASCII. Non-ASCII characters are
#   sanitized by GitHub (CJK -> ".", accents folded), so a Chinese file name
#   can NOT be preserved on the release. We therefore upload the asset under
#   an ASCII name and keep the Chinese name in:
#     - the release title (shown on the release page)
#     - the release body (name mapping for users)
#   The canonical Chinese-named APK still lives in dist\<CN_DIR>\ locally.

param(
    [string]$Version = "",
    [string]$Token = "",
    [switch]$NotesOnly,
    # override for -NotesOnly runs on historical releases: build.gradle.kts holds
    # the CURRENT versionCode, which is wrong for an older release
    [int]$VersionCode = 0,
    # GitHub repo slug "owner/name"; auto-detected from the origin remote when empty
    [string]$Repo = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = if ($env:GITHUB_TOKEN) { $env:GITHUB_TOKEN } else { [Environment]::GetEnvironmentVariable("GITHUB_TOKEN", "User") }
}
if ([string]::IsNullOrWhiteSpace($Token)) { throw "missing GitHub token (env GITHUB_TOKEN)" }

$headers = @{
    "Authorization" = "token $Token"
    "Accept"        = "application/vnd.github.v3+json"
}
# ---- resolve the GitHub repo slug "owner/name" ----
#   1) -Repo argument      2) origin remote      3) hard error with instructions
if ([string]::IsNullOrWhiteSpace($Repo)) {
    # git config --get stays silent when the key is missing (no native stderr -> no early abort)
    $remoteUrl = (& git -C $root config --get remote.origin.url 2>$null | Select-Object -First 1)
    if ($remoteUrl -and ($remoteUrl -match 'github\.com[:/](?<slug>[^/\s]+/[^/\s]+?)(\.git)?/?$')) { $Repo = $Matches['slug'] }
}
if ([string]::IsNullOrWhiteSpace($Repo)) {
    throw "GitHub repo not set: run 'git remote add origin <url>' first, or pass -Repo owner/name"
}
$api = "https://api.github.com/repos/$Repo"

# Chinese strings from code points (script file stays pure ASCII).
# DIR_NAME   = U+6B63 U+5F0F U+7248                    (dist sub-dir)
# APP_NAME   = "AI" + U+89D2 U+8272 U+626E U+6F14
# MODEL_WORD = U+6A21 U+578B
$dirName = [string]::new([char[]]@(0x6B63, 0x5F0F, 0x7248))
$appName = "AI" + [string]::new([char[]]@(0x89D2, 0x8272, 0x626E, 0x6F14))
$modelWord = [string]::new([char[]]@(0x6A21, 0x578B))

$gradleContent = [System.IO.File]::ReadAllText((Join-Path $root "app\build.gradle.kts"), [System.Text.Encoding]::UTF8)
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = [regex]::Match($gradleContent, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
}
if ($VersionCode -gt 0) {
    $vc = $VersionCode
} else {
    $vc = [int][regex]::Match($gradleContent, 'versionCode\s*=\s*(\d+)').Groups[1].Value
}
$tag = "v$Version"
Write-Host "=== publish $tag (versionCode $vc)$(if ($NotesOnly) { ' [notes only]' }) ===" -ForegroundColor Cyan

# locate local APK (Chinese name = canonical local artifact)
$distDir = Join-Path $root ("dist\" + $dirName)
$localName = $appName + "_" + $Version + "_phys_release.apk"
$apk = Get-ChildItem -Path $distDir -Filter $localName -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $apk -and -not $NotesOnly) { throw "not found: $distDir\$localName (run pack-release.ps1 first)" }
if ($null -ne $apk) { Write-Host ("APK: " + $apk.FullName + " (" + [math]::Round($apk.Length / 1MB, 2) + " MB)") }

# ASCII asset name (GitHub sanitizes non-ASCII asset names)
$assetName = "AI-Roleplay-v$Version-phys.apk"
Write-Host "asset name (ASCII): $assetName"

# ============================================================================
#  Changelog (feature updates / bug fixes) -> release body + annotated tag
# ============================================================================
# 1) authoritative source (optional): CHANGELOG.md, section "## vX.Y.Z" up to the next "## "
function Get-ChangelogFromFile {
    param([string]$Path, [string]$Ver)
    if (!(Test-Path $Path)) { return "" }
    $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    $lines = $text -split "`r?`n"
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        # match "## v1.0.2", "## 1.0.2", "## v1.0.2 (2026-10-07)"
        if ($lines[$i] -match ('^##\s+v?' + [regex]::Escape($Ver) + '(\s|$)')) { $start = $i + 1; break }
    }
    if ($start -lt 0) { return "" }
    $out = @()
    for ($i = $start; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^##\s') { break }
        $out += $lines[$i]
    }
    # drop trailing blank lines / markdown separators, so the injected block does
    # not butt up against the template's own "---" and produce a doubled rule
    while ($out.Count -gt 0) {
        $last = $out[$out.Count - 1].Trim()
        if ($last -eq "" -or $last -eq "---" -or $last -eq "***" -or $last -eq "___") {
            if ($out.Count -eq 1) { $out = @() } else { $out = $out[0..($out.Count - 2)] }
        } else { break }
    }
    return (($out -join "`n").Trim())
}

# 2) fallback: fix:/feat: commits since the previous release tag
function Get-ChangelogFromGit {
    param([string]$PrevTag)
    $range = if ($PrevTag) { "$PrevTag..HEAD" } else { "-30" }
    $raw = & git log --no-merges --pretty=format:"%s" $range 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $raw) {
        $raw = & git log --no-merges --pretty=format:"%s" -30 2>$null
    }
    $fix = @(); $feat = @(); $other = @()
    foreach ($line in @($raw)) {
        $s = "$line".Trim()
        if ($s -eq "") { continue }
        if     ($s -match '^fix(\(.+\))?!?:\s*(.+)$')  { $fix  += ("- " + $matches[2]) }
        elseif ($s -match '^feat(\(.+\))?!?:\s*(.+)$') { $feat += ("- " + $matches[2]) }
        else { $other += ("- " + $s) }
    }
    # Section headings are plain Chinese text (no symbol markers). They are built
    # from code points so this script file itself stays pure ASCII -- PowerShell 5.1
    # reads BOM-less UTF-8 as ANSI/GBK and would otherwise mangle them.
    $hBugFix = "### BUG " + [string]::new([char[]]@(0x4FEE, 0x590D))
    $hFeat   = "### " + [string]::new([char[]]@(0x529F, 0x80FD, 0x4F18, 0x5316))
    $hOther  = "### " + [string]::new([char[]]@(0x5176, 0x5B83, 0x6539, 0x52A8))
    $hThis   = "### " + [string]::new([char[]]@(0x672C, 0x6B21, 0x6539, 0x52A8))
    $parts = @()
    if ($fix.Count  -gt 0) { $parts += $hBugFix; $parts += $fix }
    if ($feat.Count -gt 0) { $parts += $hFeat; $parts += $feat }
    if ($fix.Count -gt 0 -or $feat.Count -gt 0) {
        if ($other.Count -gt 0) { $parts += $hOther; $parts += $other }
    } elseif ($other.Count -gt 0) {
        # no conventional fix:/feat: commits -> list everything so the release page is never blank
        $parts += $hThis; $parts += $other
    }
    return ($parts -join "`n")
}

$changelog = Get-ChangelogFromFile (Join-Path $root "CHANGELOG.md") $Version
if ([string]::IsNullOrWhiteSpace($changelog)) {
    # find previous release tag (most recent release that is not this one)
    $prevTag = ""
    try {
        $relList = Invoke-RestMethod -Uri "$api/releases" -Headers $headers
        foreach ($r in $relList) { if ($r.tag_name -ne $tag) { $prevTag = $r.tag_name; break } }
    } catch { }
    Write-Host "CHANGELOG.md has no section for $Version -> falling back to git log" -ForegroundColor Yellow
    $changelog = Get-ChangelogFromGit $prevTag
}
if ([string]::IsNullOrWhiteSpace($changelog)) {
    $changelog = "_No changelog recorded for this version._"
}
$clLines = ($changelog -split "`n").Count
Write-Host ("changelog: " + $clLines + " lines")

# ---- release notes ----
# Filled in here (not at release time) so the annotated tag can carry the very
# same text as the release body: update record + device support + model link.
$notesPath = Join-Path $root "scripts\release-notes.txt"
$notes = [System.IO.File]::ReadAllText($notesPath, [System.Text.Encoding]::UTF8)
$notes = $notes.Replace("{{APP}}", $appName)
$notes = $notes.Replace("{{DIR}}", $dirName)
$notes = $notes.Replace("{{TAG}}", $tag)
$notes = $notes.Replace("{{MODEL}}", $modelWord)
$notes = $notes.Replace("{{VC}}", "$vc")
$notes = $notes.Replace("{{LOCAL}}", $localName)
$notes = $notes.Replace("{{ASSET}}", $assetName)
$notes = $notes.Replace("{{CHANGELOG}}", $changelog)

# ---- locate existing release (and drop its assets, full mode only) ----
Write-Host ""
Write-Host "=== remove existing release ===" -ForegroundColor Cyan
$releases = Invoke-RestMethod -Uri "$api/releases" -Headers $headers
$releaseId = 0
foreach ($r in $releases) {
    if ($r.tag_name -eq $tag) {
        $releaseId = $r.id
        if ($NotesOnly) {
            # notes-only: keep the uploaded APK, refresh the body only
            Write-Host ("  kept " + $r.assets.Count + " asset(s)")
        } else {
            foreach ($a in $r.assets) {
                Invoke-RestMethod -Uri $a.url -Method Delete -Headers $headers | Out-Null
                Write-Host ("  deleted asset: " + $a.name)
            }
        }
    }
}
if ($NotesOnly -and $releaseId -eq 0) { throw "notes-only mode: no existing release for $tag" }

# ---- drop existing tag ----
if (-not $NotesOnly) {
    Write-Host ""
    Write-Host "=== remove existing tag ===" -ForegroundColor Cyan
    $tags = Invoke-RestMethod -Uri "$api/tags" -Headers $headers
    foreach ($t in $tags) {
        if ($t.name -eq $tag) {
            Invoke-RestMethod -Uri "$api/git/refs/tags/$tag" -Method Delete -Headers $headers | Out-Null
            Write-Host "  deleted tag: $tag"
        }
    }

    # ---- create ANNOTATED tag (message carries the changelog) ----
    Write-Host ""
    Write-Host "=== create annotated tag $tag ===" -ForegroundColor Cyan
    $main = Invoke-RestMethod -Uri "$api/commits/main" -Headers $headers
    $tagMessage = "$tag`n`n$notes"
    $tagPayload = @{
        tag     = $tag
        message = $tagMessage
        object  = $main.sha
        type    = "commit"
    }
    $tagBytes = [System.Text.Encoding]::UTF8.GetBytes(($tagPayload | ConvertTo-Json -Depth 3))
    $tagObj = Invoke-RestMethod -Uri "$api/git/tags" -Method Post -Headers $headers -Body $tagBytes -ContentType "application/json; charset=utf-8"
    $refBody = @{ ref = "refs/tags/$tag"; sha = $tagObj.sha } | ConvertTo-Json
    Invoke-RestMethod -Uri "$api/git/refs" -Method Post -Headers $headers -Body $refBody -ContentType "application/json" | Out-Null
    Write-Host ("annotated tag -> main HEAD " + $main.sha.Substring(0, 10) + " (tag obj " + $tagObj.sha.Substring(0, 10) + ")")
}

# ---- create / update release (body sent as UTF-8 bytes!) ----
Write-Host ""
Write-Host "=== create release ===" -ForegroundColor Cyan
# Notes template lives in a UTF-8 file so this script stays pure ASCII
# (PowerShell 5.1 reads BOM-less UTF-8 as ANSI/GBK and would break on CJK).
# $notes is already filled in above -- the annotated tag reuses the same text.
$relPayload = @{
    tag_name   = $tag
    name       = "$tag $dirName"
    body       = $notes
    prerelease = $false
    draft      = $false
}
if ($releaseId -gt 0) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($relPayload | ConvertTo-Json -Depth 3))
    $rel = Invoke-RestMethod -Uri "$api/releases/$releaseId" -Method Patch -Headers $headers -Body $bytes -ContentType "application/json; charset=utf-8"
    Write-Host ("updated release id: " + $rel.id)
} else {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($relPayload | ConvertTo-Json -Depth 3))
    $rel = Invoke-RestMethod -Uri "$api/releases" -Method Post -Headers $headers -Body $bytes -ContentType "application/json; charset=utf-8"
    Write-Host ("release id: " + $rel.id)
}

# ---- upload APK via curl.exe (reliable binary upload on Windows) ----
$asset = $null
if (-not $NotesOnly) {
    Write-Host ""
    Write-Host "=== upload APK ===" -ForegroundColor Cyan
    $uploadUrl = "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=$assetName"
    $out = & curl.exe -sS -X POST -H "Authorization: token $Token" -H "Content-Type: application/octet-stream" --data-binary "@$($apk.FullName)" $uploadUrl 2>&1
    $asset = $out | ConvertFrom-Json
    if ($null -eq $asset.name) { throw ("upload failed: " + ($out -join "`n")) }
    Write-Host ("uploaded: " + $asset.name + " (" + [math]::Round($asset.size / 1MB, 2) + " MB)")
} else {
    Write-Host ""
    Write-Host "notes-only mode: tag and APK left untouched" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "=== done ===" -ForegroundColor Green
Write-Host "page : https://github.com/$Repo/releases/tag/$tag"
if ($null -ne $asset) { Write-Host "link : $($asset.browser_download_url)" }
