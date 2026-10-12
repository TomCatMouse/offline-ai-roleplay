<#
  真机包（Flyme 版 / phys）打包脚本 —— 和默认包（MuMu 版）是完全独立的两套逻辑。

  重要约定（用户 2026-07 明确要求）：
    - 「打包」默认打的是 MuMu 版（build-default.ps1）；真机/Flyme 包只在明确要求时打
    - 产物：dist\Flyme版\AI角色扮演-Flyme版-v<版本>.apk
    - 内置 engine/android-snapdragon 里的骁龙 arm64 llama.cpp 引擎
      （CPU + Adreno OpenCL GPU + Hexagon NPU 后端），模型放手机本地

  本脚本做的事：
    1) :app:assemblePhys
    2) 产物放 dist\Flyme版\
    3) 自动装到真机（默认自动选唯一在线的真机；多设备时用 -Device 或环境变量 ANDROID_SERIAL），并顺手搞定：
       - Flyme 等国产 ROM 的 USB 安装限制（临时改 flyme_installer_settings，装完恢复）
       - 「所有文件访问权限」（读 /storage/emulated/0/AI角色扮演/模型 里的本地模型）
       - 建好 /storage/emulated/0/AI角色扮演/模型 目录
    4) 冒烟测试（screenshot-test.ps1 -Target flyme）：截图落在
       /storage/emulated/0/AI角色扮演/测试截图-<时间戳>\，拉回 dist\Flyme版\测试截图\ 后
       自动清掉设备侧文件夹（-SkipTest 可跳过）

  用法：
    powershell -File scripts\build-phys.ps1
    powershell -File scripts\build-phys.ps1 -SkipInstall     # 只打包不安装
    powershell -File scripts\build-phys.ps1 -SkipTest        # 打包+安装但不跑截图测试
    powershell -File scripts\build-phys.ps1 -Device 别的序列号
#>
param(
    # 留空 = 自动选唯一在线的真机（模拟器不算）；也可设环境变量 ANDROID_SERIAL
    [string]$Device = '',
    [switch]$SkipInstall,
    [switch]$SkipTest
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Device)) {
    if ($env:ANDROID_SERIAL) {
        $Device = $env:ANDROID_SERIAL
    } else {
        $cand = @()
        foreach ($line in (& adb devices)) {
            $t = ($line -replace "`r", '').Trim()
            if ($t -match '^(\S+)\s+device$' -and $Matches[1] -notmatch '^emulator-') { $cand += $Matches[1] }
        }
        if ($cand.Count -eq 1) { $Device = $cand[0] }
        else { throw ("没检测到唯一真机（当前在线：" + (($cand -join ', ') -replace '^$', '无') + "），请用 -Device <序列号> 指定") }
    }
}
Write-Host ("[Flyme 版] 目标设备：{0}" -f $Device)

# 中文路径（/storage/emulated/0/AI角色扮演/模型）传给 adb 需要 UTF-8 代码页，5.1 默认 GBK 会串味
if ($env:OS -eq 'Windows_NT') { chcp 65001 | Out-Null }
$root = Split-Path -Parent $PSScriptRoot
$pkg = 'com.example.roleplay.phys'
$apk = Join-Path $root 'app\build\outputs\apk\phys\app-phys.apk'
$dist = Join-Path $root 'dist\Flyme版'

Write-Host '[Flyme 版] 1/5 编译（内置骁龙引擎，约 200MB 原生库）...'
# 注意：gradlew.bat 构建失败时返回的退出码也是 0（实测），所以不能只看 $LASTEXITCODE，
# 否则会把上一版残留的 APK 当成新产物拷进 dist。这里改看构建输出。
$buildOut = (& (Join-Path $root 'gradlew.bat') ':app:assemblePhys' --console=plain 2>&1 | Out-String)
$buildOut.TrimEnd() -split "`r?`n" | ForEach-Object { Write-Host $_ }
if ($buildOut -notmatch 'BUILD SUCCESSFUL') { throw '编译失败（看上面的构建输出）' }
if (!(Test-Path $apk)) { throw "没找到产物：$apk" }

$version = (Select-String -Path (Join-Path $root 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"' |
    Select-Object -First 1).Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$target = Join-Path $dist ("AI角色扮演-Flyme版-v{0}.apk" -f $version)
Copy-Item $apk $target -Force
Write-Host ('[Flyme 版] 2/5 产物：{0}（{1:N1} MB）' -f $target, ((Get-Item $target).Length / 1MB))

if ($SkipInstall) {
    Write-Host '[Flyme 版] 指定了 -SkipInstall，跳过安装'
    exit 0
}

Write-Host ('[Flyme 版] 3/5 安装到真机 {0} ...' -f $Device)
$devices = (& adb devices) -join "`n"
if ($devices -notmatch [regex]::Escape($Device)) {
    throw "没连上真机 $Device，插好 USB 后 adb devices 确认一下"
}

# 国产 ROM（Flyme/MIUI…）默认挡 adb 安装：INSTALL_FAILED_USER_RESTRICTED。
# 临时把安装器限制关掉，装完立刻恢复原值，不影响手机其它安全设置。
$old = (& adb -s $Device shell 'settings get global flyme_installer_settings').Trim()
& adb -s $Device shell 'settings put global flyme_installer_settings 0' | Out-Null
try {
    & adb -s $Device install -r $target
    if ($LASTEXITCODE -ne 0) { throw '安装失败，看上面的 adb 报错' }
} finally {
    $restore = if ([string]::IsNullOrWhiteSpace($old) -or $old -eq 'null') { '1' } else { $old }
    & adb -s $Device shell "settings put global flyme_installer_settings $restore" | Out-Null
}

Write-Host '[Flyme 版] 4/5 授权 + 拉起 App（首启自动建 /storage/emulated/0/AI角色扮演/模型 目录）...'
& adb -s $Device shell "appops set $pkg MANAGE_EXTERNAL_STORAGE allow" | Out-Null
# 模型目录由 App 首启自动创建（attach() 里 mkdir），这里装完顺手拉起一次 App 触发它。
# （不直接用 adb 传中文路径 mkdir：Windows PowerShell 5.1 给 adb 传中文参数会 GBK 串味）

# ---- 5/5 冒烟测试：截图存 /storage/emulated/0/AI角色扮演/shot-<ts>/（App 目录内），拉回 dist 后清设备侧 ----
if ($SkipTest) {
    Write-Host '[Flyme 版] 指定了 -SkipTest，跳过冒烟测试'
} else {
    & (Join-Path $PSScriptRoot 'screenshot-test.ps1') -Target flyme -Package $pkg -Apk $target -DistDir $dist -Device $Device
}

Write-Host ''
Write-Host '[Flyme 版] 完成 ✅'
Write-Host '  模型目录：/storage/emulated/0/AI角色扮演/模型/'
Write-Host ("  推模型示例：adb -s {0} push 本地.gguf ""/storage/emulated/0/AI角色扮演/模型/""" -f $Device)
