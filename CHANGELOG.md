# 变更日志

按版本记录功能更新与 BUG 修复。`scripts/publish-release.ps1` 会取「## v版本号」这一小节
（直到下一个 `## ` 为止）作为 Release 正文与附注标签里的「变更日志」内容，新增版本加一段即可。

> 想追每个修复背后的根因、复现步骤与验证数据，见 [`docs/android-armv8.2-engine-rebuild.md`](docs/android-armv8.2-engine-rebuild.md)。

## v1.0.4

> 说明：本版没有改动 App 的功能代码，APK 与 1.0.3 在功能上完全一致；改动集中在
> 构建守卫、CI、项目文档与发布流程。仓库已转为公开，这一版是把"开源后别人第一次
> 构建"最容易踩的坑补掉。

### 功能优化

- **新增真机包构建守卫**：`engine/android-snapdragon/jniLibs/arm64-v8a` 下引擎库不足 27 个时，构建会在 `:app:prePhysBuild` 阶段直接报错并给出三步修复指引。此前 Gradle 对缺失的 `jniLibs` 目录不报错，会打出一个 `BUILD SUCCESSFUL` 但里面一个引擎字节都没有的「真机包」，装到手机上推理完全不可用且看不出原因
- **新增 GitHub Actions 工作流**：在 `windows-latest` 上构建默认包（默认包不需要引擎库），并自检「缺引擎库时真机包必须被守卫拦下、且不产出任何废包」
- **补齐开源项目文件**：`CONTRIBUTING.md`、`SECURITY.md`、Issue 模板（Bug 报告 / 功能建议）与 PR 模板；仓库社区健康度由 42% 提升到 85%，并开启私密漏洞上报入口
- **版本号规则明确化**：版本号只在**发布成功之后**递增（新增 `scripts/bump-version.ps1`），发布之前的任意次本地打包不再消耗版本号

### BUG修复

- **修复打包脚本「构建失败却当成成功」**：本仓库的 `gradlew.bat` 在构建失败时返回的退出码依然是 0（官方原版 wrapper 自身的行为，直接问 `cmd` 内层 `%ERRORLEVEL%` 同样是 0），导致 `scripts/pack-release.ps1`、`build-default.ps1`、`build-phys.ps1` 里的 `if ($LASTEXITCODE -ne 0) { throw ... }` 形同虚设 —— 构建失败时脚本会继续执行，把上一版残留的 APK 当成新产物拷进 `dist`，并把 `version-state.json` 记为打包成功。现改为校验构建输出里是否出现 `BUILD SUCCESSFUL`
- **修复 `audit-secrets.ps1` 会在 Windows 凭据管理器留下残留**：远端探测改为 `git -c credential.helper= ls-remote` 并关闭终端提示，避免系统级 GCM 把这次认证写成一条 `git:https://github.com` 记录

## v1.0.3

### 功能优化

- **全新安装 / 首次启动就自动创建模型目录**：App 启动时幂等创建 `/storage/emulated/0/AI角色扮演/模型`
- **授权后立即生效，不再需要重启 App**：从系统「所有文件访问权限」页授权返回后，会立刻把模型目录补建出来
- 目录创建失败不再静默：日志会打出 `W/PhysEngine` 并点名原因（多半是缺「所有文件访问权限」）

### BUG修复

- **修复模型目录不自动创建**：`File.mkdirs()` 失败不抛异常、只返回 `false`，而返回值无人检查 —— 全新安装尚未授权时创建失败**完全静默**，日志与界面都看不出来，现象上就像「这个功能没了」
- **修复「授权后仍要重启 App 才会建目录」**：授权回调走的是 `rescan()`，而它原来只扫描、不建目录；现在 `attach()` 与 `rescan()` 都会调用幂等的 `ensureModelDirs()`，授权后当场生效

## v1.0.2

### 功能优化

- **推理引擎升级到 llama.cpp b11558**：真机端 16 个引擎库按 `armv8.2-a+dotprod` 从源码重编（官方预编译产物为 ARMv8.6，在骁龙 855/865/870/888 上一启动就 SIGILL），PC / 模拟器端同步升级
- **真机 GPU(OpenCL) 装载链路修复**：修掉「引擎子进程看不到 `/vendor/lib64`」「随包 ICD loader 不兼容」「后端按 OpenCL 3.0 编译、驱动只有 2.0」三个根因 —— Adreno 7xx/8xx 机型可用 GPU 推理
- **真机设置页移除「自定义启动参数」**：启动参数固定由 App 按档位装配（CPU `-ngl 0 -dev none` / GPU `-ngl 999 -dev GPUOpenCL` / NPU `--device HTP0`）
- 设备档位失败自动回退（NPU → GPU(OpenCL) → CPU，最多 3 次），已知不可用的后端直接跳过并在界面说明原因
- 按机型自动选择 Hexagon HTP 代际与 DSP 域；NPU 会话失败时自动降档重试（v79→v75→v73），仍失败才回退
- 引擎启动全量诊断日志：机型/SoC、HTP 代际与选择依据、DSP 域、库路径、OpenCL ICD 来源、最终生效参数
- 模型目录自动识别：任意合规 `.gguf` 放进模型目录即可被扫描、列出并加载
- 角色推理状态按角色隔离；跨角色发送时整条消息不再发出，改为提示「暂不支持同步会话」
- dock 栏固定在底部（键盘弹出时不再收起）；打赏横幅与弹窗改用随包矢量奶茶图标
- 设置页新增打赏入口（内置微信 / 支付宝收款码）

### BUG修复

- **修复 NPU 档「有设备但会话打不开」**：`ADSP_LIBRARY_PATH` 必须写成**单个目录** —— 多目录拼接会让 DSP 拼接出不存在的路径、找不到 Hexagon skel 并报 `0x80000406`；修复后骁龙 8 Gen 3 机型 NPU 档实测可用
- **修复 NPU 档在全部机型上都无法启动的问题**：重编引擎时漏掉了 Hexagon 后端注册宏，设备列表里没有 HTP 设备
- 修复骁龙 855/865/870/888 等 ARMv8.2/8.3 机型上真机引擎「一启动就退出」（SIGILL）的问题
- 修复「明明选了 CPU 档也启动报错」，以及 GPU 档报 `platform IDs not available` 的问题
- 修复 NPU 加载失败即整体退出、以及把「本机没有可用 HTP 设备」误报成「启动参数错误」的问题
- 补齐引擎依赖的 OpenMP 运行时 `libomp.so`；修复 NPU 降档重试日志写错代际的问题

## v1.0.1

### 功能优化

- 骁龙与 PC 端 llama.cpp 引擎升级到 b11523（commit e94acad85）
- 骁龙引擎按上游原样全量入库（26 库）；全部 AARCH64 引擎库 16 KB 内存页对齐，满足 Android 15 及以上要求
- 参数兼容性已按 b11523 逐项复核：量化、推理开关、采样与批处理相关参数全部存在

### BUG修复

- 本版以引擎升级为主，无独立修复项
