# -*- coding: utf-8 -*-
# 临时验证脚本：分析模拟器截图里每个角色头像的发色/瞳色/脸部尺寸
# 目的：在没有图像识别能力的情况下，客观确认 Q 版头像确实按角色区分渲染
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"

$ev = "$PSScriptRoot\..\docs\evidence"

function RgbToHex($p) { return ('#{0:X2}{1:X2}{2:X2}' -f $p.R, $p.G, $p.B) }

function Get-TopColors($bmp, $x1, $y1, $x2, $y2, $step, $filter) {
    $h = @{}
    for ($y = $y1; $y -le $y2; $y += $step) {
        for ($x = $x1; $x -le $x2; $x += $step) {
            $p = $bmp.GetPixel($x, $y)
            if (-not (& $filter $p)) { continue }
            $k = "$($p.R),$($p.G),$($p.B)"
            if ($h.ContainsKey($k)) { $h[$k]++ } else { $h[$k] = 1 }
        }
    }
    return $h.GetEnumerator() | Sort-Object Value -Descending
}

function Analyze-Shot($file, $cards) {
    if (-not (Test-Path $file)) { Write-Host "找不到截图: $file"; return }
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList $file
    if (-not $bmp) { throw "Bitmap 构造失败: $file" }
    Write-Host "`n===== $([System.IO.Path]::GetFileName($file))  ($($bmp.Width)x$($bmp.Height)) ====="

    foreach ($c in $cards) {
        $name = $c[0]; $cardX = $c[1]; $cardY = $c[2]
        # 头像在卡片左上：14dp 内边距 + 60dp 头像框，密度 1.75 → 约 24..135 px
        $rx1 = $cardX + 20; $rx2 = $cardX + 140
        $ry1 = $cardY + 20; $ry2 = $cardY + 170

        # 1) 找肤色像素，定位脸
        $skin = Get-TopColors $bmp $rx1 $ry1 $rx2 $ry2 2 {
            param($p) ([math]::Pow($p.R-255,2) + [math]::Pow($p.G-230,2) + [math]::Pow($p.B-213,2)) -lt 2600
        }
        $skinCount = 0; foreach ($e in $skin) { $skinCount += $e.Value }
        if ($skinCount -lt 20) {
            Write-Host ("  {0,-8} ❌ 头像区未找到肤色（{1} 点）" -f $name, $skinCount)
            continue
        }

        # 2) 重新扫描记录脸的包围盒
        $fx1 = 9999; $fx2 = 0; $fy1 = 9999; $fy2 = 0; $n = 0
        for ($y = $ry1; $y -le $ry2; $y += 1) {
            for ($x = $rx1; $x -le $rx2; $x += 1) {
                $p = $bmp.GetPixel($x, $y)
                if (([math]::Pow($p.R-255,2) + [math]::Pow($p.G-230,2) + [math]::Pow($p.B-213,2)) -lt 2600) {
                    $n++
                    if ($x -lt $fx1) { $fx1 = $x }; if ($x -gt $fx2) { $fx2 = $x }
                    if ($y -lt $fy1) { $fy1 = $y }; if ($y -gt $fy2) { $fy2 = $y }
                }
            }
        }

        # 3) 发色 = 脸上方带内出现最多的颜色（排除暗背景）
        $hair = Get-TopColors $bmp $fx1 $([math]::Max($ry1, $fy1-26)) $fx2 ($fy1+3) 1 {
            param($p) ($p.R + $p.G + $p.B) -gt 210
        }
        $hairTop = $hair | Select-Object -First 2

        # 4) 瞳色 = 脸内高饱和色
        $eye = Get-TopColors $bmp $fx1 $fy1 $fx2 $fy2 1 {
            param($p)
            $mx = [math]::Max($p.R, [math]::Max($p.G, $p.B))
            $mn = [math]::Min($p.R, [math]::Min($p.G, $p.B))
            (($mx - $mn) -gt 50) -and ($mx -gt 95)
        }
        $eyeTop = $eye | Select-Object -First 1

        # 5) 瞳孔（近黑）存在性
        $dark = Get-TopColors $bmp $fx1 $fy1 $fx2 $fy2 1 { param($p) ($p.R+$p.G+$p.B) -lt 190 }
        $darkCount = 0; foreach ($e in $dark) { $darkCount += $e.Value }

        $hairStr = ($hairTop | ForEach-Object {
            $rgb = $_.Name -split ','
            $col = [System.Drawing.Color]::FromArgb([int]$rgb[0], [int]$rgb[1], [int]$rgb[2])
            "$(RgbToHex $col)($($_.Value))"
        }) -join " "
        $eyeStr = "无"
        if ($eyeTop) {
            $rgb = $eyeTop.Name -split ','
            $col = [System.Drawing.Color]::FromArgb([int]$rgb[0], [int]$rgb[1], [int]$rgb[2])
            $eyeStr = "$(RgbToHex $col)($($eyeTop.Value))"
        }

        Write-Host ("  {0,-8} 脸 {1}x{2}px 位置({3},{4}) | 发色 {5} | 瞳色 {6} | 瞳孔点 {7}" -f `
            $name, ($fx2-$fx1+1), ($fy2-$fy1+1), $fx1, $fy1, $hairStr, $eyeStr, $darkCount)
    }
    $bmp.Dispose()
}

Analyze-Shot "$ev\screenshot_avatars2.png" @(
    @("墨尘", 28, 594), @("星野铃", 372, 594), @("沈清秋", 28, 935), @("林小夏", 372, 935)
)
