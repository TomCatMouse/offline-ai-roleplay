# -*- coding: utf-8 -*-
# 临时脚本：把图标 PNG 渲染成字符画，用于在无法读图的情况下验证绘制结果
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"

# 调色板：设计值 → 字符
$palette = @(
    @{ c = "#7048CC"; ch = " " },   # 背景紫
    @{ c = "#35176B"; ch = " " },
    @{ c = "#9B8AF5"; ch = " " },
    @{ c = "#4A2A1A"; ch = "#" },   # 描边
    @{ c = "#8A4E32"; ch = "H" },   # 后发
    @{ c = "#7A4228"; ch = "h" },   # 侧发
    @{ c = "#B87A5C"; ch = "b" },   # 刘海
    @{ c = "#FFEDDD"; ch = "." },   # 脸
    @{ c = "#FFE0CC"; ch = "." },   # 耳
    @{ c = "#35242E"; ch = "@" },   # 眼框
    @{ c = "#241C2A"; ch = "p" },   # 瞳孔
    @{ c = "#FFFDFB"; ch = "O" },   # 眼白
    @{ c = "#FFFFFF"; ch = "O" },
    @{ c = "#8A5CFF"; ch = "I" },   # 虹膜
    @{ c = "#6B3FD6"; ch = "I" },
    @{ c = "#FF9BA6"; ch = "+" },   # 腮红
    @{ c = "#D4636E"; ch = "v" },   # 嘴
    @{ c = "#FFE082"; ch = "*" }    # 星饰
)
$pal = $palette | ForEach-Object {
    $col = [System.Drawing.ColorTranslator]::FromHtml($_.c)
    [pscustomobject]@{ R = $col.R; G = $col.G; B = $col.B; Ch = $_.ch }
}

function Show-Ascii($file, $cols) {
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList $file
    $step = $bmp.Width / [double]$cols
    Write-Host "`n===== $([System.IO.Path]::GetFileName($file))  $($bmp.Width)x$($bmp.Height) ====="
    for ($row = 0; $row -lt $cols; $row++) {
        $line = ""
        for ($col = 0; $col -lt $cols; $col++) {
            $x = [int](($col + 0.5) * $step); $y = [int](($row + 0.5) * $step)
            if ($x -ge $bmp.Width) { $x = $bmp.Width - 1 }
            if ($y -ge $bmp.Height) { $y = $bmp.Height - 1 }
            $p = $bmp.GetPixel($x, $y)
            # 透明像素标记为 -
            if ($p.A -lt 40) { $line += "-"; continue }
            $best = $null; $bd = [double]::MaxValue
            foreach ($q in $pal) {
                $d = [math]::Pow($p.R - $q.R, 2) + [math]::Pow($p.G - $q.G, 2) + [math]::Pow($p.B - $q.B, 2)
                if ($d -lt $bd) { $bd = $d; $best = $q }
            }
            $line += $best.Ch
        }
        Write-Host ("  " + $line)
    }
    $bmp.Dispose()
}

$res = "$PSScriptRoot\..\app\src\main\res"
Show-Ascii "$res\mipmap-xxxhdpi\ic_launcher.png" 54
Show-Ascii "$res\mipmap-mdpi\ic_launcher_round.png" 34
