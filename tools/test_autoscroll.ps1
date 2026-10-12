# -*- coding: utf-8 -*-
# 临时测试：验证长会话自动下滚（清单第 1 项）
# 判定逻辑：uiautomator 只会 dump「当前可见」的节点，
#   所以只要最后一条消息的文本出现在 dump 里且位置靠近输入框，
#   就说明 LazyColumn 已自动滚到底部。
$ErrorActionPreference = "Continue"
$adb = "D:\MuMu\nx_main\adb.exe"

$msgs = @(
    "你还记得我们小时候在老槐树下埋的那个铁盒子吗",
    "里面到底放了什么我一直没敢问",
    "我今天在城外看到一支军队往北边去了",
    "旗子是黑底金纹的你知道那是谁的人马吗",
    "如果我跟着那支军队走你会拦我吗",
    "你说的话我记下了你再说一遍好吗"
)

function Get-UI {
    & $adb shell "uiautomator dump /storage/emulated/0/t.xml" 2>&1 | Out-Null
    return (& $adb shell "cat /storage/emulated/0/t.xml" 2>&1) -join ""
}

function Get-Texts($xml) {
    $out = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $t = $m.Groups[1].Value
        if ($t.Trim() -ne "") {
            $out += [pscustomobject]@{ Text = $t; Top = [int]$m.Groups[3].Value; Bottom = [int]$m.Groups[5].Value }
        }
    }
    return $out
}

Write-Host "===== 自动下滚验证开始 =====`n"
$inputTapY = 1208
$sendX = 660; $sendY = 1220

for ($n = 0; $n -lt $msgs.Count; $n++) {
    $msg = $msgs[$n]
    Write-Host "[轮 $($n+1)] 发送: $msg"

    & $adb shell "input tap 300 $inputTapY" | Out-Null
    Start-Sleep -Milliseconds 800
    & $adb shell "input text '$msg'" | Out-Null
    Start-Sleep -Milliseconds 800
    & $adb shell "input tap $sendX $sendY" | Out-Null

    # 等回复结束：连续两次 dump 文本一致
    $prev = ""
    $stable = 0
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        $cur = (Get-Texts (Get-UI) | ForEach-Object { $_.Text }) -join "|"
        if ($cur -eq $prev -and $cur.Length -gt 0) { $stable++ } else { $stable = 0 }
        $prev = $cur
        if ($stable -ge 2) { break }
    }

    $texts = Get-Texts (Get-UI)
    # 找最后一条非界面元素的文本（排除页头/输入框）
    $body = $texts | Where-Object { $_.Top -gt 250 -and $_.Top -lt 1160 }
    $last = $body | Sort-Object Top | Select-Object -Last 1
    Write-Host ("        可见消息节点 {0} 个；最底部节点 y=[{1}..{2}] 文本: {3}" -f `
        $body.Count, $(if ($last) { $last.Top } else { "-" }), $(if ($last) { $last.Bottom } else { "-" }), `
        $(if ($last) { $last.Text.Substring(0, [math]::Min(40, $last.Text.Length)) } else { "无" }))
}

Write-Host "`n===== 最终界面可见内容 ====="
$final = Get-Texts (Get-UI)
$final | Where-Object { $_.Top -gt 150 } | Sort-Object Top | ForEach-Object {
    Write-Host ("  y={0,4}  {1}" -f $_.Top, $_.Text.Substring(0, [math]::Min(46, $_.Text.Length)))
}
Write-Host "`n输入框位置: $((Get-Texts (Get-UI) | Where-Object { $_.Text -like '*输入消息*' } | Select-Object -First 1).Top)"
