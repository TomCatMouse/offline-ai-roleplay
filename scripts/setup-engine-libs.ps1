# setup-engine-libs.ps1 —— 骁龙引擎库自检 / 获取提示
#
# 背景：models/android-snapdragon/jniLibs/arm64-v8a 下的 27 个引擎库不入库（体积 246 MB，
#       且含受厂商许可约束的高通闭源库）。构建真机包前需要先把它们准备好。
# 用法：powershell -File scripts\setup-engine-libs.ps1
#       （若提示「未对文件进行数字签名/禁止运行脚本」，改用：
#         powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-engine-libs.ps1）
#       退出码 0 = 齐全；1 = 有缺失（会打印获取方式）
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$dir  = Join-Path $root "models\android-snapdragon\jniLibs\arm64-v8a"

# 期望清单：27 个文件，缺任何一个都可能导致真机引擎装载失败
$expected = @(
    # ---- 本项目按 armv8.2-a+dotprod 自编（16 个 + server）----
    "libggml.so", "libggml-base.so", "libggml-cpu.so", "libggml-opencl.so", "libggml-rpc.so",
    "libllama.so", "libllama-common.so", "libllama-server-impl.so", "libllama-server.so",
    "libllama-batched-bench-impl.so", "libllama-bench-impl.so", "libllama-cli-impl.so",
    "libllama-completion-impl.so", "libllama-fit-params-impl.so", "libllama-perplexity-impl.so",
    "libllama-quantize-impl.so", "libmtmd.so", "libomp.so",
    # ---- 上游 llama.cpp Android snapdragon 包（Hexagon / 设备库）----
    "libggml-hexagon.so", "libggml-htp-v73.so", "libggml-htp-v75.so",
    "libggml-htp-v79.so", "libggml-htp-v81.so",
    "libcdsprpc.so", "libvmmem.so", "vndk_dsp_10.so",
    # ---- 厂商 OpenCL 实现（建议从目标设备提取）----
    "libOpenCL.so"
)

Write-Host ""
Write-Host "骁龙引擎库目录: $dir" -ForegroundColor Cyan

if (-not (Test-Path -LiteralPath $dir)) {
    Write-Host "  ✗ 目录不存在 —— 需要先准备引擎库" -ForegroundColor Red
    $present = @()
} else {
    $present = @(Get-ChildItem -LiteralPath $dir -File | Select-Object -ExpandProperty Name)
}

$missing = @($expected | Where-Object { $present -notcontains $_ })
$extra   = @($present | Where-Object { $expected -notcontains $_ })

Write-Host ("  期望 " + $expected.Count + " 个 ｜ 实际 " + $present.Count + " 个 ｜ 缺失 " + $missing.Count + " 个 ｜ 多余 " + $extra.Count + " 个")

if ($present.Count -gt 0) {
    $sum = (Get-ChildItem -LiteralPath $dir -File | Measure-Object -Property Length -Sum).Sum
    Write-Host ("  合计体积: " + [Math]::Round($sum / 1MB, 1) + " MB（期望约 246 MB）")
}

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "缺失文件：" -ForegroundColor Yellow
    $missing | ForEach-Object { Write-Host ("    - " + $_) }
}

if ($extra.Count -gt 0) {
    Write-Host ""
    Write-Host "多余文件（不影响构建，但请确认不是旧版本残留）：" -ForegroundColor DarkYellow
    $extra | ForEach-Object { Write-Host ("    + " + $_) }
}

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "获取方式（自行编译，完整配方见 docs/android-armv8.2-engine-rebuild.md）：" -ForegroundColor Cyan
    Write-Host "  1) 18 个 —— 自编 16 个引擎库（§1 源码准备 → §2 构建 → §3 自检 → §4 入库；"
    Write-Host "             切记 §5.10：libggml.so 必须带 Hexagon 注册宏）"
    Write-Host "            + 改名后的 libllama-server.so + 取自 NDK clang 19 的 libomp.so"
    Write-Host "  2)  8 个 —— 上游 llama.cpp Android snapdragon 预编译包里的 libggml-hexagon.so、"
    Write-Host "             libggml-htp-v73/75/79/81.so、libcdsprpc.so、libvmmem.so、vndk_dsp_10.so"
    Write-Host "             （CPU / OpenCL / llama 系列库务必用自编产物，原因见 §5.14）"
    Write-Host "  3)  1 个 —— 从目标设备提取：adb pull /vendor/lib64/libOpenCL.so"
    Write-Host "             （厂商实现，受其自身许可约束，再分发前请自行确认）"
    Write-Host ""
    exit 1
}

Write-Host ""
Write-Host "  ✓ 引擎库齐全，可以构建真机包：gradlew.bat :app:assemblePhysRelease" -ForegroundColor Green
exit 0
