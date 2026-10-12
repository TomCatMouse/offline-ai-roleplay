# -*- coding: utf-8 -*-
<#
.SYNOPSIS
    用 dumpsys gfxinfo 客观度量滚动时的渲染负担 / 卡顿。

.DESCRIPTION
    gfxinfo 是 Android 官方的渲染统计，能给出：
      - 总帧数 / Janky 帧数 / 卡顿率
      - 50th / 90th / 95th / 99th 分位帧耗时
      - Missed Vsync（错过垂直同步）、Slow UI thread、Slow draw
    对比"修复前 / 修复后"可以量化优化效果。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\measure_scroll_jank.ps1 -Screen chat -Label "聊天-修复前"
#>
param(
    [string]$Adb = "D:\MuMu\nx_main\adb.exe",
    [ValidateSet("list", "chat")] [string]$Screen = "chat",
    [string]$Label = "",
    [int]$Scrolls = 12,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Continue"
$pkg = "com.example.roleplay"

function Get-Node([string]$attr, [string]$value) {
    & $Adb shell "uiautomator dump /storage/emulated/0/jk.xml" 2>&1 | Out-Null
    $xml = (& $Adb shell "cat /storage/emulated/0/jk.xml" 2>&1) -join ""
    $out = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $seg = $m.Value
        $out += [pscustomobject]@{
            Text = [regex]::Match($seg, 'text="([^"]*)"').Groups[1].Value
            Desc = [regex]::Match($seg, 'content-desc="([^"]*)"').Groups[1].Value
            Click = [regex]::Match($seg, 'clickable="([^"]*)"').Groups[1].Value
            X1 = [int]$m.Groups[1].Value; Y1 = [int]$m.Groups[2].Value
            X2 = [int]$m.Groups[3].Value; Y2 = [int]$m.Groups[4].Value
        }
    }
    return $out
}

if (-not $SkipLaunch) {
    & $Adb shell "settings delete system font_scale" | Out-Null
    & $Adb shell "wm size reset" | Out-Null
    & $Adb shell "am force-stop $pkg" | Out-Null
    Start-Sleep -Seconds 1
    & $Adb shell "monkey -p $pkg -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
    Start-Sleep -Seconds 11

    if ($Screen -eq "chat") {
        $ok = $false
        for ($a = 1; $a -le 4 -and -not $ok; $a++) {
            $nodes = Get-Node
            $card = $nodes | Where-Object {
                $_.Click -eq "true" -and ($_.X2 - $_.X1) -ge 250 -and ($_.X2 - $_.X1) -le 400 -and ($_.Y2 - $_.Y1) -ge 100 -and $_.Y1 -gt 520
            } | Select-Object -First 1
            if (-not $card) { Start-Sleep -Seconds 2; continue }
            & $Adb shell "input tap $([int](($card.X1+$card.X2)/2)) $([int](($card.Y1+$card.Y2)/2))" | Out-Null
            Start-Sleep -Seconds 7
            if ((Get-Node) | Where-Object { $_.Text -like "*输入消息*" }) { $ok = $true }
        }
        if (-not $ok) { Write-Host "❌ 没能进入聊天页"; exit 1 }
        $y1 = 900; $y2 = 620
        Write-Host "已进入聊天页"
    } else {
        $y1 = 950; $y2 = 700
        Write-Host "已在角色列表"
    }
} else {
    $y1 = 900; $y2 = 620
    Write-Host "跳过启动"
}

# ---------- 重置统计 ----------
& $Adb shell "dumpsys gfxinfo $pkg reset" | Out-Null
Start-Sleep -Milliseconds 500

# ---------- 来回滚动 ----------
for ($i = 0; $i -lt $Scrolls; $i++) {
    if ($i % 2 -eq 0) { & $Adb shell "input swipe 360 $y1 360 $y2 350" | Out-Null }
    else              { & $Adb shell "input swipe 360 $y2 360 $y1 350" | Out-Null }
    Start-Sleep -Milliseconds 350
}
Start-Sleep -Seconds 1

# ---------- 读取统计 ----------
$raw = (& $Adb shell "dumpsys gfxinfo $pkg" 2>&1) -join "`n"

function Pick([string]$pattern) {
    $m = [regex]::Match($raw, $pattern)
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
    return "?"
}

$total   = Pick 'Total frames rendered:\s*(\d+)'
$janky   = Pick 'Janky frames:\s*(\d+)'
$jankyP  = Pick 'Janky frames:\s*\d+\s*\(([\d.]+)%\)'
$p50     = Pick '50th percentile:\s*(\d+)ms'
$p90     = Pick '90th percentile:\s*(\d+)ms'
$p95     = Pick '95th percentile:\s*(\d+)ms'
$p99     = Pick '99th percentile:\s*(\d+)ms'
$missed  = Pick 'Number Missed Vsync:\s*(\d+)'
$slowUi  = Pick 'Number Slow UI thread:\s*(\d+)'
$slowDraw= Pick 'Number Slow draw:\s*(\d+)'
$slowBmp = Pick 'Number Slow bitmap uploads:\s*(\d+)'

Write-Host ""
Write-Host "======================================================================"
Write-Host "  $Label"
Write-Host "  界面: $(if($Screen -eq 'chat'){'聊天页'}else{'角色列表'})   往返滚动 $Scrolls 次"
Write-Host "======================================================================"
Write-Host "  总渲染帧数:        $total"
Write-Host "  Janky 帧:          $janky  ($jankyP%)"
Write-Host "  帧耗时 50th:       ${p50}ms"
Write-Host "  帧耗时 90th:       ${p90}ms    <- 主指标, 越小越流畅"
Write-Host "  帧耗时 95th:       ${p95}ms"
Write-Host "  帧耗时 99th:       ${p99}ms"
Write-Host "  Missed Vsync:      $missed"
Write-Host "  Slow UI thread:    $slowUi"
Write-Host "  Slow draw:         $slowDraw"
Write-Host "  Slow bitmap:       $slowBmp"
Write-Host "======================================================================"

if ($jankyP -ne "?") {
    $jp = [double]$jankyP
    if ($jp -lt 5)       { Write-Host "  判定: 流畅 (卡顿率 $jp%)" }
    elseif ($jp -lt 15)  { Write-Host "  判定: 轻度卡顿 (卡顿率 $jp%)" }
    elseif ($jp -lt 35)  { Write-Host "  判定: 明显卡顿 (卡顿率 $jp%)" }
    else                 { Write-Host "  判定: 严重卡顿 (卡顿率 $jp%)" }
    Write-Host "  JANK_PCT=$jp"
}
