# -*- coding: utf-8 -*-
<#
.SYNOPSIS
    验证「超长单条回复」时聊天列表能否自动滚到底部

.DESCRIPTION
    这是用户反馈的 bug「输出过长就会不向下滚动」的回归测试。

    原理：
      LazyListState.scrollToItem(index) 会把目标项【顶部】对齐到视口顶部。
      当最后一条消息比视口还高时，对齐顶部就意味着底部（最新文字）被推到屏幕外。
      正确做法是再 scrollBy(last.offset + last.size - viewportEndOffset) 把溢出的部分补滚掉。

      反直觉的关键点：
        - 消息高度 < 视口高度 -> scrollToItem 会被滚动范围 clamp 到底部，旧代码也正常（假通过）
        - 消息高度 > 视口高度 -> 旧代码必然露出 bug
      所以必须人为造出「单条消息高于视口」的条件，否则测了等于没测。

    怎么造这个条件：
      不要用 `wm size` 压矮屏幕 —— MuMu 上它只改 display 的 override 值，
      应用窗口不会真的重排（实测输入框仍在 y=592，视口高度没变）。
      改用【系统字体缩放】：settings put system font_scale 2.0
      气泡高度随行高翻倍，视口高度基本不变 -> 单条消息必然超过一屏。

    流程：
      1. font_scale=2.0 并重启 App
      2. 发一条要求写长文、并以「测试结束」结尾的请求
      3. 轮询 llama-server /slots 的 is_processing 判断生成结束
         （不能用 uiautomator 轮询：流式输出时 Compose 持续重组，dump 等不到 idle 会返回空）
      4. dump 后检查「测试结束」是否落在视口内

    判定：尾部标记可见 且 气泡底沿贴着视口下沿 = 通过；尾部不可见 = 失败

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\test_long_output_scroll.ps1
    powershell -ExecutionPolicy Bypass -File tools\test_long_output_scroll.ps1 -FontScale 2.5
#>
param(
    [string]$Adb      = "D:\MuMu\nx_main\adb.exe",
    [string]$Server   = "http://127.0.0.1:8080",
    [double]$FontScale = 2.0,   # 0 表示不改字体
    [int]$TimeoutSec  = 300
)

$ErrorActionPreference = "Continue"

$prompt = "请你用至少两百五十字详细描述我们小时候在老槐树下埋的那个铁盒子，里面到底有什么，我当时说了什么，你的心情如何，都要写清楚，写得越长越好。写完以后最后单独加上测试结束这四个字。"

function Get-UI {
    & $Adb shell "uiautomator dump /storage/emulated/0/ls.xml" 2>&1 | Out-Null
    return (& $Adb shell "cat /storage/emulated/0/ls.xml" 2>&1) -join ""
}
function Get-Nodes($xml) {
    $out = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $seg = $m.Value
        $out += [pscustomobject]@{
            Text  = [regex]::Match($seg, 'text="([^"]*)"').Groups[1].Value
            Desc  = [regex]::Match($seg, 'content-desc="([^"]*)"').Groups[1].Value
            Class = [regex]::Match($seg, 'class="([^"]*)"').Groups[1].Value
            Click = [regex]::Match($seg, 'clickable="([^"]*)"').Groups[1].Value
            Scroll= [regex]::Match($seg, 'scrollable="([^"]*)"').Groups[1].Value
            X1 = [int]$m.Groups[1].Value; Y1 = [int]$m.Groups[2].Value
            X2 = [int]$m.Groups[3].Value; Y2 = [int]$m.Groups[4].Value
        }
    }
    return $out
}
function Get-Screen {
    $s = (& $Adb shell "wm size" 2>&1) -join " "
    $m = [regex]::Match($s, 'Override size:\s*(\d+)x(\d+)')
    if (-not $m.Success) { $m = [regex]::Match($s, '(\d+)x(\d+)') }
    return [pscustomobject]@{ W = [int]$m.Groups[1].Value; H = [int]$m.Groups[2].Value }
}
function Get-FontScale { return ((& $Adb shell "settings get system font_scale" 2>&1) -join "").Trim() }
function Is-Processing {
    try {
        $c = (Invoke-WebRequest "$Server/slots" -TimeoutSec 4 -UseBasicParsing).Content
        return ($c -match '"is_processing":true')
    } catch { return $false }
}

# ============ 0. 放大字体（造出真正的复现条件）============
$origFS = Get-FontScale
Write-Host "原始 font_scale: $origFS"
if ($FontScale -gt 0) {
    & $Adb shell "settings put system font_scale $FontScale" | Out-Null
    Start-Sleep -Seconds 1
    Write-Host "放大后 font_scale: $(Get-FontScale)   -> 行高变大, 气泡变高, 视口基本不变"
}

try {
    # ============ 1. 进入聊天页 ============
    & $Adb shell "am force-stop com.example.roleplay" | Out-Null
    Start-Sleep -Seconds 1
    & $Adb shell "monkey -p com.example.roleplay -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
    Start-Sleep -Seconds 11

    $opened = $false
    for ($attempt = 1; $attempt -le 4 -and -not $opened; $attempt++) {
        $nodes = Get-Nodes (Get-UI)
        # 角色卡：宽 320；高度正常约 317，放大字体后约 375，
        # 且可能被窗口裁短，所以高度只设下限
        $card = $nodes | Where-Object {
            $_.Click -eq "true" -and ($_.X2 - $_.X1) -ge 250 -and ($_.X2 - $_.X1) -le 400 -and ($_.Y2 - $_.Y1) -ge 100 -and $_.Y1 -gt 520
        } | Select-Object -First 1
        if (-not $card) { Start-Sleep -Seconds 2; continue }
        $cx = [int](($card.X1 + $card.X2) / 2); $cy = [int](($card.Y1 + $card.Y2) / 2)
        Write-Host "  第 $attempt 轮: 点击角色卡 ($cx,$cy)"
        & $Adb shell "input tap $cx $cy" | Out-Null
        Start-Sleep -Seconds 8
        if ((Get-Nodes (Get-UI)) | Where-Object { $_.Text -like "*输入消息*" }) { $opened = $true }
    }
    if (-not $opened) { throw "4 轮都没能进入聊天页" }
    Write-Host "[OK] 已进入聊天页"

    # ============ 2. 定位输入框 / 发送按钮 / 列表视口 ============
    $nodes = Get-Nodes (Get-UI)
    $input = $nodes | Where-Object { $_.Class -match "EditText" } | Select-Object -First 1
    if (-not $input) { throw "找不到输入框" }
    $sendDesc = $nodes | Where-Object { $_.Desc -eq "发送" } | Select-Object -First 1
    $send = $null
    if ($sendDesc) {
        $send = $nodes | Where-Object { $_.Click -eq "true" -and $_.X1 -le $sendDesc.X1 -and $_.X2 -ge $sendDesc.X2 -and $_.Y1 -le $sendDesc.Y1 -and $_.Y2 -ge $sendDesc.Y2 } | Select-Object -First 1
    }
    if (-not $send) {
        $send = $nodes | Where-Object { $_.Click -eq "true" -and $_.Y1 -gt ($input.Y1 - 60) -and $_.X1 -gt $input.X2 } | Select-Object -First 1
    }
    if (-not $send) { throw "找不到发送按钮" }

    $ix = [int](($input.X1 + $input.X2) / 2); $iy = [int](($input.Y1 + $input.Y2) / 2)
    $sx = [int](($send.X1 + $send.X2) / 2);  $sy = [int](($send.Y1 + $send.Y2) / 2)

    # 列表视口 = 可滚动容器的实际 bounds（别用估算）
    $scroller = $nodes | Where-Object { $_.Scroll -eq "true" } | Sort-Object { ($_.Y2 - $_.Y1) } -Descending | Select-Object -First 1
    if ($scroller) { $vpTop = $scroller.Y1; $vpBot = $scroller.Y2 } else { $vpTop = 235; $vpBot = $input.Y1 }
    $viewport = $vpBot - $vpTop

    Write-Host "输入框中心=($ix,$iy)  发送按钮中心=($sx,$sy)"
    Write-Host "列表视口: y=[$vpTop..$vpBot]  高度 $viewport px"

    # ============ 3. 发送长输出请求 ============
    Write-Host ""
    Write-Host "发送长输出请求 ($($prompt.Length) 字)..."
    & $Adb shell "input tap $ix $iy" | Out-Null
    Start-Sleep -Milliseconds 1000
    & $Adb shell "input text '$prompt'" | Out-Null
    Start-Sleep -Milliseconds 1000
    & $Adb shell "input tap $sx $sy" | Out-Null

    # ============ 4. 轮询服务端判断生成起止 ============
    Write-Host "等待服务端开始生成..."
    $started = $false
    for ($i = 0; $i -lt 60; $i++) { Start-Sleep -Milliseconds 500; if (Is-Processing) { $started = $true; break } }
    Write-Host "  已开始: $started"
    Write-Host "等待生成结束 (最多 $TimeoutSec 秒)..."
    $stable = 0; $elapsed = 0
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        Start-Sleep -Seconds 1; $elapsed++
        if (Is-Processing) { $stable = 0 } else { $stable++; if ($stable -ge 4) { break } }
    }
    Write-Host "  生成结束, 用时约 $elapsed 秒"
    Start-Sleep -Seconds 3

    # ============ 5. 判定 ============
    $final = Get-Nodes (Get-UI)
    $fin = $final | Where-Object { $_.Class -match "EditText" } | Select-Object -First 1
    if (-not $fin) { $fin = $final | Where-Object { $_.Text -like "*输入消息*" } | Select-Object -First 1 }
    $bubble = $final | Where-Object { $_.Text.Trim() -ne "" -and $_.Y1 -ge ($vpTop - 40) -and $_.Y2 -le ($fin.Y1 + 40) } |
              Sort-Object { $_.Text.Length } -Descending | Select-Object -First 1
    if (-not $bubble) { throw "找不到任何消息气泡" }

    $bubbleH  = $bubble.Y2 - $bubble.Y1
    $marker   = $bubble.Text -like "*测试结束*"
    $ratio    = [math]::Round($bubbleH / [double]$viewport, 2)
    $sc       = Get-Screen

    Write-Host ""
    Write-Host "================= 结果 ================="
    Write-Host "  屏幕: $($sc.W)x$($sc.H)    font_scale: $(Get-FontScale)"
    Write-Host "  列表视口高度:  $viewport px   (y=[$vpTop..$vpBot])"
    Write-Host "  单条气泡高度:  $bubbleH px   (y=[$($bubble.Y1)..$($bubble.Y2)])"
    Write-Host "  气泡 / 视口:   $ratio 倍"
    Write-Host "  气泡字符数:    $($bubble.Text.Length)"
    Write-Host "  尾部标记[测试结束]可见: $(if ($marker) { '是 OK' } else { '否 NG' })"
    Write-Host "  气泡底沿 $($bubble.Y2) vs 视口下沿 $vpBot, 间距 $($vpBot - $bubble.Y2) px"
    $tail = $bubble.Text.Substring([math]::Max(0, $bubble.Text.Length - 40))
    Write-Host "  气泡结尾 40 字: ...$tail"

    # ─────────────────────────────────────────────────────────────
    # 判定逻辑（踩过坑，务必照这个来）
    #
    # 坑 1：uiautomator 报的 text 是**节点全文**，即使那段字已经滚出屏幕外。
    #       所以「文本里能找到尾部标记」根本不能证明它看得见 ——
    #       实测反证版（去掉补滚）尾部在屏幕外 11644px，脚本却照样报"通过"。
    #
    # 坑 2：uiautomator 会把 Compose 长文本节点的 bounds 裁剪到视口内，
    #       所以外部量不到"消息比视口高"这件事，高度比永远 <= 1。
    #
    # 真正可用的信号是**可见几何**：
    #   修复后  -> 滚到了超长消息的底部，气泡可见部分几乎占满整个视口
    #              （顶部被裁掉，底沿贴住视口下沿）
    #   未修复  -> 只做了顶部对齐，气泡在视口里只剩很窄的一条
    #
    # 用 App 内部日志交叉验证过（font_scale 2.0、单条 958 字）：
    #   修复版：溢出量在 0 与一行(96px)之间摆动，每次都被立刻补零 -> 最终 0
    #   旧逻辑：溢出量一路涨到 11644px，从不修正 -> 尾部在屏幕外 11644px
    # ─────────────────────────────────────────────────────────────
    $gap = $vpBot - $bubble.Y2
    $visibleH = $bubble.Y2 - $bubble.Y1
    Write-Host "  (注: bounds 是被 uiautomator 裁剪后的可见范围, 非真实高度)"
    Write-Host "  气泡可见高度: $visibleH px (视口 $viewport px, 占 $([math]::Round(100.0*$visibleH/$viewport,0))%)"

    # 200 字以上在 font_scale 2.0 下必然远超一屏（视口仅约 11 行）
    $expectTall = $bubble.Text.Length -gt 200

    if (-not $marker) {
        Write-Host ""
        Write-Host "  [NG] 连尾部标记都没抓到 -> 失败" -ForegroundColor Red
        $verdict = "失败"
    } elseif ($expectTall -and $visibleH -lt 0.8 * $viewport) {
        Write-Host ""
        Write-Host "  [NG] 长回复只露出 $visibleH px（占视口 $([math]::Round(100.0*$visibleH/$viewport,0))%），" -ForegroundColor Red
        Write-Host "       说明只做了顶部对齐、最新文字还在屏幕外 -> bug 仍在" -ForegroundColor Red
        Write-Host "       （文本里能找到尾部标记是假象：uiautomator 报的是节点全文）" -ForegroundColor Red
        $verdict = "失败"
    } elseif ($gap -le 24) {
        Write-Host ""
        Write-Host "  [OK] 气泡可见部分占满视口 $([math]::Round(100.0*$visibleH/$viewport,0))%，" -ForegroundColor Green
        Write-Host "       且底沿距视口下沿仅 $gap px -> 已滚到超长消息的最底部" -ForegroundColor Green
        $verdict = "通过"
    } else {
        Write-Host ""
        Write-Host "  [?] 气泡底沿距视口下沿 $gap px 偏大, 建议人工复查" -ForegroundColor Yellow
        $verdict = "存疑"
    }
    Write-Host ""
    Write-Host "结论: $verdict"
}
finally {
    if ($FontScale -gt 0) {
        if ($origFS -eq "null" -or $origFS -eq "") { & $Adb shell "settings delete system font_scale" | Out-Null }
        else { & $Adb shell "settings put system font_scale $origFS" | Out-Null }
        Start-Sleep -Seconds 1
        Write-Host "已恢复 font_scale: $(Get-FontScale)"
    }
}
