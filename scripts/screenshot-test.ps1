<#
  打包脚本配套冒烟测试（G4）：
    - 启动 App → 等冷启动 → 连拍 4 张截图
    - 截图存放位置（用户指定）：
        * MuMu 版（-Target mumu）：设备 /mnt/shared/MuMuShared/AI角色扮演/test/shot-<时间戳>\（MuMu 共享文件夹）
        * Flyme 版（-Target flyme）：设备 /storage/emulated/0/AI角色扮演/test/shot-<时间戳>\
    - 截图统一拉回  <DistDir>\测试截图\<时间戳>\  留作测试记录
    - 顺手查崩溃：pidof + logcat 里的 FATAL / AndroidRuntime
    - 测试完自动清掉设备侧的截图文件夹（本地留档不受影响；-Keep 可保留设备侧）

  用法（一般不用手工调，build-default / build-phys 会在有设备时自动调用）：
    powershell -File scripts\screenshot-test.ps1 -Target mumu -Package com.example.roleplay `
               -Apk ..\dist\MuMu版\AI角色扮演-MuMu版-v2.21.apk -DistDir ..\dist\MuMu版
#>
param(
    [ValidateSet('mumu', 'flyme')]
    [string]$Target = 'mumu',
    [string]$Package = 'com.example.roleplay',
    [string]$Apk = '',
    [string]$DistDir = '',
    [string]$Device = '',
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'

# 真机版（flyme 目标）的 applicationId 是 com.example.roleplay.phys，
# 但 Activity 类名还是 com.example.roleplay.MainActivity（namespace 没变）。
# 之前这里写死了默认包名，am start 直接报 "Error type 3: Activity class does not exist"。
if ($Target -eq 'flyme' -and $Package -eq 'com.example.roleplay') {
    $Package = 'com.example.roleplay.phys'
}

# 单独跑（不走 build-*.ps1）时也要有截图落盘目录，按目标给默认值：
# 之前 DistDir 默认空串，Split-Path '' 直接报错，连拍全白跑。
if ([string]::IsNullOrWhiteSpace($DistDir)) {
    $DistDir = if ($Target -eq 'flyme') { Join-Path $PSScriptRoot '..\dist\Flyme版' }
               else { Join-Path $PSScriptRoot '..\dist\MuMu版' }
}

# Windows PowerShell 5.1 把传给原生命令（adb）的中文参数按 GBK 编码，路径会串味；
# 先切到 UTF-8 代码页，chcp 只影响当前进程，脚本退出即复原。
if ($env:OS -eq 'Windows_NT') { chcp 65001 | Out-Null }

# ---- 1) 找设备：没给序列号就用唯一一台在线设备 ----
if ([string]::IsNullOrWhiteSpace($Device)) {
    $lines = (& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    if ($lines.Count -eq 0) {
        Write-Host '[冒烟测试] 没有在线设备，跳过（不影响打包结果）'
        exit 0
    }
    # MuMu 版优先找模拟器（emulator-*）；Flyme 版就用剩下的真机
    if ($Target -eq 'mumu') {
        $emu = $lines | Where-Object { $_ -match '^emulator-' } | Select-Object -First 1
        if ($emu) { $Device = ($emu -split "`t")[0] }
    }
    if ([string]::IsNullOrWhiteSpace($Device)) {
        if ($lines.Count -gt 1) {
            Write-Host '[冒烟测试] 连了多台设备，请用 -Device 指定；跳过'
            exit 0
        }
        $Device = ($lines[0] -split "`t")[0]
    }
}
Write-Host ("[冒烟测试] 设备：{0}（{1}）" -f $Device, $Target)

# ---- 2) 时间戳 + 设备侧截图文件夹 ----
# 全部 adb 参数只用 ASCII：中文目录（flyme 的 /storage/emulated/0/AI角色扮演、
# MuMu 的 /mnt/shared/MuMuShared/AI角色扮演）都在设备侧用通配/base64 定位，
# 绕开 Windows PowerShell 5.1 给原生命令传中文参数的编码问题（GBK 串味）。
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
if ($Target -eq 'flyme') {
    # 截图存到 App 自己的目录 /storage/emulated/0/AI角色扮演/shot-<ts>/（用户指定位置）
    # 注意两件事：
    #  1) shell 的 $(...) 不能直接写在 PowerShell 双引号串里 —— 反引号会被 PS 当转义符
    #     吃掉（之前就是这么坏的：设备侧收到 `find ...` → "no closing quote"）；
    #     这里用单引号串拼接，PS 完全不解释，原样送给设备的 sh。
    #  2) 用通配 + ls -d（而不是 find）来拿中文目录：只用 ASCII 输入就能定位，
    #     且不受 toybox find 默认不跟随符号链接的影响。
    $sh = 'A=$(ls -d /storage/emulated/0/AI*/ 2>/dev/null | head -1); [ -n "$A" ] && mkdir -p "$A/test/shot-' + $ts + '" && echo OK || echo NOAPPDIR'
    $setupOut = (& adb -s $Device shell $sh).Trim()
    if ($setupOut -notmatch 'OK') {
        Write-Host '[冒烟测试] 没找到 /storage/emulated/0/AI角色扮演 目录（Flyme 版 App 首启会自动建）；先装一次 App 再跑测试'
        exit 1
    }
    $devFolder = "test/shot-$ts"
} else {
    # MuMu 版：截图落到 MuMu 共享文件夹 /mnt/shared/MuMuShared/AI角色扮演/test/shot-<ts>/，
    # 宿主机能在 MuMu 的共享目录里直接看到图；/storage/emulated/0 根目录不留任何测试文件。
    #
    # 关键坑 1（中文不能进 adb shell）：Windows PowerShell 5.1 给原生命令传中文参数时按 GBK 发货，
    #   设备侧收到乱码。所以这里所有 shell 命令只用 ASCII，中文目录名靠两招拿到：
    #     a) 先通配 ls -d $M/AI*/ —— 目录已存在时首选（成本最低）；
    #     b) 目录不存在时用 base64 还原中文再 mkdir（toybox 的 printf 不支持 \xNN 十六进制转义，
    #        实测会原样吐出 "AIxe8xa7..."；八进制 printf '\350\247\222...' 也是可用的备选）。
    #   $shMumuDir 就是这段纯 ASCII 前置片段，执行后在设备侧留下 S = 中文根目录（无尾斜杠）。
    $mumuRoot = '/mnt/shared/MuMuShared'
    $mumuNameB64 = 'QUnop5LoibLmia7mvJQ='   # base64("AI角色扮演" 的 UTF-8 字节)
    $shMumuDir = 'M=' + $mumuRoot + '; S=$(ls -d $M/AI*/ 2>/dev/null | head -1); ' +
                 '[ -n "$S" ] || { N=$(printf %s ' + $mumuNameB64 + ' | base64 -d); S=$M/$N; mkdir -p $S; }; ' +
                 'S=${S%/}; [ -n "$S" ] || exit 1'
    # $devFolder 是设备侧真实路径（含中文），只给 adb pull 这种非 shell 场景用；
    # shell 场景一律走 $shMumuDir + "$S/test/shot-<ts>"，绝不让中文过 adb shell。
    $devFolder = "$mumuRoot/AI角色扮演/test/shot-$ts"
    $mirror = "/data/local/tmp/mumupull-$ts"
    $sh = $shMumuDir + '; mkdir -p "$S/test/shot-' + $ts + '" && echo OK || echo NOAPPDIR'
    $setupOut = (& adb -s $Device shell $sh).Trim()
    if ($setupOut -notmatch 'OK') {
        Write-Host '[冒烟测试] 没能在 MuMu 共享文件夹里建出 AI角色扮演/test/；先确认 MuMu 已打开「共享文件夹」再跑'
        exit 1
    }
}

# ---- 3) （可选）安装新包再测 ----
if (-not [string]::IsNullOrWhiteSpace($Apk) -and (Test-Path $Apk)) {
    Write-Host "[冒烟测试] 安装 $($Apk | Split-Path -Leaf) ..."
    & adb -s $Device install -r $Apk | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host '  ⚠ 安装失败，继续用设备上现有的版本测试'
    }
}

# ---- 4) 冷启动 + 连拍 4 张 ----
& adb -s $Device shell "am force-stop $Package" | Out-Null
& adb -s $Device shell "am start -n $Package/com.example.roleplay.MainActivity" 2>&1 | Out-Null
Write-Host '[冒烟测试] 已启动，开始连拍（4s / 10s / 16s / 22s）...'
$shots = 4
for ($i = 1; $i -le $shots; $i++) {
    $wait = 4
    if ($i -gt 1) { $wait = 6 }
    Start-Sleep -Seconds $wait
    $file = "/data/local/tmp/shot-$ts-$i.png"
    & adb -s $Device shell "screencap -p $file" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "  ⚠ shot $i：screencap 失败" }
    if ($Target -eq 'flyme') {
        # 同 L62：不能在 PS 双引号串里用反引号当 shell 的 $()，中文目录也不能过 adb shell
        $sh = 'A=$(ls -d /storage/emulated/0/AI*/ 2>/dev/null | head -1); cp ' + $file + ' "$A/' + $devFolder + '/shot-' + $i + '.png"'
        & adb -s $Device shell $sh | Out-Null
    } else {
        # 同 L62：中文路径不能过 adb shell，用 $shMumuDir 求出 $S 再拼 ASCII 前缀
        $sh = $shMumuDir + '; cp ' + $file + ' "$S/test/shot-' + $ts + '/shot-' + $i + '.png"'
        & adb -s $Device shell $sh | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { Write-Host "  ⚠ shot $i：cp 失败" }
    & adb -s $Device shell "rm -f $file" | Out-Null
}

# ---- 5) 健康检查：进程还在不在 + 有没有 FATAL ----
$pidOf = (& adb -s $Device shell "pidof $Package").Trim()
$crash = & adb -s $Device shell logcat -d -t 2000 | Select-String -Pattern 'FATAL EXCEPTION|AndroidRuntime.*FATAL|SIGKILL' | Select-Object -First 5

# ---- 6) 拉回本地留档 ----
# 关键坑（实测）：这台机器的 adb 客户端往「含中文的本地路径」pull 会报
# Illegal byte sequence（chcp/代码页都救不了，是 adb 本地文件系统编码的问题）。
# 所以先 pull 到纯 ASCII 临时目录（dist\.shots-<ts>），再用 PowerShell
# （.NET API，中文路径没问题）把文件搬进 dist\<目标>\测试截图\<ts>\。
$distRoot = Split-Path $DistDir -Parent
$tmpLocal = Join-Path $distRoot ".shots-$ts"
New-Item -ItemType Directory -Force -Path $tmpLocal | Out-Null
if ($Target -eq 'flyme') {
    $mirror = "/data/local/tmp/shotpull-$ts"
    $sh = 'A=$(ls -d /storage/emulated/0/AI*/ 2>/dev/null | head -1); cp -r "$A/' + $devFolder + '" ' + $mirror
    & adb -s $Device shell $sh | Out-Null
    & adb -s $Device pull $mirror $tmpLocal | Out-Null
} else {
    # 实测这台机器 adb pull 能吃下含中文的「设备侧」路径（本地落点这边仍是纯 ASCII 临时目录）；
    # 万一以后编码再串味，就退回 flyme 同款方案：先 cp 到 /data/local/tmp 的 ASCII 中转目录再 pull。
    & adb -s $Device pull $devFolder $tmpLocal | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Get-ChildItem $tmpLocal -Recurse -File -ErrorAction SilentlyContinue)) {
        $sh = $shMumuDir + '; rm -rf ' + $mirror + '; cp -r "$S/test/shot-' + $ts + '" ' + $mirror
        & adb -s $Device shell $sh | Out-Null
        & adb -s $Device pull $mirror $tmpLocal | Out-Null
    }
}
$localDir = Join-Path $DistDir '测试截图'
$runDir = Join-Path $localDir $ts
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
# adb pull 一个目录会把目录本身建到目标下（$tmpLocal\<目录名>\*.png），
# 直接 Move-Item $tmpLocal\* 会把整个子目录搬进留档目录、截图多一层。
# 这里先把子目录里的文件提上来，再统一搬运。
Get-ChildItem $tmpLocal -Directory | ForEach-Object {
    Get-ChildItem $_.FullName -File | Move-Item -Destination $tmpLocal -Force
    Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
}
Move-Item (Join-Path $tmpLocal '*') $runDir -Force -ErrorAction SilentlyContinue
Remove-Item $tmpLocal -Recurse -Force -ErrorAction SilentlyContinue

# ---- 7) 自动清理设备侧（除非 -Keep） ----
if ($Target -eq 'flyme') {
    & adb -s $Device shell "rm -rf '/data/local/tmp/shotpull-$ts'" | Out-Null
} else {
    # 只有走了中文 pull 兜底时才会存在，顺手清掉
    & adb -s $Device shell "rm -rf '$mirror'" | Out-Null
}
if ($Keep) {
    Write-Host "[冒烟测试] -Keep 指定了，设备侧文件夹保留（$($Target) 目标下：$devFolder）"
} else {
    if ($Target -eq 'flyme') {
        $sh = 'A=$(ls -d /storage/emulated/0/AI*/ 2>/dev/null | head -1); rm -rf "$A/' + $devFolder + '"'
        & adb -s $Device shell $sh | Out-Null
    } else {
        # 只删 shot-<ts> 目录，$S/test 目录本身保留（用户要求：test 留着）。
        # 关键坑 2：MuMu 共享文件夹是宿主机映射进来的 FUSE 挂载，toybox 的 `rm -rf` 删非空目录
        # 会在 rmdir 阶段撞 ETXTBSY（"Text file busy"：遍历目录用的 fd 还开着就 rmdir），
        # 连跑两次 rm -rf 同样删不掉。实测可行：先 rm -f 掉目录里的文件，再单起一个 rmdir 删空目录。
        $sh = $shMumuDir + '; rm -f "$S/test/shot-' + $ts + '"/* 2>/dev/null; rmdir "$S/test/shot-' + $ts + '" 2>/dev/null'
        & adb -s $Device shell $sh | Out-Null
    }
    Write-Host "[冒烟测试] 设备侧截图已清理（本地留档：$runDir）"
}

# ---- 8) 结论 ----
Write-Host ''
if (-not $pidOf) {
    Write-Host '  ⚠ 进程不在：App 可能崩溃或没起来，看上面 logcat 摘要'
    if ($crash) { Write-Host '  logcat 命中：'; $crash | ForEach-Object { Write-Host "    $($_.Line)" } }
    Write-Host '[冒烟测试] 结果：FAIL（App 未运行）'
    exit 1
}
if ($crash) {
    Write-Host '  ⚠ 日志里有崩溃痕迹：'
    $crash | ForEach-Object { Write-Host "    $($_.Line)" }
}
Write-Host "[冒烟测试] 结果：PASS（pid=$pidOf，截图已存 $runDir）"
exit 0
