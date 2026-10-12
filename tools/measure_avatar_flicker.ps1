# -*- coding: utf-8 -*-
<#
.SYNOPSIS
    客观测量「滚动时头像闪烁」

.DESCRIPTION
    做法：
      1. 打开目标界面（角色列表 / 聊天页）
      2. adb screenrecord 录屏
      3. 录屏期间做【小幅来回滑动】—— 让同一批卡片始终留在屏幕上，
         这样"每帧肤色像素数"应当基本恒定，出现骤降即说明头像闪了
      4. ffmpeg 抽帧 -> Python 逐帧统计肤色像素 -> 输出判定

    为什么用小幅来回滑动而不是一路滚到底：
      一路滚动时卡片不断进出画面，肤色像素数本来就会大幅变化，无法区分
      "正常进出"和"头像闪烁"。小幅振荡能把变量控制住。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\measure_avatar_flicker.ps1 -Screen list -Label "列表-修复前"
    powershell -ExecutionPolicy Bypass -File tools\measure_avatar_flicker.ps1 -Screen chat -Label "聊天-修复前"
#>
param(
    [string]$Adb    = "D:\MuMu\nx_main\adb.exe",
    [string]$Ffmpeg = "D:\ffmpeg\bin\ffmpeg.exe",
    [string]$Python = "D:\python\python.exe",
    [ValidateSet("list", "chat")] [string]$Screen = "list",
    [string]$Label  = "",
    [int]$Seconds   = 10,
    [int]$Fps       = 30,
    [int]$CropTop   = -1,     # -1 = 按界面自动选
    [switch]$SkipLaunch,
    [switch]$KeepFrames
)

$ErrorActionPreference = "Continue"
$workDir  = Join-Path $env:TEMP "avatar_flicker"
$frameDir = Join-Path $workDir "frames"
if (Test-Path $workDir) { Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $frameDir | Out-Null

function Get-Nodes {
    & $Adb shell "uiautomator dump /storage/emulated/0/mf.xml" 2>&1 | Out-Null
    $xml = (& $Adb shell "cat /storage/emulated/0/mf.xml" 2>&1) -join ""
    $out = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $seg = $m.Value
        $out += [pscustomobject]@{
            Text  = [regex]::Match($seg, 'text="([^"]*)"').Groups[1].Value
            Class = [regex]::Match($seg, 'class="([^"]*)"').Groups[1].Value
            Click = [regex]::Match($seg, 'clickable="([^"]*)"').Groups[1].Value
            X1 = [int]$m.Groups[1].Value; Y1 = [int]$m.Groups[2].Value
            X2 = [int]$m.Groups[3].Value; Y2 = [int]$m.Groups[4].Value
        }
    }
    return $out
}

# ---------- 1. 打开目标界面 ----------
$swipeTop = 950; $swipeBottom = 770
if (-not $SkipLaunch) {
    & $Adb shell "settings delete system font_scale" | Out-Null
    & $Adb shell "wm size reset" | Out-Null
    & $Adb shell "am force-stop com.example.roleplay" | Out-Null
    Start-Sleep -Seconds 1
    & $Adb shell "monkey -p com.example.roleplay -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
    Start-Sleep -Seconds 11
    Write-Host "[1/4] 已打开应用"

    if ($Screen -eq "chat") {
        $opened = $false
        for ($a = 1; $a -le 4 -and -not $opened; $a++) {
            $nodes = Get-Nodes
            $card = $nodes | Where-Object {
                $_.Click -eq "true" -and ($_.X2 - $_.X1) -ge 250 -and ($_.X2 - $_.X1) -le 400 -and ($_.Y2 - $_.Y1) -ge 100 -and $_.Y1 -gt 520
            } | Select-Object -First 1
            if (-not $card) { Start-Sleep -Seconds 2; continue }
            $cx = [int](($card.X1 + $card.X2) / 2); $cy = [int](($card.Y1 + $card.Y2) / 2)
            & $Adb shell "input tap $cx $cy" | Out-Null
            Start-Sleep -Seconds 7
            if ((Get-Nodes) | Where-Object { $_.Text -like "*输入消息*" }) { $opened = $true }
            Write-Host "      第 $a 轮点击角色卡 ($cx,$cy) -> $opened"
        }
        if (-not $opened) { Write-Host "❌ 没能进入聊天页"; exit 1 }
        Write-Host "[1/4] 已进入聊天页（消息区来回滑动）"
        $swipeTop = 900; $swipeBottom = 560
    } else {
        Write-Host "[1/4] 角色列表（网格区来回滑动）"
    }
} else {
    Write-Host "[1/4] 跳过启动（沿用当前界面）"
}

if ($CropTop -lt 0) { $CropTop = if ($Screen -eq "chat") { 235 } else { 550 } }

# ---------- 2. 后台录屏 ----------
& $Adb shell "rm -f /storage/emulated/0/flick.mp4" | Out-Null
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Adb
$psi.Arguments = "shell screenrecord --bit-rate 8000000 --time-limit $Seconds /storage/emulated/0/flick.mp4"
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$rec = [System.Diagnostics.Process]::Start($psi)
Start-Sleep -Seconds 2
Write-Host "[2/4] 已开始录屏（$Seconds 秒）"

# ---------- 3. 小幅来回滑动 ----------
# 幅度小、时长 600ms：不触发惯性滑动，内容一直留在屏幕上
$rounds = [int](($Seconds - 3) * 1000 / 1100)
for ($i = 0; $i -lt $rounds; $i++) {
    if ($i % 2 -eq 0) { & $Adb shell "input swipe 360 $swipeTop 360 $swipeBottom 600" | Out-Null }
    else              { & $Adb shell "input swipe 360 $swipeBottom 360 $swipeTop 600" | Out-Null }
    Start-Sleep -Milliseconds 500
}
Write-Host "[2/4] 滑动完成（$rounds 次往返）"

$rec.WaitForExit(($Seconds + 15) * 1000) | Out-Null
if (-not $rec.HasExited) { $rec.Kill() }
Start-Sleep -Seconds 1

# ---------- 4. 抽帧 + 分析 ----------
& $Adb pull /storage/emulated/0/flick.mp4 (Join-Path $workDir "flick.mp4") 2>&1 | Out-Null
$mp4 = Join-Path $workDir "flick.mp4"
if (-not (Test-Path $mp4)) { Write-Host "❌ 录屏文件没拉到"; exit 1 }
Write-Host "[3/4] 录屏大小: $([math]::Round((Get-Item $mp4).Length/1KB,1)) KB"

& $Ffmpeg -y -loglevel error -i $mp4 -vf "fps=$Fps" (Join-Path $frameDir "%05d.png") 2>&1 | Out-Null
$frameCount = (Get-ChildItem $frameDir -Filter "*.png").Count
Write-Host "[3/4] 抽出 $frameCount 帧（crop-top=$CropTop）"

$env:PYTHONIOENCODING = "utf-8"
& $Python (Join-Path $PSScriptRoot "avatar_flicker_analysis.py") $frameDir --crop-top $CropTop --label $Label 2>&1

Write-Host "[4/4] 录屏: $mp4"
if (-not $KeepFrames) {
    Remove-Item $frameDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "      （帧序列已清理）"
}
