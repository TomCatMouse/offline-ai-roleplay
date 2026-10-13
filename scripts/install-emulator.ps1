<#
  把「当前版本的默认包 + 真机包」同步安装到模拟器（MuMu / emulator-5554）。

  用户要求（2026-10-13）：版本更新后，将最新的真机包、默认包同步安装模拟器。

  与两个打包脚本的关系：
    默认包（MuMu 版）  = scripts\build-default.ps1 → dist\MuMu版\AI角色扮演-MuMu版-v<ver>.apk
                         包名 com.example.roleplay      （不含引擎，连 PC 上的 llama-server）
    真机包（phys 正式版）= scripts\pack-release.ps1 → dist\正式版\AI角色扮演_<ver>_phys_release.apk
                         包名 com.example.roleplay.phys （内置骁龙 arm64 引擎）
  两者 applicationId 不同，可在同一台设备上共存，数据各自独立。

  本脚本做的事：
    1) 从 app\build.gradle.kts 读当前版本，定位上面两个 APK（-Build 可先自动打包）
    2) 选目标设备：默认优先挑 emulator-*；可用 -Device 或环境变量 ANDROID_SERIAL 指定
    3) 逐个 adb install -r（覆盖升级，不卸载、不动应用数据）；签名不匹配时给出明确提示，
       绝不擅自卸载（卸载会清空 App 内的角色与对话数据）
    4) 逐个拉起 + 截图（截图拉回 dist\模拟器截图\<时间戳>\，设备侧临时文件自动清理）
    5) 打印两者安装后的 versionName / versionCode

  用法：
    powershell -File scripts\install-emulator.ps1
    powershell -File scripts\install-emulator.ps1 -Device emulator-5554
    powershell -File scripts\install-emulator.ps1 -Build              # 先打包再同步
    powershell -File scripts\install-emulator.ps1 -SkipScreenshot

  ⚠️ 本文件含中文，必须以带 UTF-8 BOM 的形式保存。Windows PowerShell 5.1 会把无 BOM 的
     UTF-8 当成 GBK 读，中文变乱码后会直接抛 ParserError（build-default.ps1 /
     build-phys.ps1 / pack-release.ps1 在 2026-10-13 都踩过这个坑，本脚本同样适用）。
#>
param(
    [string]$Device = '',
    [switch]$Build,
    [switch]$SkipScreenshot
)

$ErrorActionPreference = 'Stop'
# --- adb 调用统一入口 -------------------------------------------------------
# 原生命令（adb）经常把诊断信息写到 stderr。PowerShell 5.1 在
# $ErrorActionPreference = 'Stop' 下会把这种 stderr 变成【终止错误】，
# 即使写 2>&1 也拦不住（2026-10-13 monkey 那一步就是这么挂的）。
# 所以所有 adb 调用都走这里：临时放宽偏好 + 合并输出 + 由调用方决定收或丢。
function Invoke-Capture {
    param([scriptblock]$Block)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return (& $Block 2>&1 | Out-String) } finally { $ErrorActionPreference = $old }
}
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$gradlePath = Join-Path $root 'app\build.gradle.kts'
$gradle = [System.IO.File]::ReadAllText($gradlePath, [System.Text.Encoding]::UTF8)
$version = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if ([string]::IsNullOrWhiteSpace($version)) { throw '无法从 app\build.gradle.kts 解析出版本号' }

# 中文路径不能用字符串字面量拼（脚本自身编码风险），用码点构造，双保险：
#   正式版 = U+6B63 U+5F0F U+7248      AI角色扮演 = "AI" + U+89D2 U+8272 U+626E U+6F14
$dirZhengshi = [string]::new([char[]]@(0x6B63, 0x5F0F, 0x7248))
$appName = 'AI' + [string]::new([char[]]@(0x89D2, 0x8272, 0x626E, 0x6F14))
$dirShots = [string]::new([char[]]@(0x6A21, 0x62DF, 0x5668, 0x622A, 0x56FE))   # 模拟器截图

$targets = @(
    [pscustomobject]@{
        Label = '默认包(MuMu版)'
        Pkg   = 'com.example.roleplay'
        Apk   = Join-Path $root ("dist\MuMu版\" + $appName + "-MuMu版-v" + $version + ".apk")
        Tag   = 'default'
    },
    [pscustomobject]@{
        Label = '真机包(phys正式版)'
        Pkg   = 'com.example.roleplay.phys'
        Apk   = Join-Path $root ("dist\" + $dirZhengshi + "\" + $appName + "_" + $version + "_phys_release.apk")
        Tag   = 'phys'
    }
)

Write-Host ('=== 同步安装到模拟器：版本 ' + $version + ' (versionCode ' + $versionCode + ') ===') -ForegroundColor Cyan

if ($Build) {
    Write-Host '[0/4] 先打包（默认包 + 真机包正式版）...'
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    & $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-default.ps1') -SkipTest
    & $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'pack-release.ps1')
}

Write-Host '[1/4] 检查两个 APK 是否就绪...'
$missing = 0
foreach ($t in $targets) {
    if (Test-Path -LiteralPath $t.Apk) {
        $mb = [math]::Round((Get-Item -LiteralPath $t.Apk).Length / 1MB, 2)
        Write-Host ('  OK  ' + $t.Label.PadRight(20) + $mb.ToString().PadLeft(7) + ' MB   ' + (Split-Path $t.Apk -Leaf))
    } else {
        $missing++
        Write-Host ('  缺  ' + $t.Label.PadRight(20) + ' 未找到 ' + $t.Apk) -ForegroundColor Red
    }
}
if ($missing -gt 0) { throw '有 APK 未产出：先跑 build-default.ps1 与 pack-release.ps1（或用 -Build）' }

Write-Host '[2/4] 选目标设备...'
# adb start-server 的 "daemon not running / starting now" 走 stderr，在 Stop 模式下会变成
# 终止错误，所以统一用「赋值 + 2>&1 + Out-String」吞掉它。
$null = Invoke-Capture { & adb start-server }
if ([string]::IsNullOrWhiteSpace($Device)) {
    if ($env:ANDROID_SERIAL) {
        $Device = $env:ANDROID_SERIAL
    } else {
        $all = @()
        $devOut = Invoke-Capture { & adb devices }
        foreach ($line in ($devOut -split "`r?`n")) {
            $t = $line.Trim()
            if ($t -match '^(\S+)\s+device$') { $all += $Matches[1] }
        }
        $emus = @($all | Where-Object { $_ -match '^emulator-' })
        if ($emus.Count -ge 1) { $Device = $emus[0] }
        elseif ($all.Count -eq 1) { $Device = $all[0] }
        else { throw ('没找到可用设备（在线：' + (($all -join ', ') -replace '^$', '无') + '），请用 -Device 指定') }
    }
}
Write-Host ('  目标设备：' + $Device)

Write-Host '[3/4] 逐个安装（-r 覆盖升级，不卸载、不动数据）...'
# ASCII 临时名：adb 对含中文的本地路径不稳
$tmpDir = 'D:\harnessspace\tests'
if (!(Test-Path $tmpDir)) { New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null }
$results = @()
foreach ($t in $targets) {
    $tmp = Join-Path $tmpDir ('sync-' + $t.Tag + '-' + $version + '.apk')
    Copy-Item -LiteralPath $t.Apk -Destination $tmp -Force
    Write-Host ('  --- ' + $t.Label + '  ->  ' + $t.Pkg)
    $out = Invoke-Capture { & adb -s $Device install -r $tmp }
    $out.Trim() -split "`r?`n" | Where-Object { $_.Trim() } | ForEach-Object { Write-Host ('      ' + $_.Trim()) }
    Remove-Item -LiteralPath $tmp -Force
    $ok = ($out -match 'Success')
    if (-not $ok -and ($out -match 'UPDATE_INCOMPATIBLE|signatures do not match')) {
        Write-Host '      ⚠ 签名不匹配（旧包与新包不是同一把钥匙签的）。' -ForegroundColor Yellow
        Write-Host ('        需要先卸载旧包（会清空 App 内角色与对话，模型目录不受影响）：') -ForegroundColor Yellow
        Write-Host ('        adb -s ' + $Device + ' uninstall ' + $t.Pkg) -ForegroundColor Yellow
    }
    $results += [pscustomobject]@{ Label = $t.Label; Pkg = $t.Pkg; Ok = $ok }
}

Write-Host '[4/4] 核对版本 + 截图...'
$shotRoot = Join-Path $root ('dist\' + $dirShots + '\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
foreach ($t in $targets) {
    $d = Invoke-Capture { & adb -s $Device shell ('dumpsys package ' + $t.Pkg) }
    $vn = [regex]::Match($d, 'versionName=([^\s]+)').Groups[1].Value
    $vc = [regex]::Match($d, 'versionCode=(\d+)').Groups[1].Value
    Write-Host ('  ' + $t.Pkg.PadRight(28) + 'versionName=' + $vn + '  versionCode=' + $vc)
    if ($SkipScreenshot) { continue }
    # monkey / screencap / pull / rm 都可能往 stderr 写东西（monkey 会打 args: 行），
    # Stop 模式下那就是终止错误 —— 一律用「赋值 + 2>&1 + Out-String」吞掉。
    $null = Invoke-Capture { & adb -s $Device shell ('monkey -p ' + $t.Pkg + ' -c android.intent.category.LAUNCHER 1') }
    Start-Sleep -Seconds 9
    if (!(Test-Path $shotRoot)) { New-Item -ItemType Directory -Force -Path $shotRoot | Out-Null }
    $remote = '/sdcard/sync-' + $t.Tag + '.png'
    $null = Invoke-Capture { & adb -s $Device shell ('screencap -p ' + $remote) }
    Start-Sleep -Seconds 2
    $null = Invoke-Capture { & adb -s $Device pull $remote (Join-Path $shotRoot ($t.Tag + '.png')) }
    $null = Invoke-Capture { & adb -s $Device shell ('rm -f ' + $remote) }
    Write-Host ('      截图 -> ' + (Join-Path $shotRoot ($t.Tag + '.png')))
}

$failed = @($results | Where-Object { -not $_.Ok })
Write-Host ''
if ($failed.Count -eq 0) {
    Write-Host ('完成 ✅  两个包均已安装并拉起（版本 ' + $version + '）') -ForegroundColor Green
} else {
    Write-Host ('完成，但有 ' + $failed.Count + ' 个包未装成功：' + (($failed | ForEach-Object { $_.Pkg }) -join ', ')) -ForegroundColor Yellow
    exit 1
}
