# 真机引擎（llama.cpp）ARMv8.2 重编配方

> 为什么需要这份文档：官方预编译的 Android 引擎库是按 **ARMv8.6-a+i8mm** 编译的，
> 在骁龙 855 / 865 / 870 / 888 等 **ARMv8.2 / 8.3** 机型上会执行到 `stlur` / `smmla` 这类
> 新指令直接 **SIGILL**（日志表现为 `Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC)`，
> App 侧显示「引擎进程结束 code=132」）。
>
> **当前入库状态（b11558 / 2026-10-12）**：`models/android-snapdragon/jniLibs/arm64-v8a` **共 27 个文件**
> = 按本配方**全量自编的 16 个引擎库**（§2 / §4）+ `libllama-server.so`（改名的 `bin/llama-server`）
> + `libomp.so`（自编 CPU 后端的 OpenMP 运行时，取自 NDK clang 19）
> + 上游 Hexagon skel 与高通设备库（`libggml-hexagon.so`、`libggml-htp-v73/75/79/81.so`、
>   `libcdsprpc.so`、`libvmmem.so`、`vndk_dsp_10.so`）+ 随包的厂商 OpenCL 实现 `libOpenCL.so`。
> **不要再走「官方包打底、只重编个别库」这条路**（§5.13 试过、当天被 §5.14 打回）：官方 CPU / OpenCL / llama
> 系列库都是 **ARMv8.6** 口径，在骁龙 888 上实测 `stlur` **SIGILL**（§5.14），`libggml-cpu.so` 里还有
> `smmla×420`；官方包**只能**提供 Hexagon skel 与设备库那部分。
> §5.x 历史小节记录 b11523 / b11550 时代的排障结论，方法仍然适用。
> 命令中的 `<repo>` / `<tmp>` / `<AndroidSDK>` 是占位符，分别替换为你的仓库根目录、临时目录与 Android SDK 根目录。

## 0. 结论速查

| 项目 | 值 |
| --- | --- |
| 上游 tag | `b11558`（commit `c83f3058b`）；原配方基于 `b11550`（`abee0c847`），配方与 tag 无关 |
| NDK | `28.2.13676358` |
| ABI / 平台 | `arm64-v8a` / `android-21`（配方默认；本次 b11558 用 `android-26`：只编 `ggml` 目标或不需要 §1.1 的 `llama-mmap.cpp` 垫片时可以直接抬到 26，minSdk = 26，两条路都安全） |
| CPU 基线 | `armv8.2-a+dotprod` |
| 构建类型 | `Release` |
| 关键开关 | `GGML_OPENCL=ON`、`GGML_OPENCL_EMBED_KERNELS=ON`、`GGML_OPENCL_USE_ADRENO_KERNELS=ON`、`GGML_OPENCL_TARGET_VERSION=200`（**不能是 300**，见 §5.3）、`GGML_OPENMP=ON`、`GGML_RPC=ON`（`libllama-server.so` 直接依赖 `libggml-rpc.so`，漏了会 CANNOT LINK）、`GGML_HEXAGON=OFF`、`LLAMA_BUILD_SERVER=ON` |
| 产物 | **全量自编（当前入库走这条）**：`bin/` 下的 16 个 `.so` + `llama-server`（改名 `libllama-server.so`）+ NDK 的 `libomp.so`；官方包只取 Hexagon skel / 高通设备库 / `libOpenCL.so`（§4、§5.14） |
| NPU（Hexagon / HTP） | **可用**，但有两个硬前提：① `libggml.so` 必须带 Hexagon 注册（§5.10）；② `ADSP_LIBRARY_PATH` 只能是**单个目录**，且 skel 实物要落在该目录（含 `cdsp/` 子目录）—— 否则 DSP 找不到 skel 报 `0x80000406`（§5.15） |

## 1. 源码准备

```powershell
git -C <repo>\llama.cpp worktree add <tmp>\llama-b11550 b11550
```

### 1.1 必须打的一个补丁：`posix_madvise`

`src/llama-mmap.cpp` 用了 `posix_madvise` / `POSIX_MADV_*`。bionic 没有这个函数，而 NDK 的
`<sys/mman.h>` 只在 `__INTRODUCED_IN(23)` 之后才声明它 —— 由于本配方按 `android-21` 编译，
声明是**隐藏**的，于是报 `use of undeclared identifier 'posix_madvise'`（上游 Android 构建同样卡在这）。

Linux 上 `POSIX_MADV_*` 与 `MADV_*` 取值一致，所以直接垫一层即可。插到
`struct llama_mmap::impl {` **之前**：

```cpp
// LLAMA_ANDROID_MADVISE_SHIM: bionic has no posix_madvise(); MADV_* equals POSIX_MADV_* on Linux
#if defined(__ANDROID__)
#ifndef POSIX_MADV_NORMAL
#define POSIX_MADV_NORMAL     MADV_NORMAL
#define POSIX_MADV_RANDOM     MADV_RANDOM
#define POSIX_MADV_SEQUENTIAL MADV_SEQUENTIAL
#define POSIX_MADV_WILLNEED   MADV_WILLNEED
#define POSIX_MADV_DONTNEED   MADV_DONTNEED
static inline int posix_madvise(void * addr, size_t len, int advice) {
    return madvise(addr, len, advice);
}
#endif
#endif
```

> 备选做法：把 `ANDROID_PLATFORM` 提到 `android-23` 以上，声明就会可见、无需打补丁。
> 本项目 minSdk = 26，两种做法都安全。这里保留垫片是因为它同时兼容更老的 API。

### 1.2 OpenCL 头文件

NDK 28 不带 `CL/cl.h`，需要自备 Khronos 头：

```powershell
git clone --depth 1 https://github.com/KhronosGroup/OpenCL-Headers <tmp>\OpenCL-Headers
```

链接期用包内那个**厂商 OpenCL 实现本体**（`models\android-snapdragon\jniLibs\arm64-v8a\libOpenCL.so`，115112 B，直接取自设备 `/vendor/lib64/` —— 见 §5.2，**不是**高通那层 ICD loader），
`find_package(OpenCL)` 在交叉编译时找不到它，必须显式传 `OpenCL_INCLUDE_DIR` / `OpenCL_LIBRARY`。

## 2. 配置与构建

```powershell
$ndk = "<AndroidSDK>\ndk\28.2.13676358"
$wt  = "<tmp>\llama-b11550"
$jni = "<repo>\models\android-snapdragon\jniLibs\arm64-v8a"

cmake -S $wt -B "$wt\build-android-v82" -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" `
  -DCMAKE_BUILD_TYPE=Release `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 `
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod `
  -DGGML_NATIVE=OFF -DGGML_CPU_KLEIDIAI=OFF -DGGML_LLAMAFILE=ON `
  -DGGML_OPENMP=ON -DGGML_OPENCL=ON -DGGML_RPC=ON `
  -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON -DGGML_OPENCL_TARGET_VERSION=200 `
  -DGGML_HEXAGON=OFF -DLLAMA_BUILD_SERVER=ON `
  -DOpenCL_INCLUDE_DIR="<tmp>/OpenCL-Headers" `
  -DOpenCL_LIBRARY="$jni/libOpenCL.so"

$env:PYTHONUTF8 = "1"; $env:PYTHONIOENCODING = "utf-8"   # 否则 OpenCL kernel 生成会因 GBK 解码失败
cmake --build "$wt\build-android-v82" --parallel 8
```

配置阶段应当看到：

```
-- HAVE_DOTPROD   - Success
-- HAVE_MATMUL_INT8 - Failed          # 关键：i8mm 必须失败，它属于 ARMv8.6
-- Adding CPU backend variant ggml-cpu: -march=armv8.2-a+dotprod
```

## 3. 产物自检（入库前必做）

```powershell
$bin = "<AndroidSDK>\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin"
# 1) 新指令必须为 0（stlur/ldapur 属 ARMv8.4，smmla 属 ARMv8.6）
foreach ($n in @("libggml-cpu.so","libllama.so","libggml-opencl.so")) {
  $d = & "$bin\llvm-objdump.exe" -d "$jni\$n"
  "$n stlur=" + @($d | Select-String '\bstlur\b').Count +
        " ldapur=" + @($d | Select-String '\bldapur\b').Count +
        " smmla=" + @($d | Select-String '\bsmmla\b').Count
}
# 2) 依赖闭包：除系统库外只应依赖 libomp.so / libggml-base.so 等包内文件
& "$bin\llvm-readelf.exe" -d "$jni\libggml-cpu.so" | Select-String 'NEEDED|SONAME'
# 3) 16 KB 页对齐（Android 15+ 要求）：LOAD 段 Align 应为 0x4000
& "$bin\llvm-readelf.exe" -l "$jni\libggml-base.so" | Select-String 'LOAD'
```

## 4. 入库规则（jniLibs 组装）

**全量自编路径**下从 `build-android-v82/bin/` 复制这 16 个（b11523 时代在 `lib/`，b11550 改到了 `bin/`）—— **当前 b11558 入库走的就是这条路**，另外还要：① 把 `bin/llama-server` 复制成 `libllama-server.so`；② 按 §5.10 单独重编一次 `ggml` 目标补回 Hexagon 注册（`GGML_HEXAGON=OFF` 会把注册宏一起关掉）；③ 从 NDK clang 19 取 `libomp.so` 一并入库（自编 CPU 后端依赖它）：

```
libggml.so  libggml-base.so  libggml-cpu.so  libggml-opencl.so  libggml-rpc.so
libllama.so  libllama-common.so  libllama-server-impl.so  libmtmd.so
libllama-batched-bench-impl.so  libllama-bench-impl.so  libllama-cli-impl.so
libllama-completion-impl.so  libllama-fit-params-impl.so
libllama-perplexity-impl.so  libllama-quantize-impl.so
```

另有两点：

- `llama-server` 是 **PIE 可执行文件**（`Type: DYN`），必须复制成 `libllama-server.so`
  放在同一目录，App 才能按 `nativeLibraryDir/libllama-server.so` 直接 exec。
- **必须补 `libomp.so`**：本配方 `GGML_OPENMP=ON`，`libggml-cpu.so` 会依赖它
  （只用到 `omp_get_num_threads` / `omp_get_thread_num` 两个符号），而 Android 系统里没有。
  取自 NDK：`$ndk\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\19\lib\linux\aarch64\libomp.so`。
  缺了它引擎会以 `CANNOT LINK EXECUTABLE ... library "libomp.so" not found` 秒退。
  （官方预编译产物不含 OpenMP 依赖，所以当前 b11558 入库里**没有**这个库。）

**不能动**的 8 个（直接取自官方 `llama-b11558-bin-android-arm64-snapdragon.tar.gz` 的 `lib/`，
原配方取的是 b11550 包；Hexagon 部分需要高通 Hexagon SDK 才能编，只能沿用上游产物；同一 tag 所以 ABI 一致）：

```
libggml-hexagon.so  libggml-htp-v73/75/79/81.so
libcdsprpc.so  libvmmem.so  vndk_dsp_10.so
```

> 上游那几个 `libggml-cpu.so` 是按 **ARMv8.6** 编的（实测 `smmla=420`、`stlur=4`），
> 直接拿来用会在骁龙 888 上 SIGILL，所以 CPU/OpenCL/llama 这 16 个必须按本文重编。
> `libggml-hexagon.so` 里有 2 条 `stlur` + 1 条 `ldapur`（上游产物，无法重编），
> 但 HTP 后端在注册不出设备时根本不会走到那段代码，实测无 SIGILL —— b11523 时代同样如此，不是回归。

`lib/gcc.so` 之类只是 Hexagon DSP 侧 skel 的依赖（跑在 DSP 上），不需要补进包。
另外 `libOpenCL.so`（**厂商 OpenCL 实现本体**，115112 B，取自设备 `/vendor/lib64/`；**不是**高通那层 ICD loader，见 §5.2）必须留在包里：
它是引擎可执行文件的**链接期依赖**，删掉就会 `CANNOT LINK EXECUTABLE ... library "libOpenCL.so" not found`。

## 5. 真机后端排障史（GPU / NPU / DSP）

> ⚠️ 本节按**时间顺序**排列，早期结论可能已被后续小节推翻（§5.11 被 §5.15 推翻、§5.13 被 §5.14 推翻）。
> 引用任何一条结论前，先确认它后面有没有更新的小节。

让 App 派生出的引擎子进程用上 Adreno GPU，前面几个"装载期"的坑已经全部踩通
（5.1~5.5，**整条链路不需要在包里附带任何高通闭源驱动**）。
但 5.7 记录的最后一个坑来自驱动编译器自身，**在 Redmi K40（SM8350 / Adreno 660 /
Android 14 / MIUI）上无法绕开**，因此本项目在那台机器上仍然只能用 CPU 档。

### 5.1 子进程看不到 /vendor/lib64

Android 12+ 上 App 派生出的引擎进程处于 `(default)` linker namespace：

```
E linker: library "/vendor/lib64/libOpenCL.so" ... is not accessible for the namespace:
  [name="(default)", ld_library_paths="/data/data/<pkg>/files/htplibs:<nativeLibraryDir>",
   default_library_paths="/system/lib64:/system_ext/lib64",
   permitted_paths="/system/lib64/hw:...:/vendor/framework:/vendor/app:/data:..."]
```

`/vendor/lib64` 不在 `permitted_paths` 里，所以设备路径上的厂商 OpenCL 库加载不到；
而 `nativeLibraryDir` 可以（引擎自己的 `libllama*` 就是这么起来的），
`ld_library_paths` 里那个 `files/htplibs` 也同样可用。
=> **厂商实现必须随包**，放进 `jniLibs/arm64-v8a/`。

### 5.2 别用高通那层 ICD loader

包里早期放的是高通魔改的 ICD loader（导出 `khrIcd*` 符号，可读字符串里有
`vendor/qcom/proprietary/graphics/adreno200/cb/src/loader/icd.c`）。它**不认**
`OCL_ICD_FILENAMES`，内部写死去找 `/system/vendor/lib64/egl`，结果平台数恒为 0：
日志只有一行 `E ggml_opencl: platform IDs not available.`，连它自己的
`failed to get extension function address clIcdGetPlatformIDsKHR` 都不打（说明它压根没去加载我们指定的 ICD）。

=> **直接用厂商实现本体当 `libOpenCL.so`**：它同时导出 147 个 `cl*` 公开 API
（`clGetPlatformIDs` / `clCreateContext` / `clEnqueueNDRangeKernel` / `clBuildProgram` ...）
与 `clIcdGetPlatformIDsKHR`，SONAME 也正好是 `libOpenCL.so`，本身就是完整实现，不需要 loader 层。

### 5.3 后端目标版本要和驱动对齐

本机 Adreno 驱动是 **OpenCL 2.0**。若后端按 3.0 编译，会直接引用 3.0 才有的
`clCreateBufferWithProperties`，装载期就失败：

```
F linker: CANNOT LINK EXECUTABLE ".../libllama-server.so":
  cannot locate symbol "clCreateBufferWithProperties" referenced by ".../libggml-opencl.so"
```

=> 用 `-DGGML_OPENCL_TARGET_VERSION=200` 重编后端。llama.cpp 自己用
`#if CL_TARGET_OPENCL_VERSION >= 300` 把 3.0 调用围起来了，降到 200 是官方支持的档位
（`ggml/src/ggml-opencl/CMakeLists.txt` 把该变量直接 `add_compile_definitions` 下去）。

### 5.4 复现命令

```powershell
$ndk   = "<AndroidSDK>\ndk\28.2.13676358"
$ninja = "<AndroidSDK>\cmake\3.22.1\bin\ninja.exe"
$jni   = "<repo>\models\android-snapdragon\jniLibs\arm64-v8a"

# 1) 从真机取厂商 OpenCL 实现，直接当包内 libOpenCL.so（115112 B）
adb pull /vendor/lib64/libOpenCL.so "$jni\libOpenCL.so"

# 2) 只重编 OpenCL 后端（目标版本 2.0）
cmake -S <llama.cpp-b11550 工作区> -B <build-ocl200> -G Ninja -DCMAKE_MAKE_PROGRAM=$ninja `
  -DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake -DCMAKE_BUILD_TYPE=Release `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 `
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod -DGGML_NATIVE=OFF -DGGML_OPENMP=ON `
  -DGGML_OPENCL=ON -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON `
  -DGGML_OPENCL_TARGET_VERSION=200 `
  -DOpenCL_INCLUDE_DIR=<OpenCL-Headers 克隆> -DOpenCL_LIBRARY="$jni\libOpenCL.so"
cmake --build <build-ocl200> --target ggml-opencl --parallel 8

# 3) 入库前自检（这条必须为空，否则装载期就会 cannot locate symbol）
llvm-readelf --dyn-syms <build-ocl200>\bin\libggml-opencl.so | Select-String clCreateBufferWithProperties
```

### 5.5 驱动本体靠 sphal 命名空间加载

`libgsl.so`(2.1MB) / `libCB.so`(2.9MB) / `libllvm-glnext.so`(14MB) 仍留在 `/vendor/lib64`，
由厂商实现内部调 `android_load_sphal_library` 走 **sphal 命名空间**取（与 EGL 驱动同一套机制）。
这点很关键：`libCB.so` 还依赖 `libgralloc.qti.so`、`libhidlbase.so` 等厂商 HAL，
只有 sphal 这条路能拿到 —— **拷贝到 App 私有目录是行不通的**。
所以包里只多了一个 115KB 的 `libOpenCL.so`，其它一律不带。

### 5.6 NPU 档的限制（**DSP 代际下限**，仍然存在）

> ℹ️ 本节只讲 **v68 代际下限**（870/888 一类机型注册不出 HTP 设备）。魅族 21 Pro 那种
> 「设备在册、会话打不开」是另一回事，已于 §5.15 定位为 `ADSP_LIBRARY_PATH` 写法问题并修复。

NPU 要求设备 DSP 代际与包内 skel 一致：不少骁龙 870/888 机型的 DSP 是 **v68**
（`/vendor/lib/rfsa/adsp/libsnpe_htp_v68_skel.so`），而包内只有 v73/75/79/81，
HTP 后端会注册不出设备并报 `invalid device: HTP0` —— App 会把这种情形归类为
「NPU 档不可用」并自动回退 CPU。

### 5.7 驱动编译器编不过内核（GPU 档不可用的真正原因）

**现象**：平台、设备、context、queue 全部正常（
`QUALCOMM Adreno(TM) | OpenCL 2.0 Adreno(TM) 660 | OpenCL C 2.0`），
但 `clBuildProgram` 要么返回 `CL_OUT_OF_HOST_MEMORY(-6)` 且 build log 只有 5 字节 `Pass`，
要么**直接把进程打成 SIGSEGV**（`/vendor/lib/libCB.so` 里的
`QGPUPeepholeOptimizer::buildClusterOp`，崩溃地址是空指针解引用）。

**定位方法**：写了一个独立 PIE 探针，用与引擎**完全一致**的
`LD_LIBRARY_PATH` / `TMPDIR` / `OCL_ICD_FILENAMES` 环境启动，
并用 `fork()` 让**每个内核在独立子进程里单独编译**（段错误无法在进程内捕获，
只能靠子进程隔离）—— 一次运行即可拿到全量清单。

**结果（182 个内核）**：

| 判定 | 数量 | 内核 |
| --- | --- | --- |
| 编译器 SIGSEGV | 5 | `rms_norm`(11031B) `mean`(3394B) `sum_rows`(3388B) `ssm_scan`(14474B) `mul_mv_f16_f32_l4`(37919B) |
| 编译失败(-6) | 31 | `cumsum` `group_norm` `repeat` `sigmoid` `mul_mv_f16_f32_mrow` `flash_attn_*` `gemm_noshuffle_*` `gemv_noshuffle_*` `gemm_moe_*` `mul_mv_q4_0_f32_1d_*` 等 |
| 正常 | 146 | 其余（含 `mul_mv_q4_0_f32_flat`、`mul_mm_f16_f32_kq_kqv` 等主力矩阵乘） |

**已逐项排除**（都不是原因）：

- 编译选项：裸 `-cl-std=CL2.0`、加 `-cl-opt-disable`、去掉 `-cl-fast-relaxed-math` /
  `-cl-unsafe-math-optimizations` / `-cl-finite-math-only` —— 结果完全一致；
- `-cl-std=CL1.2`：这些内核用到 subgroup，直接报 `unsupported OpenCL extension`；
- 内存压力：`mmap` 并触到 2.6GB 时 `argsort`(2737B) 照编不误；
- SELinux 域 / linker namespace / `OCL_ICD_FILENAMES` / `TMPDIR` / `LD_LIBRARY_PATH`：
  在 `adb shell` 与 App 子进程两种上下文下表现相同；
- kernel 二进制缓存污染、编译顺序：全新进程第一个就编 `rms_norm` 同样崩；
- 设备能力：`cl_khr_subgroups` **支持**，但 `cl_khr_integer_dot_product` **不支持**。

**注意驱动的编译器是"有状态"的**：某些内核单独在子进程里能编过，
在引擎那种"一口气连编上百个"的进程里会退化并返回 -6。
所以"探针里能过"不等于"引擎里能过"。

**试过的规避手段与结论**（补丁均已从引擎源码撤销，本机不留任何改动）：

1. 按源码字节数跳过 5 个必崩内核 + `GGML_OPENCL_TOLERATE_KERNEL_FAILURES`
   容忍加载期失败 → 引擎能通过内核加载、`load_model` 完成、`llama threadpool init`
   成功并**开始跑 GPU 图计算**，随后在图计算阶段段错误；
2. 给主编译路径接上上游自带的重试（`clFinish` + 3 次，
   闪存注意力那几条路径本就这么用）→ 前进到更晚才崩，仍未通过；
3. 把失败算子用 `GGML_OPENCL_OPFILTER` 逐个退回 CPU → 换一个位置崩。

**结论**：这是 Adreno 660 该版驱动的 OpenCL 编译器缺陷，**App 侧无法修复**。
上游 llama.cpp 的 OpenCL 后端面向更新一代 Adreno（7xx/8xx），
本项目在 888/870 这类机型上保持 CPU 档，并由 App 归类失败原因、自动回退。

**复现要点**（探针已删除，结论保留在此）：内核源码字节数是稳定指纹，
`rms_norm.cl` = 11031 B、`mean.cl` = 3394 B、`sum_rows.cl` = 3388 B、
`ssm_scan.cl` = 14474 B、`mul_mv_f16_f32_l4.cl` = 37919 B、`mul_mv_f16_f32_mrow.cl` = 16468 B。

### 5.8 换 b11550 之后的复测（三档全跑）

在 Redmi K40（SM8350 / Adreno 660 / Android 14 / 7.1 GB RAM）上按与 App 引擎子进程**完全一致**的
环境复测（原生库目录 + `/data/local/tmp/ocllibs` 驱动副本 + `OCL_ICD_FILENAMES` + `TMPDIR`），
三档各发一次真实推理请求：

| 档位 | 启动参数 | 结果 | 原因 |
| --- | --- | --- | --- |
| CPU | `-ngl 0` | ✅ 可用 | `model loaded` → `listening on http://127.0.0.1:8080` → 32 token 正常返回 |
| GPU | `-ngl 999 -dev GPUOpenCL` | ❌ 不可用 | 约 4.5 s 后 `ggml_opencl: kernel compile error (err=-6)` + 5 字节 `Pass`（§5.7 的驱动编译器缺陷） |
| NPU | `-ngl 99 --device HTP0` | ❌ 不可用 | 当时报 `invalid device: HTP0` —— **后来查明是 b11550 丢了 Hexagon 注册**（§5.10），不是 DSP 代际问题（§5.6） |

**结论**：K40 上 GPU/NPU 的不可用与引擎版本无关（b11523 → b11550 无变化），根因在驱动（§5.7）与
DSP 代际（§5.6 / §5.9）。顺带记一条环境事实：4B-Q4 + `-c 4096` 在 7.1 GB 内存机型上足以把系统压到
`lowmemorykiller` 连续杀后台，GPU 档还要额外占显存/主机内存 —— 这是「默认 CPU 档 + 自动回退」的
现实理由之一。该机型 GPU/NPU 至今仍不可用，与 README / CHANGELOG 的「部分支持」表述一致。

### 5.9 K40 NPU 墙的双重实证（DSP 实为 HTP v68）

§5.6 依据 `/vendor/lib/rfsa/adsp/libsnpe_htp_v68_skel.so` 推断 K40 DSP 代际为 v68。
本节补两条**独立实证**，把「skel 文件名推断」升级为「设备自报 + 源码行为」：

1. **实机 skel 清单**（`find /vendor/lib* /vendor/lib*/rfsa`）：`/vendor/lib/rfsa/adsp/`
   只带 `libsnpe_htp_v65_skel.so`、`libsnpe_htp_v66_skel.so`、`libsnpe_htp_v68_skel.so`
   三档 SNPE skel（外加不带你机型的 `libQnnHtpSkel.so` / `libQnnHtpAltPrepSkel.so`），
   **没有任何 v73+ 的 DSP 固件**——与包内 `libggml-htp-v73/75/79/81.so` 无一匹配。
2. **实时 FastRPC arch 查询**：写一个最小 PIE 探针，dlopen `libggml.so` +
   `libggml-hexagon.so` 后直接调用 `ggml_backend_hexagon_reg()`（与引擎
   `ggml_backend_load_all()` 同一条路径），在实机上打出：
   ```
   ggml-hex: Loading driver libcdsprpc.so
   ggml-hex: Hexagon arch v68 is under supported range, capping at v73
   ggml-hex: forcing ndev to 1 for SoCs archs lower than v75.
   ggml-hex: device 0: HTP0 (phys=0, virt=0, domain=cdsp:3)
   ```
   即：设备的 CDSP 域经 `DSPRPC_GET_DSP_INFO` **自报 arch = v68**，被引擎钳到 v73，
   于是即便「注册出」一个 HTP0，其配套 skel 也只能是 `libggml-htp-v73.so`——
   而 v73 的 skel 跑在 v68 的 DSP 上无法工作。这就是 K40 上 NPU 彻底不可用的根因，
   与 §5.8 表格里 NPU 档 `invalid device: HTP0` 的表征一致。

**两条独立的 CPU 档位事实**：4B-Q4 在骁龙 888 上的 CPU 生成速度**实测约 4.5～5 tok/s**
（`print_timing`：prompt ≈5.4 / eval ≈4.6 tok/s；`-c 2048`、`-np 1`、8 线程）。
早期若干文案误写成「约 30 tokens/s」（6 倍高估），本次已把
`PhysLocalEngine` 里 GPU/NPU 失败回退建议中的 CPU 速度统一改回「生成约 5～6 tok/s」。

### 5.10 b11550 丢了 Hexagon 注册：`libggml.so` 必须带 `GGML_USE_HEXAGON`（2026-10-11）

**现象**：魅族 21 Pro（SM8650 / Android 16 / `ro.board.platform=pineapple`）升级到 b11550 后，
NPU 档从「能起来」变成一律不可用；引擎日志里只有一句
`error while handling argument "--device": invalid device: HTP0`，**没有任何后端加载报错**。

**根因**：后端静态注册表在 `ggml/src/ggml-backend-reg.cpp` 里是按条件编译挑的
（L147-155：`#ifdef GGML_USE_OPENCL` → `register_backend(ggml_backend_opencl_reg())`、
`#ifdef GGML_USE_HEXAGON` → `register_backend(ggml_backend_hexagon_reg())`）。
本配方为避免依赖高通 Hexagon SDK，配置时用了 `-DGGML_HEXAGON=OFF` —— 而 CMake 的
`GGML_HEXAGON=ON` 正是**唯一**给 `ggml` 目标加上 `GGML_USE_HEXAGON` 宏并链接
`libggml-hexagon.so` 的地方。于是重编出来的 `libggml.so`：既没有 `NEEDED libggml-hexagon.so`、
也没有未定义符号 `ggml_backend_hexagon_reg`，注册表里根本没有 Hexagon 这一项 →
所有机型都注册不出 HTP 设备 → `--device HTP0` 被判非法。
release 构建里后端加载失败是**静默**的（`ggml_backend_load_all_from_path()` 传 `silent = true`），
所以连一行提示都不会有，这是它难查的关键。

**验证方法（三条独立证据）**：
1. `llvm-readelf -d/--dyn-syms` 对比：HEAD(b11523) 的 `libggml.so` 有 `NEEDED libggml-hexagon.so`
   + `UND ggml_backend_hexagon_reg`；b11550 重编的只有 `opencl/rpc/cpu` 三个 `*_reg`。
2. 同一台机器、同一套库，只把 `libggml.so` 换回 HEAD 版：
   `--list-devices` 从「只有 `GPUOpenCL`」变成 **`GPUOpenCL` + `HTP0: Hexagon`**。
3. 换回后在壳层（`adb shell`，uid 2000）直接跑引擎：模型加载 7～10 s，
   `print_timing` 实测 prompt ≈38 tok/s、**生成 ≈13.5 tok/s**、`graphs reused = 46`。

**修复（不需要 Hexagon SDK，只重编 `ggml` 目标）**：worktree 回到 `b11550`，
关闭 OPENCL（省掉 OpenCL-Headers 依赖）但**手动补上两个注册宏**，并把已入库的上游
`libggml-opencl.so` / `libggml-hexagon.so` 作为 `NEEDED` 链进去：

```bat
git -C llama.cpp worktree add <tmp>\llama-b11550 b11550
cmake -S <worktree> -B <worktree>\build-ggml-hex -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE=<ndk>\build\cmake\android.toolchain.cmake ^
  -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 ^
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod -DGGML_NATIVE=OFF -DGGML_CPU_KLEIDIAI=OFF -DGGML_LLAMAFILE=ON ^
  -DGGML_OPENMP=ON -DGGML_OPENCL=OFF -DGGML_RPC=ON -DGGML_HEXAGON=OFF -DLLAMA_BUILD_SERVER=ON ^
  "-DCMAKE_CXX_FLAGS=-DGGML_USE_OPENCL -DGGML_USE_HEXAGON" ^
  "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--no-as-needed -L<jniLibs> -l:libggml-opencl.so -l:libggml-hexagon.so"
cmake --build <worktree>\build-ggml-hex --target ggml --parallel 8
```

产物核验（每次重编 `libggml.so` 都必须过）：`NEEDED` 含 `libggml-hexagon.so`；
`UND ggml_backend_hexagon_reg` / `ggml_backend_opencl_reg`；`stlur`/`ldapur`/`smmla` 归零；
`LOAD` 段 Align = `0x4000`。
`GGML_USE_OPENCL` / `GGML_USE_HEXAGON` 这两个宏在整个 `ggml/src` 里**只被
`ggml-backend-reg.cpp` 用到**（`grep` 可复核），所以用全局 `CMAKE_CXX_FLAGS` 定义是安全的。

### 5.11 魅族 21 Pro：注册修好后 App 侧仍开不了 HTP 会话（**2026-10-11 的误判，已被 §5.15 推翻**）

> ⚠️ **本节原结论作废，只留档案**：当时判定「该机 DSP 安全策略拒绝未签名 skel 进 App 的 PD、
> App 侧不可修」。2026-10-12 的真机 A/B 证明**这个判断是错的** —— 真实原因只是 App 下发的
> `ADSP_LIBRARY_PATH` 写成了冒号列表，CDSP 把一个目录名当整串用，skel 从未被找到。
> 定案、修法与复测见 **§5.15**。

§5.10 修好后，失败点从「没有设备」推进到「有设备、会话打不开」：

```
E ggml-hex: HTP0 failed to open session :
  uri file:///libggml-htp-v75.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=cdsp&_session=0
  error 0x80000406          <- 第 1 次（强制 v75）
E ggml-hex: HTP0 failed to open session :
  uri file:///libggml-htp-v73.so?....  error 0x80000406   <- 第 2 次（降档重试，逻辑正常）
```

两域对照（同一台机器、同一套库、同一份模型）：

| 运行位置 | 结果 |
| --- | --- |
| `adb shell`（uid 2000 / `u:r:shell:s0`） | ✅ 正常，生成 ≈13.5 tok/s |
| App 进程（`untrusted_app`，真机 release 包） | ❌ `failed to open session` = `0x80000406` |
| `run-as`（`runas_app`） | ❌ 更早断在 FastRPC 底层 `err 512 / 0x200`（域不同，不代表真机） |

**当时的推理（错在哪）**：`0x80000406` = `AEE_EOFFSET(0x80000400) + 6`，是 DSP 侧 AEE 错误码，
出现在 `htp_iface_open()` —— 于是判为「App 触达了 DSP、DSP 拒绝未签名 skel 进 App 的 PD」，
且认为 v75/v73 报同一个码说明「与代际无关」。**实际上 `0x80000406` 只是「DSP 侧动态加载失败」
的笼统码，推不出签名策略**；「shell 行、App 不行」的真正差异是**搜索路径写法**，不是 SELinux/PD 域。
教训：手上有成功样本时，先去枚举成功/失败样本之间**可变的差异**（本次就是环境变量的值），
不要先解释失败、更不要跳到「不可修」。

当时排除的猜想仍然成立且有用：**不是缺库**（`libcdsprpc.so` 需要的
`vendor.qti.hardware.dsp@1.0.so` 已按原名拷进 `files/htplibs`）、**不是 SELinux 拒绝**
（无针对该 App 的 `avc: denied`）、**节点权限推不出结论**（`/dev/adsprpc-smd` 连 shell 都打不开，
而 shell 的 NPU 却是通的）、**`run-as` 不能代表真机**（域不同，断在更底层）。

### 5.12 §5.10 修复的连带坑：注册修好后 CPU 档反而「启动报错」（2026-10-12 凌晨）

§5.10 的修复落地后，真机（魅族 21 Pro）出现新症状：**明明选了 CPU 档，启动也报错**，
引擎 `code=1` 退出，且 App 把失败误判成「模型文件问题」（给了换模型的建议、不回退）。

**根因链**（日志 + b11550 源码双实证）：

1. CPU 档的启动参数从不点名设备（只有 `-ngl 0`，无 `-dev`）；
2. b11550 起 `llama_init_from_model` 的建上下文路径会**探测所有在册设备**
   （`common_fit_params` 逐个初始化设备、读空闲显存）——§5.10 修好后 HTP0 **一定在册**，
   于是 CPU 档也会去开 HTP 会话；
3. App 域里 HTP 会话打不开（当时误判为 §5.11 的「平台策略墙」，真实原因是库路径写法，
   定案见 §5.15；现象同为 DSP 侧 `0x80000406`）→ 设备初始化抛异常
   → **整个 context 创建失败** → 引擎退出。b11523 没有这层「全设备探测」，
   所以同一个 App 域墙从未波及过 CPU 档 —— 这正是「之前 CPU 还可以」的原因。

**为什么不能用 `-dev CPU` 收窄**：b11550 的 `parse_device_list`
（`common/arg.cpp:1122-1140`）查到名为 CPU 的设备后**显式拒绝 CPU 类型**
（`ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU` → `invalid device: CPU`）；
官方的「只用 CPU」写法是单设备特殊值 **`none`**（`devices = [nullptr]`，arg.cpp:1129-1131）。

**修法**：`PhysLocalEngine.normalizeDeviceArgs` 的补齐分支从「GPU→`GPUOpenCL`、NPU→`HTP0`」
扩成三档——CPU 档补 `-dev none`。设备集显式收窄后，fit-params 探测只看 CPU，HTP 不再被触碰；
NPU/GPU 档本来就有 `-dev` 点名，不受影响。

**修复后实测（同机同夜）**：

| 档位 | 参数 | 结果 |
| --- | --- | --- |
| CPU | `-ngl 0 -dev none` | ✅ `model loaded` → `listening on http://127.0.0.1:8080` |
| GPU | `-ngl 999 -dev GPUOpenCL` | ✅ App 内首次完整跑通 `listening`（Adreno 750） |
| NPU | `--device HTP0` | ❌ 当时判为 §5.11 的平台墙（分类正确：红字提示 + 自动回退）→ **2026-10-12 已修，见 §5.15，魅族同机可跑** |

**教训**：修「注册缺失」这类全局性回归时，要想到它会把**原本被静默掩盖的下游坑**重新暴露出来 ——
注册修好 = HTP0 回到设备列表 = 所有「遍历设备」的代码路径都要重新过一遍 App 域这堵墙。

### 5.13 引擎同步到 b11558 官方包：opencl 单库重编即可（2026-10-12，**当天即被 §5.14 推翻**）

按用户指定试过「官方包打底、只重编 `libggml-opencl.so`」这条路线：官方 `libggml.so`（393,832 B）
的注册与 `libomp` 依赖都没问题，但 `libggml-opencl.so`（13,237,904 B）硬引用 OpenCL 3.0 的
`clCreateBufferWithProperties`（b11523/b11557 官方包同样如此），直接换必现装载期
`cannot locate symbol`、连 CPU 档都起不来。修法：worktree @ b11558 +
`-DGGML_OPENCL_TARGET_VERSION=200` 单编该目标 —— 产物 13,966,360 B，引用归零、NEEDED 闭包与官方
一致、`smmla`/`stlur`/`ldapur` 归零、16 KB 对齐 ✓；魅族 21 Pro CPU 档实测 `model loaded` →
`listening`、`/health` = `{"status":"ok"}` ✓

**但当天就被 K40（888）打回**：官方 CPU / llama 系列库是 ARMv8.6 口径（`libggml-cpu.so` 含
`smmla×420`），CPU 档一启动即 `Fatal signal 4 (SIGILL)`，崩溃点正是
`httplib::Server::bind_internal` 里的 `stlur`（§5.14）。**结论：这条路勿再用** —— 真机端保持
「全量 armv8.2 自编 + 官方包只取 Hexagon skel / 设备库」。

### 5.14 红米 K40（SM8350 / Adreno 660）实锤：官方 b11558 包一启动就 SIGILL → 回退全量 armv8.2 自编（2026-10-12）

§5.13 那条「官方包打底」的路线当天就被真机打回来了：**K40 上 CPU 档一点「启动引擎」立刻报错**。

**现场（`logcat -b crash`）**：

```
F libc   : Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC), fault addr 0x71bdffb810
F DEBUG  : Cmdline: .../lib/arm64/libllama-server.so -m .../Qwen3.5-4B-Q4_0.gguf --host 127.0.0.1
           --port 8080 -c 8192 -np 1 -t 6 -ngl 0 --cache-type-k q8_0 --cache-type-v q8_0
           --reasoning off --repeat-last-n 512 --no-warmup -dev none
F DEBUG  : #00 ... libllama-server-impl.so (httplib::Server::bind_internal(...)+180)
```

**两条硬证据**（`llvm-objdump` / `llvm-readelf`）：

1. 崩溃 PC `0x599810` 处的指令**就是** `stlur`（ARMv8.4 的 LSE2 指令）：
   `599810: 99040260  stlur  w0, [x19, #0x40]` —— 而 888 的 `/proc/cpuinfo` Features
   （`fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp`）
   **既无 `i8mm`、也无 LSE2**，执行到即 ILL。
2. 官方包新指令计数：`libllama-server-impl.so` `stlur×5`；`libggml-cpu.so` `stlur×4` + **`smmla×420`**。
   注意崩溃点在**绑定 HTTP 监听**阶段 —— 模型都还没开始加载，说明这不是「某个 i8mm 内核少了运行时判定」，
   而是整包 ISA 口径就是 ARMv8.6：**没救，只能重编**（GPU 档同样参数同样是同一条 `stlur`，与档位无关）。

**修复**：完全按 §2 配方全量自编 16 库 → 按 §5.10 单独重编 `ggml` 目标补回 Hexagon 注册 →
把 NDK clang 19 的 `libomp.so` 一并入库（自编 CPU 后端依赖它）。与 §2 的唯一差异是
`-DANDROID_PLATFORM=android-26`（不编 `llama-mmap.cpp` 就不需要 §1.1 的 `posix_madvise` 垫片）。

**换装后核验（全过）**：

| 检查项 | 结果 |
| --- | --- |
| 16 库 `stlur` / `ldapur` / `smmla` | 全 0 ✓ |
| `libggml.so` | `NEEDED libggml-hexagon.so` + `UND ggml_backend_hexagon_reg` ✓ |
| `libggml-opencl.so` | `clCreateBufferWithProperties` 引用 = 0 ✓ |
| `LOAD` 段 Align | `0x4000`（16 KB）✓ |
| `libllama-server.so` | `Type: DYN`（PIE），7,336 B ✓ |
| jniLibs 规模 | 27 文件 / 245.7 MB |

**K40 实测（同机、同模型 Qwen3.5-4B-Q4_0）**：

- 壳层直跑（`/data/local/tmp/eng`，注意要 `export LD_LIBRARY_PATH`，写成 `VAR=x ; cmd` 是**不导出**的）：
  `Hexagon arch v68 is under supported range, capping at v73` → `model loaded` →
  `listening on http://127.0.0.1:8090`，无 SIGILL ✓
- App 内点「启动引擎」：状态条 `已连接 · CPU`、设备行 `● 引擎运行中`，日志
  `n_ctx_slot = 8192` → `model loaded` → `listening on http://127.0.0.1:8080`，子进程存活 ✓
  （顺带印证 §5.9 的 K40 v68 结论：DSP 是 v68，Hexagon 注册回来后仍只能降档到 v73，CPU 档才是可用档）

**教训（写给未来的自己）**：官方 Android 预编译包**只配当 Hexagon skel / 设备库的来源**；
CPU / OpenCL / llama 系列库必须保持 ARMv8.2 自编口径，否则 855 / 865 / 870 / 888 一整代老旗舰全灭。
每次换 llama.cpp tag：§2 全量自编 → §5.10 补 Hexagon 注册 → §3 自检，三步一步都不能省。

### 5.15 魅族 21 Pro 的 NPU 一直能跑：`ADSP_LIBRARY_PATH` 不能写冒号列表（2026-10-12，推翻 §5.11）

**触发**：用户拿旧发布包 `AI-Roleplay-v1.0.6-phys.apk`（versionCode 7，19 库精简集 + 另一 tag 的
Hexagon skel + 63 KB 的旧 ICD loader）说「这个包魅族支持 NPU」。真机 A/B 之后确认：
**用户是对的，§5.11 的「平台策略墙」结论错了。**

**A/B 证据**（同一台魅族 21 Pro / SM8650 / Android 16 / 同一个 App uid / 同一份 Q4_1 模型 /
同一套 App 代码逻辑，只换引擎库与环境变量）：

| 组 | 组成 | NPU 档实际下发 | 结果 |
| --- | --- | --- | --- |
| D | v1.0.6 旧发布包 | `-ngl 99 -dev HTP0` | ✅ `model loaded` → `listening`；状态条 `已连接 · NPU · Hexagon` |
| A | 本次 b11558 自编（§5.11 的判断对象） | `-ngl 99 --device HTP0` | ❌ `HTP0 failed to open session … 0x80000406` → 自动回退 GPU |
| B | 只把 v1.0.6 的 `libggml-hexagon.so` + 4 个 skel 换进自编包 | 同上 | ❌ **同一个错码** → 与 skel 代际 / hexagon 库版本**无关** |
| C | 保留自编库，只把 `ADSP_LIBRARY_PATH` 改成**单个目录**并把 skel 实物拷进去 | 同上 | ✅ `model loaded` → `listening` → 真实对话 430 t/s(prompt) / 11.5 t/s(eval) |

顺带排除：两版 `libggml-hexagon.so` 的 FastRPC 机制 token（`FASTRPC_MANAGE_DSP_LIBRARY_PATH`、
`UNSIGNED_PD_SUPPORT`、`remote_session_control`、`libggml-htp-v%u.so`…）**逐项 1:1 相同**，
NEEDED 也一致；两版 skel 的段结构同构、都没有签名段。

**根因（logcat 直接给出，不需要推测）**：App 当时下发的是**冒号拼接的两个目录**：

```
ADSP_LIBRARY_PATH=/data/app/<pkg>-<hash>/lib/arm64:/data/user/0/<pkg>/files/htplibs
```

CDSP 侧 `apps_std` 把它当**一个目录名**用，于是拼出这些根本不存在的路径：

```
apps_std_fopen_fd: done for /data/app/…/lib/arm64:/data/user/0/…/files/htplibs/cdsp/./libggml-htp-v75.so
apps_std_fopen_fd: done for /data/app/…/lib/arm64:/data/user/0/…/files/htplibs/./libggml-htp-v75.so
apps_std_fopen_fd: done for /vendor/lib64/rfs/dsp/cdsp/./libggml-htp-v75.so      <- 唯一合法路径，但设备上没有我们的 skel
```

skel 永远找不到 → `dynamic loading failed … errno 2 (no such file)` → 冒泡成 `0x80000406`。
**所以 `0x80000406` 在这里只是「DSP 侧动态加载失败」的笼统码**，与签名策略无关；
§5.11 那条「shell 行、App 不行」的对照，真正差异是**搜索路径写法**，不是 SELinux/PD 域。

**修法**（`PhysLocalEngine`，两处，见 `startInternal`）：

1. `ADSP_LIBRARY_PATH` **只写 htpDir 一个目录**，绝不写冒号列表；
2. 启动前把 `libggml-htp-v{73,75,79,81}.so` 从 `nativeLibraryDir` **实物拷进** `filesDir/htplibs/`，
   并各拷一份到 `filesDir/htplibs/cdsp/` —— CDSP 会按 `<dir>/cdsp/<名>` 与 `<dir>/<名>` 两种形式找，
   两种都给上；四个代际全拷，`GGML_HEXAGON_ARCH` 覆盖与降档重试才不会扑空。

**复测（同一天，两台机）**：

| 机型 / 档位 | 启动 | 实测（Qwen3.5-4B-Q4_1，App 内真实对话） |
| --- | --- | --- |
| 魅族 21 Pro / CPU | ✅ `-ngl 0 -dev none`，约 3.4 s 就绪 | prompt 806 tok / **14.71 t/s**、生成 78 tok / **8.08 t/s** |
| 魅族 21 Pro / GPU | ✅ `-ngl 999 -dev GPUOpenCL`（Adreno 750），约 11.2 s 就绪 | prompt 709 tok / **91.47 t/s**、生成 87 tok / **7.60 t/s** |
| 魅族 21 Pro / NPU | ✅ `--device HTP0`（Hexagon v75），约 6.0 s 就绪 | prompt 637 tok / **430.19 t/s**、生成 62 tok / **11.51 t/s** |
| 红米 K40 / CPU | ✅ `-ngl 0 -dev none` → `model loaded` → `listening`，无 SIGILL | ——（§5.14 的 armv8.2 口径未受影响） |
| 红米 K40 / NPU | 仍受 §5.9 的 v68 代际下限限制（包内只有 v73+），行为不变 | 明确报错 + 自动回退 |

三档在魅族 21 Pro 上**都能正常启动**（端口 8080 均实测 `LISTEN`）。同机横比：**NPU 综合最快**
（prompt 约 GPU 的 4.7 倍、CPU 的 29 倍；生成约为 GPU 的 1.5 倍），**GPU 的 prompt 远快于 CPU**
但生成与 CPU 基本持平，**CPU 加载最快**（3.4 s）但长 prompt 是瓶颈（806 tok 花了 54 s）。

**教训**：
- 「DSP 错码 + 两域对照」只能证明**现象**，不能直接跳到「安全策略」这种**不可修**的结论；
  把一个可修的问题写成「平台墙」，代价是整个机型白丢一个版本的 NPU。
- **只要存在一份 App 域内的成功样本（老包 / 别的机型 / 别的时间点），就不该下"平台墙"结论** ——
  先去找成功样本与失败样本之间**可枚举的差异**，而不是先解释为什么失败。
- 「文档说是列表、实现只当单值」这类环境变量接口，必须用真机日志验证**它到底被怎么拼接**
  （本次就是 `apps_std_fopen_fd` 这行日志一句话定位的）。

