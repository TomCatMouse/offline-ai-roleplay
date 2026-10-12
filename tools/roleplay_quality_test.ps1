# -*- coding: utf-8 -*-
<#
.SYNOPSIS
    角色对话质量回归测试

.DESCRIPTION
    直接用 PC 端 llama-server 复刻 App 的真实请求结构（system 角色卡 + role 分明的多轮历史），
    用性格差异极大的三个角色跑复杂多轮对话，并自动质检以下问题：

      - 答非所问 / 未遵守明确指令（如"只回答会还是不会"）
      - 跨轮复用整句（复读机）
      - OOC 泄漏（自称 AI / 模型 / 提到"设定""扮演"）
      - 旁白式分析腔
      - 繁体字、异体字、外文标点漂移
      - 用词出戏（可爱角色说"老子"、古风角色说网络流行语）

    改动提示词、采样参数或更换模型后，跑一遍这个脚本即可确认没有回退。

.EXAMPLE
    # 先确保推理服务已启动（scripts\run_servers.bat）
    powershell -ExecutionPolicy Bypass -File tools\roleplay_quality_test.ps1
    powershell -ExecutionPolicy Bypass -File tools\roleplay_quality_test.ps1 -Char mochen -Temperature 0.8
#>
param(
    [ValidateSet("all", "linxiaoxia", "mochen", "suwaner")]
    [string]$Char = "all",
    [string]$ServerHost = "127.0.0.1",
    [int]$Port = 8080,
    [double]$Temperature = 0.75
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$baseUrl = "http://${ServerHost}:${Port}"
$SEP = "========================================================================"

# ============================================================
#  铁律：必须与 GGUFAIService.buildCharacterCard 保持一致
# ============================================================
$rulesTemplate = @'
【铁律】
1. 全程以$NAME的第一人称说话。
2. 始终使用简体中文，绝对不要输出繁体字、异体字或外文字符。
3. 当对方提出明确的格式、字数或回答方式要求（例如"只回答会或不会""说一句超过30字的话"）时，优先满足该要求，再谈风格。
4. 用词必须严格贴合角色的身份、年龄与性格：可爱系角色不得用粗俗或老气横秋的自称（如"老子""爷"），古风角色不得使用现代网络流行语。
5. 禁止旁白式解释、禁止分析对方的话、禁止复述对方原话。
6. 禁止说自己是AI、模型、程序，禁止提到"设定""扮演""角色扮演"这类词。
7. 你是有情绪、有立场、有秘密的人。被追问时要有真实反应——回避、动摇、反问、生气都可以，不要像客服一样客气。
8. 直接回应对方这一句的**具体内容**，不要答非所问。
9. 严禁复用前面轮次说过的句子、句式或口头禅。尤其不要重复你前几轮用过的问句或收尾话——同一个意思每轮都必须换一种说法。
10. 长度随情境自然变化，一般 2~5 句。
'@

$roster = @{
    linxiaoxia = @{
        name = "林小夏"; personality = "温柔体贴，善解人意，说话轻声细语"
        style = "古风现代混合，偶尔引用诗句"; backstory = "青梅竹马，从小一起长大，一直默默守护着你"
        greeting = "(轻轻拉住你的衣袖) 你终于来了...我等你好久了。"; tags = "温柔、青梅竹马、治愈"
        turns = @(
            @{ text = "你还记得我们小时候在老槐树下埋的那个铁盒子吗？里面到底放了什么，我一直没敢问。"; check = "memory" },
            @{ text = "等等，你刚说的和我记忆里不一样。我记得盒子里是三样东西，你却只说了两样，是不是有什么事瞒着我？"; check = "consistency" },
            @{ text = "算了，先不说这个。我今天在城外看到一支军队往北边去了，旗子是黑底金纹的。你知道那是谁的人马吗？"; check = "topic_switch" },
            @{ text = "你刚才说不知道，可你手一直在抖。林小夏，我们认识十五年了，你骗不了我。说吧，到底是谁。"; check = "pressure" },
            @{ text = "如果我说，我要跟着那支军队走，明天天亮就出发，你会拦我吗？只回答会还是不会，然后告诉我为什么。"; check = "yn" },
            @{ text = "你把刚才那句话重复一遍，一字不差。"; check = "recall" },
            @{ text = "好。那我最后问你一次：你到底叫什么名字？"; check = "identity"; mustName = $true }
        )
    }
    mochen = @{
        name = "墨尘"; personality = "冷酷孤傲，实力强大，不善表达"
        style = "古风，言辞简洁，偶尔带点诗意"; backstory = "隐居深山的高手，因一场意外与你产生羁绊"
        greeting = "(淡然凝视) ...你来了。"; tags = "高冷、武侠、神秘"
        turns = @(
            @{ text = "山下的镇子昨夜被血洗了，四十七口人，一个活口都没留。你早就知道会这样，对不对？"; check = "pressure" },
            @{ text = "我不信你不知道。你手里这把剑，剑穗上沾的血还没干透。"; check = "evidence" },
            @{ text = "……好吧。那你告诉我，什么叫做'该来的总会来'？我师父死前也在念这句话。"; check = "philosophy" },
            @{ text = "我现在很乱。你能不能说一句超过三十个字的话？就一句，别再一个字一个字地蹦。"; check = "instruction_len" },
            @{ text = "如果我求你去救剩下的人，你会答应吗？先回答去还是不去。"; check = "yn" },
            @{ text = "你看，你又在沉默。你上一次开口是七天前。"; check = "consistency" }
        )
    }
    suwaner = @{
        name = "苏婉儿"; personality = "活泼可爱，有点天然呆，爱笑"
        style = "可爱系，喜欢用叠词和颜文字"; backstory = "邻家妹妹，总是黏着你，有什么心事都喜欢跟你说"
        greeting = "哥哥哥哥！(扑过来抱住) 今天也要陪我玩哦~"; tags = "可爱、妹系、元气"
        turns = @(
            @{ text = "哥哥，我今天在学校被人说是笨蛋了……你说我是不是真的很笨啊？"; check = "emotion" },
            @{ text = "可是那个人还把我的画撕掉了！那幅画我画了三个星期，画的是哥哥和我一起放风筝。"; check = "detail" },
            @{ text = "唔……算了算了，我们不说这个了！哥哥你会折纸鹤吗？教我嘛教我嘛！"; check = "topic_switch" },
            @{ text = "等一下，你刚才没回答我会不会折。你到底会不会呀？只回答会或者不会。"; check = "yn" },
            @{ text = "那你说，你最讨厌我哪一点？不许说没有，必须说一个。"; check = "pressure" },
            @{ text = "……哥哥，其实我今天哭过了。你不要告诉妈妈好不好？"; check = "secret" }
        )
    }
}

function Build-Card($cfg) {
    $rules = $rulesTemplate.Replace('$NAME', $cfg.name)
    return @"
你现在就是「$($cfg.name)」，你不是AI，不是助手，不是语言模型。你活在这个故事里。

【你是谁】
- 名字：$($cfg.name)
- 性格：$($cfg.personality)
- 说话风格：$($cfg.style)
- 背景：$($cfg.backstory)
- 标签：$($cfg.tags)

【表达方式】
动作、神态、语气写在圆括号里，例如：（指尖微微一颤）。台词直接说，不加引号。
你的语气可以参考你平时的开场方式，例如：$($cfg.greeting)

$rules
"@
}

function Invoke-Chat($messages, [double]$Temp) {
    $body = @{
        messages          = $messages
        max_tokens        = 512
        temperature       = $Temp
        top_p             = 0.9
        repeat_penalty    = 1.1
        presence_penalty  = 0.2
        frequency_penalty = 0.1
        stream            = $false
    } | ConvertTo-Json -Depth 10 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $resp = Invoke-WebRequest -Uri "$baseUrl/v1/chat/completions" -Method POST `
        -Body $bytes -ContentType "application/json; charset=utf-8" -TimeoutSec 240 -UseBasicParsing
    $txt = [string]($resp.Content | ConvertFrom-Json).choices[0].message.content
    $txt = [regex]::Replace($txt, '(?s) thinking.*?</think>', '')
    $txt = [regex]::Replace($txt, '(?s) thinking.*$', '')
    return $txt.Trim()
}

# ============================================================
#  质检
# ============================================================
$OOC_WORDS = @("AI", "模型", "语言模型", "设定", "扮演", "角色扮演", "作为助手")
$VULGAR = @("老子", "本大爷", "老娘", "大爷我")
$MODERN = @("么么哒", "yyds", "绝绝子", "集美", "破防", "整挺好")
$TRAD = "會為這裡們後時樣說還來個對從開關發現實點與體過頭見嗎讓覺聽誰幾機屬業隊動勢進遠邊勝樂觀規計記認識談請講論議試課調變專傳僅師帶幫歸島傷慘態戀懼圖圍園圓場塊壞壓鋼豐龍鳳鶴鷹適遲選遺遞著丟趕嘆喚囑讚譽於"
$FOREIGN_PUNCT = @([char]0x060C, [char]0x066C, [char]0x061B, [char]0x061F, [char]0x104B, [char]0x104A)

function Get-Sentences([string]$text) {
    return [regex]::Split($text, '[。！？!?\n]') | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -ge 8 }
}

function Test-Turn {
    param([string]$Reply, [string]$Check, [string[]]$PrevReplies, [string]$CharName, [bool]$MustName)
    $issues = @()

    foreach ($w in $OOC_WORDS) { if ($Reply -like "*$w*") { $issues += "OOC泄露:$w" } }
    if ($Reply -match "你的意思是|根据你(的)?话|这段话|我理解你|从你的话|你想表达|作为(一个)?AI") { $issues += "旁白式分析腔" }
    foreach ($w in $VULGAR) { if ($Reply -like "*$w*") { $issues += "粗俗自称:$w" } }
    foreach ($w in $MODERN) { if ($Reply -like "*$w*") { $issues += "现代网络语:$w" } }

    # 英文单词泄漏（规则要求纯中文，模型偶尔会漏出 maybe / something 这类词）
    $en = [regex]::Matches($Reply, '(?<![A-Za-z0-9])[A-Za-z]{3,}(?![A-Za-z0-9])') | ForEach-Object { $_.Value }
    if ($en.Count -gt 0) { $issues += "英文词泄漏:$((($en | Select-Object -Unique) -join ','))" }
    $trad = ($Reply.ToCharArray() | Where-Object { $TRAD.Contains($_) }).Count
    $fp = ($Reply.ToCharArray() | Where-Object { $FOREIGN_PUNCT -contains $_ }).Count
    if ($trad -ge 2) { $issues += "繁体漂移(${trad}字)" }
    if ($fp -ge 1) { $issues += "外文标点(${fp}个)" }

    # 跨轮复用整句（recall 轮是用户主动要求复述，不判）
    if ($Check -ne "recall" -and $PrevReplies.Count -gt 0) {
        $mine = Get-Sentences $Reply
        $old = @()
        foreach ($p in $PrevReplies) { $old += Get-Sentences $p }
        foreach ($s in $mine) { if ($old -contains $s) { $issues += "复用整句「$s」" } }
    }

    if ($Check -eq "yn") {
        $head = $Reply.Substring(0, [math]::Min(60, $Reply.Length))
        if ($head -notmatch "会|會|不会|不會|去|不去|能|不能") { $issues += "未先给明确答复" }
    }
    if ($Check -eq "instruction_len") {
        $longest = (Get-Sentences $Reply | Measure-Object -Property Length -Maximum).Maximum
        if (-not $longest -or $longest -lt 30) { $issues += "未产出30字长句(最长$(if($longest){$longest}else{0})字)" }
    }
    if ($MustName -and $Reply -notmatch [regex]::Escape($CharName)) { $issues += "未自称$CharName" }

    return $issues
}

# ============================================================
#  主流程
# ============================================================
try {
    $h = Invoke-WebRequest -Uri "$baseUrl/health" -TimeoutSec 5 -UseBasicParsing
    Write-Host "服务健康: $($h.StatusCode)  ($baseUrl)" -ForegroundColor Green
} catch {
    Write-Host "推理服务不可达: $baseUrl" -ForegroundColor Red
    Write-Host "请先运行 scripts\run_servers.bat" -ForegroundColor Yellow
    exit 1
}

$targets = if ($Char -eq "all") { @("linxiaoxia", "mochen", "suwaner") } else { @($Char) }
$grandIssues = 0
$grandTurns = 0
$report = @()

foreach ($key in $targets) {
    $cfg = $roster[$key]
    Write-Host ("`n" + $SEP) -ForegroundColor Cyan
    Write-Host "  角色: $($cfg.name)   轮数: $($cfg.turns.Count)   温度: $Temperature" -ForegroundColor Cyan
    Write-Host $SEP -ForegroundColor Cyan

    $history = @()
    $prevReplies = @()
    $turnIssues = 0

    for ($i = 0; $i -lt $cfg.turns.Count; $i++) {
        $turn = $cfg.turns[$i]
        Write-Host ("`n[第 $($i+1) 轮 / $($turn.check)] 用户: $($turn.text)") -ForegroundColor Yellow
        try {
            $messages = @(@{ role = "system"; content = (Build-Card $cfg) })
            foreach ($m in $history) { $messages += $m }
            $messages += @{ role = "user"; content = $turn.text }

            $reply = Invoke-Chat $messages $Temperature
            Write-Host "  $($cfg.name): $reply" -ForegroundColor Green

            $issues = Test-Turn -Reply $reply -Check $turn.check -PrevReplies $prevReplies `
                                -CharName $cfg.name -MustName ([bool]$turn.mustName)
            if ($issues.Count -gt 0) {
                $turnIssues += $issues.Count
                Write-Host ("  ⚠ " + ($issues -join " / ")) -ForegroundColor Red
            } else {
                Write-Host "  ✓ 通过" -ForegroundColor DarkGreen
            }

            $history += @{ role = "user"; content = $turn.text }
            $history += @{ role = "assistant"; content = $reply }
            $prevReplies += $reply

            $report += [pscustomobject]@{
                Character = $cfg.name; Turn = $i + 1; Check = $turn.check
                User = $turn.text; Reply = $reply; Issues = ($issues -join " / ")
            }
        } catch {
            Write-Host "  ✗ 请求失败: $_" -ForegroundColor Red
            $turnIssues++
            $report += [pscustomobject]@{
                Character = $cfg.name; Turn = $i + 1; Check = $turn.check
                User = $turn.text; Reply = "ERROR"; Issues = "请求失败"
            }
        }
    }
    $grandIssues += $turnIssues
    $grandTurns += $cfg.turns.Count
    $color = if ($turnIssues -eq 0) { "Green" } else { "Red" }
    Write-Host "`n  >>> [$($cfg.name)] $($cfg.turns.Count) 轮，问题 $turnIssues 处" -ForegroundColor $color
}

$outDir = Join-Path $PSScriptRoot "..\docs\evidence"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = Join-Path $outDir "quality_report.json"
$report | ConvertTo-Json -Depth 5 | Set-Content -Path $outFile -Encoding UTF8

Write-Host ("`n" + $SEP) -ForegroundColor Cyan
if ($grandIssues -eq 0) {
    Write-Host "  总结果: 全部通过 ✓  ($grandTurns 轮，0 问题)" -ForegroundColor Green
} else {
    Write-Host "  总结果: 共 $grandTurns 轮，检出 $grandIssues 处问题 ✗" -ForegroundColor Red
}
Write-Host $SEP -ForegroundColor Cyan
Write-Host "报告: $outFile" -ForegroundColor DarkGray
exit $grandIssues
