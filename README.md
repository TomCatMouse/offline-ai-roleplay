# AI 角色扮演（离线本地推理）

完全离线的 Android 角色扮演 App：角色卡、多轮对话、长期记忆、手机上本地跑 GGUF 大模型。

## 功能概览

- 角色卡：姓名、性格、说话风格、背景故事、开场白、标签与稀有度，支持 AI 批量补全
- 多轮对话：流式输出、停止生成、按角色区分「思考中」、剧情线自定义名称
- 长期记忆：从对话里按确定性规则抽取事件 / 偏好 / 事实，可在记忆页逐条查看与编辑
- 本地推理：内置骁龙 llama.cpp 引擎，模型放在手机里，全程不联网
- 数据自主：JSON 整体导出 / 导入备份，可清空全部对话与记忆

## 推理设备支持

- 高通骁龙处理器（含 Adreno GPU、Hexagon NPU）：部分支持CPU、GPU、NPU三档推理，优先顺序为NPU→GPU→CPU。引擎起不来时 App 会说明原因并自动回退到下一档；实测魅族 21 Pro（SM8650）三档全部可用（4B-Q4：NPU 生成约 11.5 tokens/s、GPU 7.6、CPU 8.1），红米 K40（888，DSP 只有 v68）NPU 档提示后回退、CPU 档 4B-Q4 生成约 5～6 tokens/s。
- 其它处理器：仅支持 CPU 推理，速度较慢

## 环境要求

- Windows 系统（打包脚本为 PowerShell 与批处理；Linux 或 macOS 需自行安装 Gradle 8.2）
- JDK 17
- Android SDK：compileSdk 34、minSdk 26、targetSdk 34
- Gradle 8.2（仓库自带 wrapper，无需另装）、AGP 8.2.0、Kotlin 1.9.20
- 首次构建需要联网下载依赖

## 引擎库准备（构建真机包前必做）

真机包内置的骁龙引擎库（`models/android-snapdragon/jniLibs/arm64-v8a`，共 27 个文件 / 约 246 MB）
**不进本仓库** —— 其中既有本项目自编的产物，也有来自上游与设备的高通闭源库，体积与许可都不适合入库。
缺了它们 `:app:assemblePhysRelease` 会在打包阶段提示缺少 jniLibs。

两种取得方式：

1. **下载现成压缩包（最快）**：从 [Releases](https://github.com/TomCatMouse/offline-ai-roleplay/releases/latest)
   下载 `engine-libs-arm64-v8a-b11558.zip`（59 MB），解压到项目根目录 —— 解压后即
   `models/android-snapdragon/jniLibs/arm64-v8a/` 下的 27 个 `.so`（sha256 以 `6E9B6F4C` 开头）。
2. **自己编译（正统做法）**：完整配方、自检命令与踩坑记录见
[`docs/android-armv8.2-engine-rebuild.md`](docs/android-armv8.2-engine-rebuild.md)：

1. **自编 16 个引擎库（最关键的一步）**：官方预编译包是 ARMv8.6 口径，在骁龙 855/865/870/888 上一启动就 SIGILL，
   必须自己编。按文档 **§1 源码准备 → §2 配置与构建（`armv8.2-a+dotprod`）→ §3 产物自检
   → §4 入库规则** 走一遍，把产物连同改名后的 `libllama-server.so`、取自 NDK clang 19 的 `libomp.so`
   一起放进 `jniLibs/arm64-v8a/`（本步合计 **18 个**）。
   ⚠️ 别漏 **§5.10**：`libggml.so` 必须带 Hexagon 后端注册宏，否则 NPU 档在**所有机型**上都不可用。
2. **取 Hexagon skel 与高通设备库（8 个）**：从上游 llama.cpp 的 Android snapdragon 预编译包里只取这几个 ——
   `libggml-hexagon.so`、`libggml-htp-v73/75/79/81.so`、`libcdsprpc.so`、`libvmmem.so`、`vndk_dsp_10.so`。
   CPU / OpenCL / llama 系列库**务必**用第 1 步的自编产物，别用官方包里的（原因见 §5.14）。
3. **取厂商 OpenCL 实现（1 个）**：`adb pull /vendor/lib64/libOpenCL.so`，直接以 `libOpenCL.so` 放进 jniLibs。
   包内无需附带其它高通闭源驱动 —— 驱动本体留在设备上，由该实现内部走 sphal 命名空间加载（§5.1–§5.5）。
   注意：这是厂商实现，受其自身许可约束，**再分发前请自行确认**。

18 + 8 + 1 = 27 个齐全后自检：

```
powershell -File scripts\setup-engine-libs.ps1
```

若提示脚本被禁止运行，改用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-engine-libs.ps1`。
看到「期望 27 ｜ 缺失 0 ｜ 多余 0」即可构建真机包。

完整的从零构建步骤（环境要求、命令、常见问题）见 **[BUILD.md](BUILD.md)**。

> 只想先跑起来、不想折腾引擎？**默认包**（模拟器 / PC 引擎模式）完全不需要这些库：
> `gradlew.bat :app:assembleDebug`

## 构建步骤

1. 在项目根目录新建 `local.properties`，写入本机 SDK 路径：

   ```
   sdk.dir=D\:\\sdk
   ```

2. 真机包（内置骁龙引擎，只打 arm64-v8a）：

   ```
   gradlew.bat :app:assemblePhysRelease
   ```

3. 默认包（模拟器专用，测试环境：win11+ 64 位操作系统 + x64 处理器；包内不打包手机端推理引擎，推理由 PC 上的 llama-server 完成；服务器地址填 PC 的局域网 IP，例如 192.168.1.100）：

   ```
   gradlew.bat :app:assembleDebug
   ```

4. 产物位置：`app\build\outputs\apk\physRelease\` 与 `app\build\outputs\apk\debug\`

5. 一键脚本（含自动安装与截图冒烟测试，可用 `-SkipTest` 跳过）：

   ```
   powershell -File scripts\build-phys.ps1      # 真机包
   powershell -File scripts\build-default.ps1   # 默认包（连 PC 引擎）
   ```

签名说明：正式版签名属性 `ROLEPLAY_KEYSTORE_FILE`、`ROLEPLAY_KEYSTORE_PASSWORD`、`ROLEPLAY_KEY_ALIAS` 放在 `~/.gradle/gradle.properties` 与密钥文件一起，均不进仓库；属性缺失时构建出的是未签名 APK，不影响编译通过。

## 目录说明

- `app/src/main`：默认包与真机包共用的主体代码（Compose 界面、Room 数据库、AI 服务）
- `app/src/phys`：真机包专属（本地引擎管理、所有文件访问权限、真机清单）
- `models/android-snapdragon/jniLibs/arm64-v8a`：骁龙引擎库共 27 个 —— 16 个按 `armv8.2-a+dotprod` 自编的引擎库、`libllama-server.so`（改名后的引擎可执行文件）、`libomp.so`（OpenMP 运行时）、上游 Hexagon skel 与高通设备库（`libggml-hexagon.so`、`libggml-htp-v*.so` 等）以及厂商 OpenCL 实现 `libOpenCL.so`。**该目录不入库**，构建前请先按上面「引擎库准备」取得
- `scripts`：打包、版本管理、发布、PC 端模型切换器等脚本
- `tools`：开发期使用的测试与测量脚本
- `asset`：图标与立绘素材

## 模型

- 把 `.gguf` 文件放进手机的 `/storage/emulated/0/AI角色扮演/模型/`，再回设置页点「重新扫描」
- 下载地址：https://pan.quark.cn/s/14ce44be5923
- 建议 Q4_0 及以上量化：Q4_0 约 2.4 GB、Q4_K_M 约 2.5 GB（项目实测在用）、Q5_K_M 约 2.9 GB、Q6_K 约 3.2 GB
- 6 至 8 GB 内存机型选 Q4_0 或 Q4_K_M，8 GB 以上可上 Q5_K_M 或 Q6_K
- App 需要「所有文件访问权限」才能扫描模型目录（Android 11 及以上）

## 发布流程

1. `scripts\pack-release.ps1`：按源码指纹自动递增版本号并打出正式包到 `dist\正式版\`
2. `scripts\publish-release.ps1`：创建附注标签与 GitHub Release，上传 APK（需要环境变量 `GITHUB_TOKEN`）

## 连通性自测

```
python test_http.py                       # 默认打 127.0.0.1（手机本地引擎）
set ROLEPLAY_HOST=192.168.1.100 && python test_http.py   # 打 PC 的局域网 IP
```

## 打赏支持

如果这个项目帮到了你，可以请作者喝杯奶茶 ☕

<details>
<summary><b>点击查看打赏码（微信 / 支付宝）</b></summary>

<br>

| 微信 | 支付宝 |
| --- | --- |
| [![微信收款码][qr-wechat]][qr-wechat] | [![支付宝收款码][qr-alipay]][qr-alipay] |

点一下图片可看大图 / 保存。收款码在仓库里**只存这一份**（`app/src/main/res/drawable/reward_qr_*.jpg`）：App 内打赏弹窗与这里引用的是同一张图，换码只需替换这两个文件。

</details>

<!-- 参考式定义：表格里的 [qr-wechat] / [qr-alipay] 指向下面两个路径，
     这样长路径在文中只出现一次，单元格不会被长 URL 撑爆 -->

[qr-wechat]: app/src/main/res/drawable/reward_qr_wechat.jpg
[qr-alipay]: app/src/main/res/drawable/reward_qr_alipay.jpg
