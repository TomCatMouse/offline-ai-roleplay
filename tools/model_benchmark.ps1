# -*- coding: utf-8 -*-
<#
.SYNOPSIS
    GGUF 模型横评：速度 + 角色扮演质量

.DESCRIPTION
    对 models\gguf 下的每个模型依次：
      1. 用与 scripts\run_servers.bat 完全相同的参数启动 llama-server（端口 8081，不干扰 8080 主服务）
      2. 测量模型加载耗时
      3. 跑速度探针，读取 llama.cpp 自报的 prompt / 生成 速度
      4. 调用 roleplay_quality_test.ps1 跑 19 轮三角色复杂对话，统计质量问题数
      5. 关停服务，归档该模型的详细报告

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\model_benchmark.ps1
    powershell -ExecutionPolicy Bypass -File tools\model_benchmark.ps1 -Only "Qwen3*"
#>
param(
    [string]$Only = "*",
    [int]$Port = 18080,
    [double]$Temperature = 0.75
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot          # 项目根目录
$exe = Join-Path $root "engine\x86_64\llama-b11558-win-x64\llama-server.exe"
$ggufDir = Join-Path $root "models\gguf"
$evidence = Join-Path $root "docs\evidence"
$logDir = Join-Path $root "docs\evidence\bench_logs"
New-Item -ItemType Directory -Force -Path $evidence, $logDir | Out-Null

if (-not (Test-Path $exe)) { Write-Host "找不到 llama-server: $exe" -ForegroundColor Red; exit 1 }

$models = Get-ChildItem $ggufDir -Filter "$Only.gguf" | Sort-Object Name
if ($models.Count -eq 0) { $models = Get-ChildItem $ggufDir -Filter "*.gguf" | Sort-Object Name }

# 与 run_servers.bat 保持一致的采样治理参数
$serverArgs = @(
    "--host", "127.0.0.1",
    "--threads", "6", "--ctx-size", "8192",
    "--reasoning", "off",
    "--repeat-last-n", "512", "--repeat-penalty", "1.1",
    "--presence-penalty", "0.2", "--frequency-penalty", "0.1",
    "--dry-multiplier", "0.5", "--dry-base", "1.75",
    "--dry-allowed-length", "3", "--dry-penalty-last-n", "512"
)

$probePrompts = @(
    "你还记得我们小时候在老槐树下埋的那个铁盒子吗？里面到底放了什么，我一直没敢问。",
    "山下的镇子昨夜被血洗了，四十七口人，一个活口都没留。你早就知道会这样，对不对？",
    "哥哥，我今天在学校被人说是笨蛋了……你说我是不是真的很笨啊？"
)

function Test-PortFree($p) {
    # netstat 行格式： TCP  本地地址  外部地址  STATE  PID
    # 冒号后只有「外部地址 + STATE」两个 token；多写一个 \S+ 会永远匹配不到，
    # 让端口检测永远返回"空闲"。这里刻意写死 STATE 关键字。
    return -not (netstat -ano | Select-String ":$p\s+\S+\s+LISTENING")
}

function Find-FreePort($start) {
    for ($p = $start; $p -lt ($start + 60); $p++) {
        if (Test-PortFree $p) { return $p }
    }
    throw "从 $start 起找不到空闲端口"
}

function Start-Model($path, $port) {
    # 坑 1：不能用 Start-Process —— 本机环境块里同时有 NO_PROXY 和 no_proxy，
    #        PowerShell 用大小写敏感字典读取会抛 ArgumentException，必须走 .NET。
    # 坑 2：不能重定向 stdout/stderr —— 不消费管道会让 llama-server 日志写满
    #        缓冲区后整进程阻塞。改用 llama-server 自带的 --log-file。
    $safe = [System.IO.Path]::GetFileNameWithoutExtension($path)
    $logFile = Join-Path $logDir ("server_$safe.log")
    $argList = (@("-m", "`"$path`"", "--port", "$port") + $serverArgs + @("--log-file", "`"$logFile`"")) -join " "

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = $argList
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.WorkingDirectory = $root

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $proc = [System.Diagnostics.Process]::Start($psi)
    $ok = $false
    for ($i = 0; $i -lt 240; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/health" -TimeoutSec 2 -UseBasicParsing
            if ($r.StatusCode -eq 200) { $ok = $true; break }
        } catch { }
        if ($proc.HasExited) { break }
    }
    $sw.Stop()
    return @{ Process = $proc; LoadSec = [math]::Round($sw.Elapsed.TotalSeconds, 1); Ok = $ok; Log = $logFile }
}

function Stop-Model($proc) {
    if ($proc -and -not $proc.HasExited) {
        try { $proc.Kill(); $proc.WaitForExit(10000) | Out-Null } catch { }
    }
}

function Get-Speed($port) {
    $speeds = @()
    foreach ($q in $probePrompts) {
        $body = @{
            messages = @(@{ role = "system"; content = "你是一个中文角色扮演助手。" },
                         @{ role = "user"; content = $q })
            max_tokens = 200; temperature = $Temperature; stream = $false
        } | ConvertTo-Json -Depth 10 -Compress
        try {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
            $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$port/v1/chat/completions" -Method POST `
                -Body $bytes -ContentType "application/json; charset=utf-8" -TimeoutSec 180 -UseBasicParsing
            $j = $resp.Content | ConvertFrom-Json
            if ($j.timings) {
                $speeds += [pscustomobject]@{
                    Prompt = [math]::Round($j.timings.prompt_per_second, 1)
                    Gen    = [math]::Round($j.timings.predicted_per_second, 1)
                    Tokens = $j.usage.completion_tokens
                }
            }
        } catch { }
    }
    if ($speeds.Count -eq 0) { return [pscustomobject]@{ Prompt = 0; Gen = 0; Tokens = 0 } }
    return [pscustomobject]@{
        Prompt = [math]::Round(($speeds | Measure-Object Prompt -Average).Average, 1)
        Gen    = [math]::Round(($speeds | Measure-Object Gen -Average).Average, 1)
        Tokens = [math]::Round(($speeds | Measure-Object Tokens -Average).Average, 0)
    }
}

Write-Host "`n开始横评，共 $($models.Count) 个模型，端口 $Port`n" -ForegroundColor Cyan
$results = @()

foreach ($m in $models) {
    $name = $m.BaseName
    Write-Host ("=" * 78) -ForegroundColor Cyan
    Write-Host "  模型: $name   ($([math]::Round($m.Length/1GB,2)) GB)" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor Cyan

    # 每个模型都重新挑一个空闲端口，避免撞上系统里已占用的端口
    $port = Find-FreePort $Port
    Write-Host "  使用端口: $port" -ForegroundColor DarkGray
    $started = Start-Model $m.FullName $port
    if (-not $started.Ok) {
        Write-Host "  ✗ 启动失败（详见 $logDir）" -ForegroundColor Red
        Stop-Model $started.Process
        $results += [pscustomobject]@{
            Model = $name; SizeGB = [math]::Round($m.Length/1GB, 2)
            LoadSec = $started.LoadSec; GenTokS = 0; PromptTokS = 0
            QualityIssues = -1; TotalTurns = 0; Detail = "启动失败"
        }
        continue
    }
    Write-Host "  ✓ 加载完成，耗时 $($started.LoadSec) 秒" -ForegroundColor Green

    $sp = Get-Speed $port
    Write-Host "  速度: 生成 $($sp.Gen) tok/s | 预填充 $($sp.Prompt) tok/s | 平均输出 $($sp.Tokens) tokens" -ForegroundColor Yellow

    Write-Host "  跑 19 轮角色质量测试..." -ForegroundColor Yellow
    $qi = -1; $turns = 0
    try {
        $out = & (Join-Path $PSScriptRoot "roleplay_quality_test.ps1") -Char all -ServerHost 127.0.0.1 -Port $port -Temperature $Temperature 2>&1
        $qi = $LASTEXITCODE
        $m2 = [regex]::Match(($out -join "`n"), '(\d+)\s*轮[，,]\s*(?:检出\s*)?(\d+)\s*(?:处问题|问题)')
        if ($m2.Success) { $turns = [int]$m2.Groups[1].Value; $qi = [int]$m2.Groups[2].Value }
        $out | Set-Content -Path (Join-Path $logDir "quality_$name.txt") -Encoding UTF8
    } catch {
        Write-Host "  ✗ 质量测试异常: $_" -ForegroundColor Red
    }
    Write-Host "  质量问题: $qi 处 / $turns 轮" -ForegroundColor $(if ($qi -le 1) { "Green" } else { "Red" })

    # 归档该模型的详细报告
    $qr = Join-Path $evidence "quality_report.json"
    if (Test-Path $qr) {
        $safe = ($name -replace '[^\w\-\.]', '_')
        Move-Item -Force $qr (Join-Path $evidence "quality_$safe.json")
    }

    Stop-Model $started.Process
    Write-Host "  已关停`n" -ForegroundColor DarkGray

    $results += [pscustomobject]@{
        Model         = $name
        SizeGB        = [math]::Round($m.Length/1GB, 2)
        LoadSec       = $started.LoadSec
        GenTokS       = $sp.Gen
        PromptTokS    = $sp.Prompt
        QualityIssues = $qi
        TotalTurns    = $turns
        Detail        = $(if ($qi -eq 0) { "全通过" } elseif ($qi -gt 0) { "$qi 处问题" } else { "未测" })
    }
}

Write-Host ("=" * 78) -ForegroundColor Cyan
Write-Host "  横评结果汇总" -ForegroundColor Cyan
Write-Host ("=" * 78) -ForegroundColor Cyan
$results | Format-Table -AutoSize
$results | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $evidence "model_benchmark.json") -Encoding UTF8
Write-Host "结果已存: docs\evidence\model_benchmark.json" -ForegroundColor DarkGray
Write-Host "服务端日志: $logDir" -ForegroundColor DarkGray
