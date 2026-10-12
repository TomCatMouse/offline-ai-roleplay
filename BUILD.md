# 构建说明

从零构建出可安装的 APK。**真机包必须先做第 1 步；默认包可跳过第 1 步直接看第 2 步。**

## 1. 准备引擎库（只有真机包需要）

真机包内置骁龙推理引擎，引擎库（`engine/android-snapdragon/jniLibs/arm64-v8a`，27 个文件 / 246 MB）
**不进仓库** —— 既有本项目自编的产物，也有厂商闭源库，体积与许可都不适合入库。缺了它构建会在打包阶段提示缺少 jniLibs。

### 方式一：下载现成压缩包（最快）

1. 到 [Releases](https://github.com/TomCatMouse/offline-ai-roleplay/releases/latest) 下载
   `engine-libs-arm64-v8a-b11558.zip`（59 MB，sha256 以 `6E9B6F4C` 开头、以 `DDB4` 结尾）
2. 在项目根目录新建 `engine\android-snapdragon\`，把压缩包解压进去（包内就是 `jniLibs/arm64-v8a/`）——
   解压后应为 `engine/android-snapdragon/jniLibs/arm64-v8a/` 下的 27 个 `.so`
3. 自检：`powershell -File scripts\setup-engine-libs.ps1`，看到「期望 27 ｜ 缺失 0 ｜ 多余 0」就算就绪

### 方式二：自己编译（正统做法）

引擎库是 llama.cpp b11558 的真机端产物。官方预编译包按 ARMv8.6 编译，在骁龙 855/865/870/888 上会执行到不支持的指令直接 SIGILL，所以必须按本项目的配方自己编。完整步骤、自检命令与踩坑记录见
[`docs/android-armv8.2-engine-rebuild.md`](docs/android-armv8.2-engine-rebuild.md)（下称《引擎重编配方》），要点：

1. 按《引擎重编配方》**第 1 节**准备源码与补丁、**第 2 节**用 `armv8.2-a+dotprod` 配置构建、**第 3 节**做产物自检（新指令必须为 0、16 KB 页对齐）、**第 4 节**按入库规则收尾
2. 产物 + 改名后的 `libllama-server.so` + NDK clang 19 的 `libomp.so` 放进 `jniLibs/arm64-v8a/`，共 **18 个**
3. 从上游 llama.cpp 的 Android snapdragon 预编译包里取 **8 个** Hexagon 与设备库：`libggml-hexagon.so`、`libggml-htp-v73/75/79/81.so`、`libcdsprpc.so`、`libvmmem.so`、`vndk_dsp_10.so`（CPU / OpenCL / llama 系列库**务必**用第 1 节的产物，别用官方包里的，原因见《引擎重编配方》第 5 节第 14 小节）
4. 从目标设备取 **1 个**厂商 OpenCL 实现：`adb pull /vendor/lib64/libOpenCL.so`（厂商实现受其自身许可约束，再分发前请自行确认）
5. 18 + 8 + 1 = 27 个后自检，同上

> 关键坑：《引擎重编配方》**第 5 节第 10 小节** —— `libggml.so` 必须带 Hexagon 后端注册宏，否则 NPU 档在**所有机型**上都不可用。

## 2. 构建

环境：Windows；JDK 17；Android SDK（compileSdk 34 / minSdk 26 / targetSdk 34）；仓库自带 Gradle wrapper（8.2），无需另装；首次构建需要联网下载依赖。

1. 在项目根目录新建 `local.properties`，写入本机 SDK 路径（注意转义写法）：

   ```
   sdk.dir=D\:\\sdk
   ```

2. **真机包**（内置引擎，只打 arm64-v8a，需先完成第 1 步）：

   ```
   gradlew.bat :app:assemblePhysRelease
   ```

3. **默认包**（模拟器 / 连 PC 引擎用，不需要引擎库）：

   ```
   gradlew.bat :app:assembleDebug
   ```

4. 产物位置：`app\build\outputs\apk\physRelease\`、`app\build\outputs\apk\debug\`

签名：正式版签名属性 `ROLEPLAY_KEYSTORE_FILE`、`ROLEPLAY_KEYSTORE_PASSWORD`、`ROLEPLAY_KEY_ALIAS` 放在
`~/.gradle/gradle.properties` 并与密钥文件放在一起，均不进仓库；不配置时构建出未签名 APK，不影响编译通过。

## 3. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 打包时提示缺少 jniLibs | 第 1 步没做或 27 个 `.so` 不齐 —— 跑 `scripts\setup-engine-libs.ps1` 看缺哪个 |
| 脚本提示「未对文件进行数字签名」 | 加执行策略参数，如 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-engine-libs.ps1` |
| 真机上引擎一启动就退出 | 用的是官方预编译引擎库（ARMv8.6 指令）—— 换成本项目自编产物，见《引擎重编配方》第 5 节第 14 小节 |
| NPU 档「有设备但会话打不开」 | 见《引擎重编配方》第 5 节第 15 小节：`ADSP_LIBRARY_PATH` 必须是单个目录 |
| `sdk.dir` 报错 | 路径要写成 `D\:\\sdk` 这种转义形式 |
| 想跳过图标/引擎相关的开发期工具 | `tools/`、`scripts/` 下的测试脚本与构建无关，可忽略 |

更多原理与排障过程见 [`docs/android-armv8.2-engine-rebuild.md`](docs/android-armv8.2-engine-rebuild.md)。
