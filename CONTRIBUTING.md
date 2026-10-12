# 贡献指南

感谢你愿意改进这个项目 🙏 提 PR 之前，请先花两分钟读一遍下面几条。

## 环境准备

- JDK 17、Android SDK（compileSdk 34 / minSdk 26 / targetSdk 34）
- 仓库自带 Gradle wrapper（8.2），**不需要**另外安装 Gradle
- 在根目录新建 `local.properties` 写入本机 SDK 路径（注意转义写法）：

```properties
sdk.dir=D\:\\sdk
```

完整步骤见 [BUILD.md](BUILD.md)。

## 提交前请自测

**默认包不需要引擎库**，改完 App 逻辑至少保证它通过：

```bat
gradlew.bat :app:assembleDebug
```

## 关于真机包（`:app:assemblePhys` / `:app:assemblePhysRelease`）

- 真机包依赖 `engine/android-snapdragon/jniLibs/arm64-v8a` 下的 **27 个**引擎库
- 该目录**不入库**；请不要在 PR 里添加任何 `.so`、`.gguf`、`.apk`、`.zip` 或密钥文件
- 引擎库缺失时构建会**明确报错**（这是刻意设计的守卫），取得方式见 README 的「引擎库准备」

## 提交信息与 PR

- 提交信息用「类型(范围): 说明」，例如 `fix(engine): 修复 NPU 档会话打不开`
  - 类型可用 `feat` / `fix` / `docs` / `chore` / `refactor` / `perf` / `test` / `build`
- 一个 PR 只做一件事；涉及界面的改动请附截图或录屏
- 不要提交 `dist/`（本地打包产物）、`local.properties`、签名密钥或模型文件
- **不要修改 `versionName` / `versionCode`**：版本号由维护者在发布成功后统一递增

## 报告问题

- 缺陷请用 [Bug 报告模板](.github/ISSUE_TEMPLATE/bug_report.yml)，尽量把机型、推理档位和日志填全
- 安全相关问题请走 [SECURITY.md](SECURITY.md)，**不要**开公开 issue
