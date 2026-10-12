# Pack release APK: auto version bump + build + output to dist\<CN_DIR>
#
# Usage:
#   .\scripts\pack-release.ps1                # auto: bump patch if sources changed
#   .\scripts\pack-release.ps1 -Bump patch    # force patch +1  (bug fix)
#   .\scripts\pack-release.ps1 -Bump minor    # force minor +1  (feature)
#   .\scripts\pack-release.ps1 -Bump major    # force major +1  (big change)
#   .\scripts\pack-release.ps1 -Bump none     # keep version, just repack
#
# Auto-bump rule:
#   Hash app/src + app/build.gradle.kts + models/android-snapdragon/jniLibs,
#   compare with scripts/version-state.json.
#   If the hash differs (= feature update or bug fix), bump patch and versionCode +1.

param(
    [ValidateSet("auto", "patch", "minor", "major", "none")]
    [string]$Bump = "auto"
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
    # 引擎 .so 也会被打进 APK（jniLibs 指向 models\android-snapdragon\jniLibs），
    # 所以重建引擎同样应该触发版本递增 —— 只算 app/src 会漏掉这类改动。
    $files += Get-ChildItem -Path (Join-Path $root "models\android-snapdragon\jniLibs") -Recurse -File -ErrorAction SilentlyContinue
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
& (Join-Path $root "gradlew.bat") :app:assemblePhysRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "build failed" }

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
