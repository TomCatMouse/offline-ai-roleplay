package com.example.roleplay.local

import android.content.Context
import androidx.compose.runtime.Composable
import com.example.roleplay.BuildConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * 一个放在手机里的本地模型文件。
 *
 * @param path      传给 llama-server 的 -m 参数（分片模型传第一片）
 * @param name      文件名（UI 显示）
 * @param sizeBytes 全部切片加起来的大小
 * @param parts     切片数量（1 = 单文件）
 */
data class LocalModel(
    val path: String,
    val name: String,
    val sizeBytes: Long = 0L,
    val parts: Int = 1
) {
    /** "1.49 GB" 这种给人看的大小 */
    val sizeText: String
        get() {
            val mb = sizeBytes / 1024.0 / 1024.0
            return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.0f MB", mb)
        }
}

/** 本地推理跑在哪个硬件上（骁龙引擎三选一） */
enum class LocalDevice(val label: String, val ngl: Int, val extraArgs: List<String>) {
    /** CPU：最稳，任何机器都能跑 */
    CPU("CPU", 0, emptyList()),

    /** Adreno GPU：走 OpenCL 后端，层卸载到 GPU */
    GPU("GPU · Adreno", 999, emptyList()),

    /** Hexagon NPU：走 HTP 后端（-dev HTP0） */
    NPU("NPU · Hexagon", 99, listOf("--device", "HTP"));

    /**
     * 这个设备挂了以后该退到哪一档（末档返回 null = 没有更保守的选择了）。
     * 顺序就是产品口径的 NPU → GPU → CPU。
     */
    fun fallback(): LocalDevice? = when (this) {
        NPU -> GPU
        GPU -> CPU
        CPU -> null
    }
}

/*
 * 为什么 GPU/NPU 档的 extraArgs 里那点东西必须点名设备（真机排障结论，给后续改动留个记号）：
 *
 * 1) llama.cpp 的默认设备选择会把**所有 GPU 类型**的后端按注册顺序排成一列
 *    （src/llama.cpp:222-279 收集 GGML_BACKEND_DEVICE_TYPE_GPU 的设备，main_gpu 默认取第 0 个），
 *    而 Hexagon 后端注册的设备类型**也是 GPU**（ggml-hexagon.cpp:7938）。OpenCL 不可用时
 *    它一个设备都不注册（ggml-opencl.cpp:6310-6312 直接 return 空列表），于是「选 GPU」
 *    会**静默落到 NPU 上**：用户以为在跑 Adreno，实际跑的是 Hexagon，NPU 一失败整台引擎就退出。
 * 2) 设备名匹配是 `striequals` 全等比较（ggml-backend-reg.cpp:345-353、common/arg.cpp:1130-1133），
 *    写 `--device HTP` 这种前缀会被判 `invalid device` 直接退出；
 *    OpenCL 的设备名是 "GPUOpenCL"（ggml-opencl.cpp:12904），
 *    Hexagon 默认设备名是 "HTP0"（ggml-hexagon.cpp:8928）。
 *
 * 这两条都在 [com.example.roleplay.local.PhysLocalEngine] 的启动参数装配里兜住
 * （GPU 档补 `-dev GPUOpenCL`、`--device HTP` 归一化成 `HTP0`），
 * 这样这里保持「人类可读的档位定义」不变。
 */

/** 真机本地引擎状态快照（默认包里永远是初始值，UI 也不会读到） */
data class LocalEngineState(
    /** attach() 是否已经跑过（建目录 + 扫描） */
    val attached: Boolean = false,
    /** 引擎文件是否齐全（真机包里必然齐全；不齐会给出提示而不是崩） */
    val enginePresent: Boolean = false,
    /** 有没有「所有文件访问权限」（Android 11+ 读写 /storage/emulated/0/AI角色扮演 需要） */
    val hasPermission: Boolean = false,
    val rootDir: String = "",
    val modelDir: String = "",
    val models: List<LocalModel> = emptyList(),
    val scanning: Boolean = false,
    val starting: Boolean = false,
    val running: Boolean = false,
    val activeModel: String = "",
    /**
     * 用户当前在模型列表里挑中的模型路径。
     * 反馈：启动→停止（或切页回来）后选择会跳回列表第一个，所以把选择也放进状态流，
     * 界面上「挑中的」和「正在跑的」是两件事，停止后仍停在你挑的那个上。
     */
    val selectedModel: String = "",
    val device: LocalDevice = LocalDevice.CPU,
    /**
     * 引擎报错文案。**CPU / GPU / NPU 三档共用这一份**（用户要求改共用）：
     * 不再按设备分槽 —— 哪一档启动失败，报错就显示在这一行；切档不会「带走」或「隔离」它。
     * 清空时机：用户手动「停止引擎」、新一轮手动启动、重新扫描、引擎成功就绪。
     * 「启动有报错则继续显示」：进程因错误退出（崩溃/退出码非 0）**不会**清它，
     * 报错一直挂在界面上，直到上面某个清空时机到来。
     */
    val error: String = "",
    /**
     * 引擎日志（最后一屏），**CPU / GPU / NPU 三档共用这一份**（用户要求改共用）。
     * 自动回退 NPU→GPU→CPU 时，各档的启动/报错行都累加进这同一份流，
     * 用户一眼看到「NPU 为什么挂 → 换 GPU 又挂 → 最后 CPU 起来」的全过程。
     * 清空时机与 [error] 一致：手动停止 / 新一轮手动启动 / 重新扫描；
     * 引擎自己退出（崩溃）不清空，日志留着当诊断依据。
     */
    val logTail: String = "",
    /**
     * 本机 OpenCL（Adreno GPU）后端是否可用。
     * null = 还没启动过引擎、未知；false = 引擎日志明确报了 platform IDs not available。
     * Android 12+ 的非 NDK 库命名空间限制会导致很多机型用不了 GPU 后端，
     * 这种情况下选 GPU 会退化成极慢路径（实测 0.5 tok/s），必须如实告诉用户。
     */
    val openclOk: Boolean? = null,
    /** 本机 Hexagon（HTP NPU）后端是否可用；判断依据同上，来自引擎日志 */
    val htpOk: Boolean? = null,
    /**
     * 机型探测结果摘要（一行，给人看）。
     * 例：「SM8650 (pineapple) · Qualcomm」（= ro.soc.model + ro.board.platform + ro.soc.manufacturer，
     * 读不到的属性直接省略）；界面在「当前设备」那一行把它和 HTP 版本一起显示。
     * 「为什么选这个 HTP 版本」的依据原文在引擎日志的「【HTP】…｜依据：…」行里（探测不到也会写明原因）。
     */
    val socDesc: String = "",
    /** 探测/选定并已通过 `GGML_HEXAGON_ARCH` 下发的 HTP 版本号（0 = 没探测到、交给引擎自己查） */
    val htpArch: Int = 0,
    /**
     * OpenCL 不可用的**具体原因**（来自引擎日志的原文，如
     * 「platform IDs not available」）；空串 = 还没发现不可用。
     * 和 [openclOk] 的区别：openclOk 是布尔结论，这个是给人看的证据原文。
     */
    val openclReason: String = "",
    /**
     * 「引擎替你做了什么」的可见提示（**不覆盖** error，是并列的一行）。
     *
     * 用途：需求「回退不能静默」——NPU 会话打开失败后自动改用 GPU、GPU 不可用后自动改用 CPU，
     * 都必须在这里留一句话，界面/日志都能看到「为什么现在跑的不是我点的那一档」。
     * 用户手动点「启动引擎」会清空它（新一轮意图）。
     */
    val notice: String = ""
)

/**
 * 真机包（phys）专属的本地推理引擎。
 *
 * 设计约束（用户要求"两套逻辑互不影响"）：
 * - 实现体 [com.example.roleplay.local.PhysLocalEngine] 只存在于 `src/phys/`，
 *   默认包根本不编译它，APK 里也没有引擎二进制；
 * - 默认包里 [LocalEngineProvider.engine] 恒为 null，共享代码里所有
 *   `LocalEngineProvider.engine?.xxx` 都是空操作，行为与以前完全一致。
 */
interface LocalEngine {
    val state: StateFlow<LocalEngineState>

    /** 真机模式下「模型切换（PC）」那一段由本地模型接管 */
    val hidesRemoteSwitcher: Boolean

    /** 真机包默认直连手机本机的引擎（127.0.0.1），默认包仍连 PC */
    val defaultServerHost: String

    /** App 启动时调用：创建 /storage/emulated/0/AI角色扮演/模型 + 扫描 gguf，幂等 */
    fun attach(context: Context)

    /** 重新扫描模型目录（用户刚往里丢了模型时用） */
    fun rescan()

    /** 用指定模型启动本地推理服务；会占用 127.0.0.1:8080 */
    fun start(model: LocalModel, device: LocalDevice)

    /** 停止本地推理服务 */
    fun stop()

    /** 跳系统「所有文件访问权限」授权页 */
    fun openStoragePermission(context: Context)

    /**
     * 设置页里的「真机本地推理」卡片。
     *
     * @param onRetryConnect 引擎就绪后回调一次，让设置页顺手重连（用户不用手动点重试）
     * @param isConnected     App 当前是否已连上推理服务；已连上就不再重复触发重连，
     *                        否则会反复闪加载页
     * @param onShowHint      卡片内的轻提示出口（真机反馈：复制成功的弹窗不要用系统 Toast，
     *                        改走全局统一的顶部药丸）—— 实现里任何想提示的场合都回调它，
     *                        由宿主页面（设置页的 TopHintHost）负责展示
     */
    @Composable
    fun SettingsSection(
        onRetryConnect: () -> Unit,
        isConnected: Boolean,
        onShowHint: (String) -> Unit
    )
}

/**
 * 真机引擎的装配点。
 *
 * 默认包：`BuildConfig.LOCAL_ENGINE == false` 是编译期常量，
 * 下面这一整个 runCatching 分支会被编译器直接丢掉，连类名都不会进 APK。
 */
object LocalEngineProvider {

    val engine: LocalEngine? = if (!BuildConfig.LOCAL_ENGINE) {
        null
    } else {
        runCatching {
            Class.forName("com.example.roleplay.local.PhysLocalEngine")
                .getDeclaredField("INSTANCE")
                .get(null) as LocalEngine
        }.getOrNull()
    }

    /** 当前是不是真机（带本地引擎）模式 */
    val isPhysBuild: Boolean get() = BuildConfig.LOCAL_ENGINE
}
