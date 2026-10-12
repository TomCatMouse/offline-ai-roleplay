# -*- coding: utf-8 -*-
# 严格版角色质量复检：对 model_benchmark.ps1 产出的 quality_*.json 重新审计。
#
# 为什么需要它：初版检测器只查 OOC/繁简/英文词等，漏掉了
#   "对着镜头""手机屏幕""发送祝福信号" 这类严重出戏描写，
#   导致 Gemma-3-1B 只被判 1 处问题，与实际观感（8/19 轮不合格）严重不符。
# 本脚本补充了出戏词、Markdown 残留、回复长度失控三类规则。
#
# 用法：.\strict_quality_audit.ps1
Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ev = Join-Path (Split-Path -Parent $PSScriptRoot) "docs\evidence"
$files = Get-ChildItem $ev -Filter "quality_*.json" | Sort-Object Name
if ($files.Count -eq 0) { Write-Host "找不到 quality_*.json，请先跑 model_benchmark.ps1"; exit 1 }

# ---- 检测规则 ----
$META = @("镜头", "摄像机", "观众", "弹幕", "直播间", "玩家", "祝福信号", "系统提示",
          "对话记录", "数据库", "程序", "手机屏幕", "看着手机", "拿着手机", "看着屏幕", "对着屏幕",
          "翻开手机", "屏幕上的")
$MODERN = @("么么哒", "yyds", "绝绝子", "集美", "破防")
$VULGAR = @("老子", "本大爷", "老娘", "大爷我")
$TRAD = "會為這裡們後時樣說還來個對從開關發現實點與體過頭見嗎讓覺聽誰幾機屬業隊動勢進遠邊勝樂觀規計記認識談請講論議試課調變專傳僅師帶幫歸島傷慘態戀懼圖圍園圓場塊壞壓豐龍鳳鶴鷹適遲選遺遞著丟趕嘆喚囑讚譽於"
$FOREIGN = @([char]0x060C, [char]0x066C, [char]0x061B, [char]0x061F, [char]0x104B, [char]0x104A)

function Get-Sentences([string]$t) {
    return [regex]::Split($t, '[。！？!?\n]') | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -ge 8 }
}

$summary = @()
$detail = @()

foreach ($f in $files) {
    $model = $f.BaseName -replace '^quality_', ''
    $turns = Get-Content -Raw -Encoding UTF8 $f.FullName | ConvertFrom-Json

    $metaHits = 0; $mdHits = 0; $enHits = 0; $tradHits = 0; $punctHits = 0
    $longHits = 0; $shortHits = 0; $vulgarHits = 0; $modernHits = 0; $dupSents = 0
    $lens = @(); $badTurns = 0; $n = 0
    $allSents = @()

    foreach ($t in $turns) {
        $r = [string]$t.Reply
        if (-not $r -or $r -eq "ERROR") { continue }
        $n++
        $lens += $r.Length
        $bad = 0

        foreach ($w in $META) { if ($r -like "*$w*") { $metaHits++; $bad++; $detail += "[$model] 出戏词:$w" } }
        if ($r -match '\*\*|^\s*#|^\s*---') { $mdHits++; $bad++ }
        $en = [regex]::Matches($r, '(?<![A-Za-z0-9])[A-Za-z]{3,}(?![A-Za-z0-9])') | ForEach-Object { $_.Value }
        if ($en.Count -gt 0) { $enHits++; $bad++ }
        $tc = ($r.ToCharArray() | Where-Object { $TRAD.Contains($_) }).Count
        if ($tc -ge 2) { $tradHits++; $bad++ }
        $fc = ($r.ToCharArray() | Where-Object { $FOREIGN -contains $_ }).Count
        if ($fc -ge 1) { $punctHits++; $bad++ }
        if ($r.Length -gt 350) { $longHits++; $bad++ }
        if ($r.Length -lt 12) { $shortHits++; $bad++ }
        foreach ($w in $VULGAR) { if ($r -like "*$w*") { $vulgarHits++; $bad++ } }
        foreach ($w in $MODERN) { if ($r -like "*$w*") { $modernHits++; $bad++ } }

        # 跨轮整句复用
        $mine = Get-Sentences $r
        foreach ($s in $mine) { if ($allSents -contains $s) { $dupSents++; $bad++; $detail += "[$model] 复用整句:$s" } }
        $allSents += $mine

        if ($bad -gt 0) { $badTurns++ }
    }

    $avgLen = 0
    if ($lens.Count -gt 0) { $avgLen = [math]::Round(($lens | Measure-Object -Average).Average, 0) }

    $summary += [pscustomobject]@{
        Model      = $model.Substring(0, [math]::Min(34, $model.Length))
        出戏词     = $metaHits
        Markdown   = $mdHits
        英文词     = $enHits
        繁体漂移   = $tradHits
        外文标点   = $punctHits
        超长回复   = $longHits
        跨轮复用   = $dupSents
        平均字数   = $avgLen
        有问题轮次 = "$badTurns/$n"
    }
}

Write-Host "`n================ 严格复检结果（19 轮/模型）================`n" -ForegroundColor Cyan
$summary | Format-Table -AutoSize
Write-Host "`n---- 明细（最多 25 条）----" -ForegroundColor Yellow
$detail | Select-Object -First 25 | ForEach-Object { "  $_" }

# 落盘证据
$out = [pscustomobject]@{
    GeneratedAt = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    TurnsPerModel = 19
    Rules = @("出戏词", "Markdown残留", "英文词泄漏", "繁体漂移", "外文标点", "超长回复>350字", "超短回复<12字", "跨轮整句复用", "粗俗自称", "现代网络语")
    Models = $summary
    Detail = $detail
}
$jsonPath = Join-Path $ev "strict_audit.json"
$out | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8
Write-Host "`n结果已存: docs\evidence\strict_audit.json" -ForegroundColor DarkGray
