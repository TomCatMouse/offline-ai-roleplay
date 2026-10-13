# Pack release APK: build + output to dist\<CN_DIR>  (does NOT bump the version by default)
#
# Usage:
#   .\scripts\pack-release.ps1                # default: keep the current version, just build
#   .\scripts\pack-release.ps1 -Bump patch    # escape hatch: force patch +1 (bug fix)
#   .\scripts\pack-release.ps1 -Bump minor    # escape hatch: force minor +1 (feature)
#   .\scripts\pack-release.ps1 -Bump major    # escape hatch: force major +1 (big change)
#   .\scripts\pack-release.ps1 -Bump auto     # escape hatch: bump patch if sources changed
#
# Version rule (user requirement):
#   The version number advances ONLY when a release is actually published to GitHub.
#   Right after a successful publish, run scripts\bump-version.ps1 (patch +1 and
#   versionCode +1), then commit and push. Until then every local build keeps the
#   current version -- packing any number of times never consumes a version number.
#
#   -Bump auto stays available as an escape hatch: it hashes app/src +
#   app/build.gradle.kts + engine/android-snapdragon/jniLibs, compares the result
#   with scripts/version-state.json and bumps patch when the hash differs.

param(
    [ValidateSet("auto", "patch", "minor", "major", "none")]
    [string]$Bump = "none"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$gradlePath = Join-Path $root "app\build.gradle.kts"
$statePath = Join-Path $root "scripts\version-state.json"

# Chinese names from code points (immune to script file encoding).
# DIR_NAME = U+6B63 U+5F0F U+7248                     (dist sub-dir, "zheng shi ban")
# APP_NAME = "AI" + U+89D2 U+8272 U+626E U+6F14
$dirName = [string]::new([char[]]@(0x6B63, 0x5F0F, 0x7248))
$appName = "AI" + [string]::new([char[]]@(0x89D2, 0x8272, 0x626E, 0x6F14))

function Get-SourceHash {
    $files = @()
    $files += Get-ChildItem -Path (Join-Path $root "app\src") -Recurse -File -ErrorAction SilentlyContinue
    $files += Get-Item $gradlePath
    # 引擎 .so 也会被打进 APK（jniLibs 指向 engine\android-snapdragon\jniLibs），
    # 所以重建引擎同样应该触发版本递增 —— 只算 app/src 会漏掉这类改动。
    $files += Get-ChildItem -Path (Join-Path $root "engine\android-snapdragon\jniLibs") -Recurse -File -ErrorAction SilentlyContinue
    $sb = New-Object System.Text.StringBuilder
    foreach ($f in ($files | Sort-Object { $_.FullName.Substring($root.Length) })) {
        [void]$sb.Append($f.FullName.Substring($root.Length))
        [void]$sb.Append(':')
        [void]$sb.Append((Get-FileHash $f.FullName -Algorithm SHA256).Hash)
        [void]$sb.Append("`n")
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
    return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLower()
}

Write-Host "=== read current version ===" -ForegroundColor Cyan
$gradleContent = [System.IO.File]::ReadAllText($gradlePath, [System.Text.Encoding]::UTF8)
$vn = [regex]::Match($gradleContent, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$vc = [int][regex]::Match($gradleContent, 'versionCode\s*=\s*(\d+)').Groups[1].Value
Write-Host "current: versionName=$vn versionCode=$vc"

# ---- decide bump ----
$srcHash = Get-SourceHash
$needBump = $false
$bumpKind = $Bump

if ($Bump -eq "auto") {
    if (Test-Path $statePath) {
        $state = [System.IO.File]::ReadAllText($statePath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($state.sourceHash -ne $srcHash) {
            $needBump = $true
            $bumpKind = "patch"
            Write-Host "source changed -> auto bump patch" -ForegroundColor Yellow
        } else {
            Write-Host "source unchanged -> keep version" -ForegroundColor DarkGray
        }
    } else {
        $needBump = $true
        $bumpKind = "patch"
        Write-Host "no previous state -> first bump (patch)" -ForegroundColor Yellow
    }
} elseif ($Bump -ne "none") {
    $needBump = $true
    Write-Host "manual bump: $Bump" -ForegroundColor Yellow
}

# ---- compute new version ----
$newVn = $vn
$newVc = $vc
if ($needBump) {
    $parts = @($vn.Split('.'))
    while ($parts.Count -lt 3) { $parts += "0" }
    $maj = [int]$parts[0]; $min = [int]$parts[1]; $pat = [int]$parts[2]
    switch ($bumpKind) {
        "major" { $maj = $maj + 1; $min = 0; $pat = 0 }
        "minor" { $min = $min + 1; $pat = 0 }
        "patch" { $pat = $pat + 1 }
    }
    $newVn = "$maj.$min.$pat"
    $newVc = $vc + 1
    Write-Host "version: $vn -> $newVn (versionCode $vc -> $newVc)" -ForegroundColor Green

    $newContent = $gradleContent -replace 'versionCode\s*=\s*\d+', ('versionCode = ' + $newVc)
    $newContent = $newContent -replace 'versionName\s*=\s*"[^"]+"', ('versionName = "' + $newVn + '"')
    [System.IO.File]::WriteAllText($gradlePath, $newContent, (New-Object System.Text.UTF8Encoding($false)))
} else {
    Write-Host "version kept: $newVn (versionCode $newVc)" -ForegroundColor DarkGray
}

# ---- build ----
Write-Host ""
Write-Host "=== build physRelease ===" -ForegroundColor Cyan
# Do NOT trust $LASTEXITCODE here: on this setup gradlew.bat exits with 0 even when
# the build FAILS (verified), so the old check was dead code and a stale APK from a
# previous build could be copied out as if it were a fresh one. Parse the output.
$buildOut = (& (Join-Path $root "gradlew.bat") :app:assemblePhysRelease --console=plain 2>&1 | Out-String)
$buildOut.TrimEnd() -split "`r?`n" | ForEach-Object { Write-Host $_ }
if ($buildOut -notmatch "BUILD SUCCESSFUL") { throw "gradle build failed - see output above" }

# ---- copy to dist\<CN_DIR> ----
$distDir = Join-Path $root ("dist\" + $dirName)
if (!(Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir -Force | Out-Null }
$src = Join-Path $root "app\build\outputs\apk\physRelease\app-physRelease.apk"
$dest = Join-Path $distDir ($appName + "_" + $newVn + "_phys_release.apk")
Copy-Item $src $dest -Force

# ---- record state ----
$json = @{
    versionName = $newVn
    versionCode = $newVc
    sourceHash  = (Get-SourceHash)
    packagedAt  = (Get-Date).ToString("s")
} | ConvertTo-Json
[System.IO.File]::WriteAllText($statePath, $json, (New-Object System.Text.UTF8Encoding($false)))

$size = [math]::Round((Get-Item $dest).Length / 1MB, 2)
Write-Host ""
Write-Host "=== done ===" -ForegroundColor Green
Write-Host "file : $dest"
Write-Host "size : $size MB"
Write-Host "ver  : $newVn (versionCode $newVc)"
