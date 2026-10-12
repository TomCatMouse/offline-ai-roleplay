<#
  默认包（MuMu 版）打包脚本 —— 和真机包（Flyme 版）是完全独立的两套逻辑。

  默认约定（用户 2026-07 明确要求）：
    - 「打包」默认就指打这一套（除非明说要真机/Flyme 包）
    - 产物：dist\MuMu版\AI角色扮演-MuMu版-v<版本>.apk
    - 不含任何推理引擎：仍是「连 PC 上的 llama-server」那套，体积 ~16MB

  本脚本做的事：
    1) :app:assembleDebug（或 -Release）
    2) 产物放 dist\MuMu版\，绝不碰 Flyme 版目录
    3) 有设备连着时：自动安装 + 截图冒烟测试（screenshot-test.ps1 -Target mumu），
       截图落在模拟器/手机 /storage/emulated/0 根目录的「AI角色扮演测试截图-<时间戳>」文件夹，
       拉回 dist\MuMu版\测试截图\ 后自动清掉设备侧文件夹（-SkipTest 可跳过）

  用法：
    powershell -File scripts\build-default.ps1
    powershell -File scripts\build-default.ps1 -Release
    powershell -File scripts\build-default.ps1 -SkipTest
#>
param(
    [switch]$Release,
    [switch]$SkipTest
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$variant = if ($Release) { 'release' } else { 'debug' }
$apk = Join-Path $root "app\build\outputs\apk\$variant\app-$variant.apk"
$dist = Join-Path $root 'dist\MuMu版'

Write-Host "[MuMu 版] 1/3 编译（$variant，不含引擎）..."
# 注意：gradlew.bat 构建失败时返回的退出码也是 0（实测），所以不能只看 $LASTEXITCODE，
# 否则会把上一版残留的 APK 当成新产物拷进 dist。这里改看构建输出。
$buildOut = (& (Join-Path $root 'gradlew.bat') ":app:assemble$($variant.Substring(0,1).ToUpper() + $variant.Substring(1))" --console=plain 2>&1 | Out-String)
$buildOut.TrimEnd() -split "`r?`n" | ForEach-Object { Write-Host $_ }
if ($buildOut -notmatch 'BUILD SUCCESSFUL') { throw '编译失败（看上面的构建输出）' }
if (!(Test-Path $apk)) { throw "没找到产物：$apk" }

$version = (Select-String -Path (Join-Path $root 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"' |
    Select-Object -First 1).Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$target = Join-Path $dist ("AI角色扮演-MuMu版-v{0}.apk" -f $version)
Copy-Item $apk $target -Force

Write-Host ''
Write-Host ('[MuMu 版] 2/3 产物：{0}（{1:N1} MB）' -f $target, ((Get-Item $target).Length / 1MB))
Write-Host '  应用名：AI角色扮演 ；包名：com.example.roleplay'
Write-Host '  这份包不含本地推理引擎，连接地址在 App 设置页里填 PC 的 IP。'

# ---- 3/3 冒烟测试（有设备才跑；没设备只提醒，不算失败） ----
if ($SkipTest) {
    Write-Host '[MuMu 版] 已跳过冒烟测试（-SkipTest）'
    exit 0
}
& (Join-Path $PSScriptRoot 'screenshot-test.ps1') -Target mumu -Package com.example.roleplay -Apk $target -DistDir $dist
