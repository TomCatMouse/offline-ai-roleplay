# -*- coding: utf-8 -*-
<#
.SYNOPSIS
    生成 App 启动图标 PNG（多密度）

.DESCRIPTION
    res/mipmap-anydpi-v26/ 与 -v33/ 下的自适应图标（矢量）负责 Android 8.0+；
    这里的 PNG 是给不支持自适应图标的启动器兜底用的。

    绘制内容与 drawable/ic_launcher_foreground.xml + ic_launcher_background.xml
    保持一致（Q 版动漫少女脸 + 紫罗兰渐变 + 星芒），只是用 GDI+ 光栅化。
    设计坐标系固定为 108x108，与 VectorDrawable 的 viewport 相同，
    改设计时两边同步改即可。

    注意 XML 里用的二次贝塞尔（Q 指令）在 GDI+ 里没有直接对应，
    已按 c1 = P0 + 2/3(C-P0)、c2 = P2 + 2/3(C-P2) 转成三次贝塞尔。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\generate_launcher_png.ps1
#>
param(
    [string]$ResDir = (Join-Path (Split-Path -Parent $PSScriptRoot) "app\src\main\res")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

# 密度 → 边长（px）
$densities = @{
    "mipmap-mdpi"    = 48
    "mipmap-hdpi"    = 72
    "mipmap-xhdpi"   = 96
    "mipmap-xxhdpi"  = 144
    "mipmap-xxxhdpi" = 192
}

$DESIGN = 108.0   # 设计画布边长，与 vector viewport 一致

function New-Icon($size, $round) {
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

    $s = $size / $DESIGN
    # 设计坐标 → 像素
    function P([double]$v) { return [float]($v * $s) }
    function Rect([double]$x, [double]$y, [double]$w, [double]$h) {
        return New-Object System.Drawing.RectangleF((P $x), (P $y), (P $w), (P $h))
    }
    # 由圆心 + 半径构造外接矩形（画圆专用）
    function CRect([double]$cx, [double]$cy, [double]$r) {
        return Rect ($cx - $r) ($cy - $r) ($r * 2) ($r * 2)
    }
    # 椭圆：圆心 + 双半径
    function ERect([double]$cx, [double]$cy, [double]$rx, [double]$ry) {
        return Rect ($cx - $rx) ($cy - $ry) ($rx * 2) ($ry * 2)
    }
    function Col([string]$hex, [int]$alpha = 255) {
        $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
        return [System.Drawing.Color]::FromArgb($alpha, $c.R, $c.G, $c.B)
    }
    function Brush([string]$hex, [int]$alpha = 255) {
        return New-Object System.Drawing.SolidBrush((Col $hex $alpha))
    }
    # 三次贝塞尔路径：$segs 每项为 8 个设计坐标 (x0,y0, c1x,c1y, c2x,c2y, x1,y1)
    function Bez([array]$segs) {
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.StartFigure()
        foreach ($b in $segs) {
            $p.AddBezier((P $b[0]), (P $b[1]), (P $b[2]), (P $b[3]), (P $b[4]), (P $b[5]), (P $b[6]), (P $b[7]))
        }
        $p.CloseFigure()
        return $p
    }
    function Poly([array]$pts) {
        $arr = @()
        for ($i = 0; $i -lt $pts.Count; $i += 2) {
            $arr += New-Object System.Drawing.PointF((P $pts[$i]), (P $pts[$i + 1]))
        }
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.AddPolygon($arr)
        return $p
    }
    function Stroke([double]$w, [string]$hex, [int]$alpha = 255) {
        $pen = New-Object System.Drawing.Pen((Col $hex $alpha), (P $w))
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        return $pen
    }

    # ---- 圆角/圆形裁剪 ----
    $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
    if ($round) {
        $clip.AddEllipse(0, 0, $size, $size)
    } else {
        $r = $size * 0.21
        $d = $r * 2
        $clip.AddArc(0, 0, $d, $d, 180, 90)
        $clip.AddArc($size - $d, 0, $d, $d, 270, 90)
        $clip.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
        $clip.AddArc(0, $size - $d, $d, $d, 90, 90)
        $clip.CloseFigure()
    }
    $g.SetClip($clip)

    # ================= 背景 =================
    $bgRect = Rect 0 0 108 108
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $bgRect, (Col "#9B8AF5"), (Col "#35176B"), 48.0)
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend(3)
    $blend.Colors = @((Col "#9B8AF5"), (Col "#7048CC"), (Col "#35176B"))
    $blend.Positions = @(0.0, 0.5, 1.0)
    $bg.InterpolationColors = $blend
    $g.FillRectangle($bg, $bgRect)

    # 头部后方白色光晕（同心椭圆模拟径向渐变）
    for ($i = 14; $i -ge 1; $i--) {
        $rr = 44 * $i / 14
        $a = [int](7 + 23 * (1 - $i / 14.0))
        $b = Brush "#FFFFFF" $a
        $g.FillEllipse($b, (CRect 52 46 $rr))
        $b.Dispose()
    }
    # 右下暖粉光晕
    for ($i = 12; $i -ge 1; $i--) {
        $rr = 48 * $i / 12
        $a = [int](5 + 24 * (1 - $i / 12.0))
        $b = Brush "#FFC2E8" $a
        $g.FillEllipse($b, (CRect 90 92 $rr))
        $b.Dispose()
    }

    # ================= 1. 深色描边 =================
    $ol = Brush "#4A2A1A"
    $g.FillEllipse($ol, (CRect 54 54 26.2))
    $g.FillPath($ol, (Bez @(
        @(32, 50.5, 27, 59, 26, 69, 28, 79.5),
        @(28, 79.5, 32.5, 72, 34.5, 62, 34, 52)
    )))
    $g.FillPath($ol, (Bez @(
        @(76, 50.5, 81, 59, 82, 69, 80, 79.5),
        @(80, 79.5, 75.5, 72, 73.5, 62, 74, 52)
    )))
    $ol.Dispose()

    # ================= 2. 后发 =================
    $hb = Brush "#8A4E32"
    $g.FillEllipse($hb, (CRect 54 54 25))
    $hb.Dispose()
    $sl = Brush "#7A4228"
    $g.FillPath($sl, (Bez @(
        @(32, 52, 28, 60, 27, 69, 29, 78),
        @(29, 78, 33, 71, 34.5, 62, 34, 54)
    )))
    $g.FillPath($sl, (Bez @(
        @(76, 52, 80, 60, 81, 69, 79, 78),
        @(79, 78, 75, 71, 73.5, 62, 74, 54)
    )))
    $sl.Dispose()

    # ================= 3. 耳朵 =================
    $skEar = Brush "#FFE0CC"
    $g.FillEllipse($skEar, (CRect 34 63 3.6))
    $g.FillEllipse($skEar, (CRect 74 63 3.6))
    $skEar.Dispose()

    # ================= 4. 脸 =================
    $face = Brush "#FFEDDD"
    # 上沿 y=39 与刘海内缘(y=37.5)重叠一点，避免中间露出一道头发底色
    $g.FillEllipse($face, (ERect 54 58.5 20 19.5))
    $face.Dispose()

    # ================= 5. 刘海 =================
    $bangs = Bez @(
        @(33, 60, 33, 33, 42, 25.5, 54, 25.5),
        @(54, 25.5, 66, 25.5, 75, 33, 75, 60),
        @(75, 60, 71, 44, 64, 37.5, 54, 37.5),
        @(54, 37.5, 44, 37.5, 37, 44, 33, 60)
    )
    $g.FillPath((Brush "#B87A5C"), $bangs)

    # 天使环：横跨刘海的一道柔光（Q 指令已转三次贝塞尔）
    $ring = Stroke 2.8 "#FFEAD8" 102
    $rp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $rp.AddBezier((P 40), (P 41.5), (P 49.33), (P 34.17), (P 58.67), (P 34.17), (P 68), (P 41.5))
    $g.DrawPath($ring, $rp)
    $ring.Dispose()

    # ================= 6. 眼睛 =================
    $eyeDark = Brush "#35242E"
    $g.FillEllipse($eyeDark, (ERect 46.5 61 6.4 7.9))
    $g.FillEllipse($eyeDark, (ERect 61.5 61 6.4 7.9))
    $eyeDark.Dispose()

    $eyeWhite = Brush "#FFFDFB"
    $g.FillEllipse($eyeWhite, (ERect 46.5 60.4 5.6 7))
    $g.FillEllipse($eyeWhite, (ERect 61.5 60.4 5.6 7))
    $eyeWhite.Dispose()

    $iris = Brush "#8A5CFF"
    $g.FillEllipse($iris, (ERect 46.5 61.4 4.6 5.8))
    $g.FillEllipse($iris, (ERect 61.5 61.4 4.6 5.8))
    $iris.Dispose()

    $irisLow = Brush "#6B3FD6"
    $g.FillEllipse($irisLow, (ERect 46.5 63.2 3.9 4.6))
    $g.FillEllipse($irisLow, (ERect 61.5 63.2 3.9 4.6))
    $irisLow.Dispose()

    $pupil = Brush "#241C2A"
    $g.FillEllipse($pupil, (ERect 46.5 61.6 2.1 3))
    $g.FillEllipse($pupil, (ERect 61.5 61.6 2.1 3))
    $pupil.Dispose()

    $hi = Brush "#FFFFFF"
    $g.FillEllipse($hi, (CRect 44.4 56.6 2.3))
    $g.FillEllipse($hi, (CRect 59.4 56.6 2.3))
    $hi.Dispose()
    $hi2 = Brush "#FFFFFF" 179
    $g.FillEllipse($hi2, (CRect 48.7 64.4 1.1))
    $g.FillEllipse($hi2, (CRect 63.7 64.4 1.1))
    $hi2.Dispose()

    # ================= 7. 眉毛 =================
    $brow = Stroke 1.7 "#9C6A4E"
    $bp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $bp.AddBezier((P 41.5), (P 49.5), (P 44.83), (P 47.83), (P 48.17), (P 47.83), (P 51.5), (P 49.5))
    $bp.AddBezier((P 56.5), (P 49.5), (P 59.83), (P 47.83), (P 63.17), (P 47.83), (P 66.5), (P 49.5))
    $g.DrawPath($brow, $bp)
    $brow.Dispose()

    # ================= 8. 腮红 =================
    for ($i = 6; $i -ge 1; $i--) {
        $a = [int](8 + 22 * (1 - $i / 6.0))
        $b = Brush "#FF9BA6" $a
        $rx = 4.8 * $i / 6; $ry = 2.9 * $i / 6
        $g.FillEllipse($b, (ERect 39.5 70.5 $rx $ry))
        $g.FillEllipse($b, (ERect 68.5 70.5 $rx $ry))
        $b.Dispose()
    }

    # ================= 9. 嘴 =================
    $mouth = Stroke 1.9 "#D4636E"
    $mp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $mp.AddBezier((P 50), (P 71), (P 52.67), (P 74), (P 55.33), (P 74), (P 58), (P 71))
    $g.DrawPath($mouth, $mp)
    $mouth.Dispose()

    # ================= 10. 星形发饰 =================
    $g.FillPath((Brush "#FFE082"), (Poly @(73, 31.5, 74.6, 34.9, 78, 36.5, 74.6, 38.1, 73, 41.5, 71.4, 38.1, 68, 36.5, 71.4, 34.9)))
    $g.FillEllipse((Brush "#FFFFFF"), (CRect 73 36.5 1.1))

    # ================= 11. 背景星芒 =================
    $g.FillPath((Brush "#FFFFFF" 153), (Poly @(31, 31.5, 32.1, 34.4, 35, 35.5, 32.1, 36.6, 31, 39.5, 29.9, 36.6, 27, 35.5, 29.9, 34.4)))
    $g.FillPath((Brush "#FFFFFF" 128), (Poly @(79, 65.5, 79.9, 67.9, 82.3, 68.8, 79.9, 69.7, 79, 72.1, 78.1, 69.7, 75.7, 68.8, 78.1, 67.9)))

    # ---- 收尾 ----
    $g.ResetClip()
    $g.Dispose()
    # 圆角矩形版本加一圈半透明白描边，提升层次
    if (-not $round) {
        $g2 = [System.Drawing.Graphics]::FromImage($bmp)
        $g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $pen2 = New-Object System.Drawing.Pen((Col "#FFFFFF" 40), [float]($size * 0.012))
        $g2.DrawPath($pen2, $clip)
        $g2.Dispose(); $pen2.Dispose()
    }
    return $bmp
}

$count = 0
foreach ($d in $densities.Keys) {
    $dir = Join-Path $ResDir $d
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $size = $densities[$d]

    $sq = New-Icon $size $false
    $sq.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sq.Dispose()

    $rd = New-Icon $size $true
    $rd.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $rd.Dispose()

    $count += 2
    Write-Host ("  {0,-16} {1,3}x{1,-3}  ic_launcher.png / ic_launcher_round.png" -f $d, $size)
}
Write-Host "`n已生成 $count 个 PNG，输出目录: $ResDir" -ForegroundColor Green
