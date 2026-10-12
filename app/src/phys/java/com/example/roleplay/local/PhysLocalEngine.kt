package com.example.roleplay.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真机（phys）专属：内置骁龙 arm64 llama.cpp 引擎 + 手机本地模型目录。
 *
 * 这套东西只存在于 src/phys 源码集里，默认包既不会编译它，APK 里也没有引擎二进制。
 * 行为约定：
 * 1. App 一起来（MainActivity.onCreate → attach）就创建
 *    `/storage/emulated/0/AI角色扮演/模型/`，并扫描里面的 .gguf；
 * 2. 点「启动引擎」把 nativeLibraryDir 里的 libllama-server.so 拉起来，
 *    监听 127.0.0.1:8080（和 App 原本的推理地址一个协议，
 *    所以聊天链路一行都不用改）；
 * 3. 模型完全放手机本地，不联网、不依赖 PC。
 */
object PhysLocalEngine : LocalEngine {

    private const val TAG = "PhysEngine"

    private const val ROOT_NAME = "AI角色扮演"
    private const val MODELS_NAME = "模型"

    /** bin/llama-server 改名后的样子：只有 nativeLibraryDir 里的文件才允许被执行 */
    private const val ENGINE_EXE = "libllama-server.so"

    private const val LOCAL_HOST = "127.0.0.1"
    private const val LOCAL_PORT = 8080

    /** 上下文开 2048、单槽位 —— 真机上内存最稳的档位（4 槽位跑 2B 模型会被系统杀掉） */
    private const val CTX_SIZE = 2048

    /**
     * 「错误 + 日志」整块的固定高度（第 4 项「启动引擎后跳屏」收尾用）。
     *
     * 当前算法（第 2 项第二次调整后：提示词与「当前设备」之间给基准间距的 2 倍 = 32dp）：
     *   外壳 LOG_AREA_HEIGHT = 148dp，内部自上而下：
     *     报错行（默认 0dp，出现时 ERR_BLOCK_HEIGHT + 8dp 间距）
     *     + 当前设备行(~12dp) + 4dp + 日志框(LOG_BOX_HEIGHT / 有报错时 LOG_BOX_HEIGHT_WITH_ERR)
     *   无报错：12 + 4 + 126 = 142dp；有报错：28 + 8 + 12 + 4 + 90 = 142dp
     *   —— **两种状态内容一样高**，所以外壳恒定 148dp（余下 6dp 落在底部，不参与纵向间距）。
     *   外壳内部是**顶部对齐**（Alignment.TopStart）：于是"提示词 → 当前设备行"的可见留白
     *   就等于外面那个 32dp 间距（=「推理设备 → CPU 档位」8dp 间距视觉上的 2 倍，用户第 2 项要求）；
     *   底部那几 dp 余量被压在按钮上方的 12dp 间距里，肉眼看不见。
     * 第 3 项（上一轮）：报错行从下面日志区那个底部对齐的列里搬到了"设备备注提示词"正下方；
     * 上一轮再把它收进日志外壳顶部并默认隐藏 —— 出现时正好插在提示词与「当前设备」之间，
     * 卡片总高依旧不变，报错出现/消失都不跳屏。
     * 需求 1/5（本轮）：这个 28dp 槽位改成「报错优先、没有报错就显示自动回退提示（notice）」——
     * 两者共用同一个槽位与同一组高度常量（口径见 `val slotText`），所以多了一句提示也不会跳屏；
     * 颜色区分：报错红（ErrColor）、回退提示暖黄（WarnColor）。
     * 日志框内部可用高 = 126 - 8×2(内边距) = 110dp，一屏约 9 行 × 12sp lineHeight
     * （fontScale=1 时）；有报错时 90 - 16 = 74dp ≈ 6 行，够看错误上下文。
     * 日志框**内部可滚动**（verticalScroll），默认贴底显示最新一行、新日志到达时自动跟随贴底、
     * 用户上翻看历史时不打扰 —— 内容多少行都不会改变这个外壳的高度。
     * 报错行钉死 2 行高度（height=ERR_BLOCK_HEIGHT + maxLines=2 + Ellipsis）：NPU 那条
     * 「引擎退出了（code=-1）…」在窄屏上本来会折成 3 行，把这一块撑出去、压到下面的按钮上。
     * 设备标注行也钉死 1 行（maxLines=1），否则「当前设备：… · 可滚动」+「已隐藏 N 条」
     * 在窄屏上会折成两行，同样会顶穿这个外壳。
     *
     * 更早的算法（供对照）：36dp 恒定报错槽位 + 10dp 间距 + 178dp 外壳，
     * 提示词到「当前设备」真机实测 49dp（用户第一次说"过大"）；上一轮收成 8dp
     * （真机节点间距 24px，反而比基准 50px 还小）；本轮按要求给到基准的 2 倍。
     *
     * 注意：改日志框高度时**必须**同时重算这里，否则顶部对齐后底部余量会变、内容可能溢出外壳。
     */
    private val LOG_AREA_HEIGHT = 148.dp

    /** 第 2 项：报错行固定占 28dp（2 行 × 14sp 行高）。高度钉死，1 行报错也占满，布局不抖。 */
    private val ERR_BLOCK_HEIGHT = 28.dp

    /** 日志框高度：无报错 126dp（≈9 行），有报错 90dp（≈6 行）。 */
    private val LOG_BOX_HEIGHT = 126.dp

    /** 有报错时的日志框高度：126 - (ERR_BLOCK_HEIGHT 28 + 8 间距) = 90dp，保证外壳总高不变。 */
    private val LOG_BOX_HEIGHT_WITH_ERR = 90.dp

    /**
     * 第 1 项（报错日志显示不全）：日志框**点按展开**时的高度 + 外壳对应高度。
     *
     * 默认（收起）仍是上面那组定高，保证流式输出不跳屏；用户主动点日志框才展开，
     * 展开后外壳从 148dp 长到 [LOG_AREA_HEIGHT_EXPANDED]，把「被截断的报错上下文」铺全，
     * 再点一次收起。展开是用户主动操作，允许布局位移（不是流式自动跳屏）。
     * 展开时日志框内可用高 ≈ 264 - 16(内边距) = 248dp ≈ 一屏 ~20 行，300 行环形缓冲
     * 里最近 20 行直接可见，更早的继续框内滚动。
     */
    private val LOG_BOX_HEIGHT_EXPANDED = 264.dp
    private val LOG_AREA_HEIGHT_EXPANDED = 330.dp

    /**
     * 共用日志环形缓冲保留的行数（第 5 项「日志可滚动查看」+ 本轮改三档共用）。
     *
     * 原来是 10 行 —— 就算日志框能滚，10 行也翻不出什么东西，所以先卡在「有没有历史」。
     * 改共用后只有**一条** 300 行缓冲（CPU/GPU/NPU 共享），内存账（UTF-16，每行按 ~120 字符估）：
     * 300 × 120 × 2B ≈ 72KB，再加 logTail 里一份 join 出来的同量副本（再 ~72KB），
     * 合计 < 0.2MB —— 8GB 机型上可以忽略。
     * 再往上加（1000+ 行）会开始影响「每 300ms join 一次 + 状态流内容比较」的开销，故取 300。
     */
    private const val LOG_RING_LINES = 300

    /** 日志框一屏能显示的行数（150dp 内部 134dp ÷ 12sp lineHeight），只用来决定要不要提示「可滚动」 */
    private const val LOG_VISIBLE_LINES = 11

    private const val PREFS = "phys_local_engine"
    private const val KEY_PID = "engine_pid"

    private val _state = MutableStateFlow(LocalEngineState())
    override val state: StateFlow<LocalEngineState> = _state.asStateFlow()

    override val hidesRemoteSwitcher: Boolean = true
    override val defaultServerHost: String = LOCAL_HOST

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var proc: Process? = null
    private var logJob: Job? = null

    /**
     * 「本次启动」的代号：每调用一次 start() 就 +1。
     *
     * 用途：stop() 取消了 logJob，但取消是协作式的，旧协程可能还要把最后几行读完
     * 才退出；如果它在这之后才把尾巴写进状态，就会盖到新一轮刚清空的槽位上。
     * 每次启动带一个代号，pumpLogs 写状态前对一下代号，不是自己的那轮就直接丢弃。
     * @Volatile：写在 start() 的线程、读在 IO 协程里。
     */
    @Volatile
    private var logGeneration = 0

    /**
     * 单一**共用**日志环形缓冲（用户要求：CPU / GPU / NPU 三档共用一份日志）。
     *
     * 原来按设备分槽（logRings: Map<LocalDevice, ArrayDeque>），各档日志互相隔离；
     * 现在改成全体共用一个 deque：自动回退 NPU→GPU→CPU 时，每一档的启动/报错行
     * 都累加进同一条流，用户直接看到完整回退全过程，不用切档回看。
     *
     * 为什么加锁：快速「停止→启动」时上一个 pump 协程不一定马上退出，
     * 两个协程可能短暂同时碰这个 deque，所以写/读/清空都走 synchronized(logRingLock)。
     */
    private val logRing = ArrayDeque<String>()
    private val logRingLock = Any()

    /** 读当前共用日志的全文（线程安全快照） */
    private fun sharedLogSnapshot(): String = synchronized(logRingLock) { logRing.joinToString("\n") }

    /** 往共用环形缓冲追加一行批，超过 [LOG_RING_LINES] 丢最旧的；返回全文供写状态 */
    private fun sharedLogAppend(lines: List<String>): String = synchronized(logRingLock) {
        lines.forEach { logRing.addLast(it) }
        while (logRing.size > LOG_RING_LINES) logRing.removeFirst()
        logRing.joinToString("\n")
    }

    /** 无条件写「共用报错」（清空 = 传空串；重新扫描时用；启动新轮 / 手动停止在各自处直接 copy） */
    private fun LocalEngineState.sharedErrorSet(message: String): LocalEngineState =
        copy(error = message)

    /**
     * 前置检查失败（没权限 / 引擎文件缺失 / 模型文件不合格）：
     * 这是一次「全新意图」但连进程都没起 —— 把共用日志环形缓冲与状态一起清空，
     * 只留这条报错给人看。切到该档（视图跟启动意图走），running/starting 复位。
     */
    private fun precheckFail(device: LocalDevice, message: String) {
        synchronized(logRingLock) { logRing.clear() }
        _state.update {
            it.copy(
                device = device,
                logTail = "",
                error = message,
                notice = "",
                starting = false,
                running = false
            )
        }
    }

    /** attach 只做一次：Activity 旋转/重建会再调 onCreate，绝不能重来一遍（否则会误杀引擎） */
    private var attached = false

    /** 当前引擎子进程 pid（-1 = 没有） */
    private var childPid = -1

    // ==================== 目录 / 权限 ====================

    private fun rootDir() = File(Environment.getExternalStorageDirectory(), ROOT_NAME)

    private fun modelDir() = File(rootDir(), MODELS_NAME)

    /**
     * 幂等：确保 `/storage/emulated/0/AI角色扮演/模型` 存在。
     *
     * 为什么必须单独抽出来：`mkdirs()` 失败**不抛异常**、只返回 false，
     * 原来那句 `runCatching { if (!dir.exists()) dir.mkdirs() }` 会把失败**完全吞掉** ——
     * 全新安装时还没授「所有文件访问权限」，目录就静默建不出来；
     * 而授权后返回 App 只走 rescan()（原先不建目录），于是必须重启 App 才看得到目录。
     * 现在 attach() 与 rescan() 都会调用本方法：授权后立即生效，失败也会留下日志。
     */
    private fun ensureModelDirs(): Boolean {
        val root = rootDir()
        val dir = modelDir()
        val ok = (root.isDirectory || root.mkdirs()) && (dir.isDirectory || dir.mkdirs())
        if (!ok) {
            Log.w(
                TAG,
                "建目录失败（多半是缺「所有文件访问权限」）：${dir.absolutePath}" +
                    " ｜ 根目录存在=${root.exists()} 模型目录存在=${dir.exists()}"
            )
        }
        return ok
    }

    private fun engineFile(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, ENGINE_EXE)

    private fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    override fun openStoragePermission(context: Context) {
        val ctx = context.applicationContext
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
                add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(ctx.packageManager) != null) {
                runCatching { ctx.startActivity(intent) }
                return
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun attach(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        // Activity 旋转/重建会再走一次 onCreate → 这里必须只初始化一次。
        // （踩过的坑：重复初始化会执行下面的 killStaleEngine，把自己刚拉起来的引擎 SIGKILL 掉，
        //   界面上表现为「引擎启动几秒后自己退出 code=137」。）
        Log.i(TAG, "attaching for first time...")
        attached = true
        val root = rootDir()
        val dir = modelDir()
        _state.update {
            it.copy(
                attached = true,
                enginePresent = engineFile(ctx).exists(),
                rootDir = root.absolutePath,
                modelDir = dir.absolutePath
            )
        }
        // 需求 #2：安装/首次启动就自动把目录建出来（没权限时只打日志，不崩）
        ensureModelDirs()
        killStaleEngine(ctx)
        rescan()
    }

    /**
     * 上次 App 被杀（或用户强退）时引擎可能还挂着，占着 8080 端口和几个 G 内存。
     * 这里按上次记录下来的 pid 清一次 —— 但绝不碰本进程自己的子进程。
     */
    private fun killStaleEngine(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pid = sp.getInt(KEY_PID, -1)
        if (pid > 0 && pid != childPid && pid != android.os.Process.myPid()) {
            runCatching {
                if (File("/proc/$pid").exists()) {
                    Log.i(TAG, "清理上次残留的引擎进程 pid=$pid")
                    android.os.Process.sendSignal(pid, 9)
                }
            }
        }
        sp.edit().remove(KEY_PID).apply()
    }

    // ==================== 模型扫描 ====================

    /** "xxx-00002-of-00003.gguf" —— 分片的第 2 片起，不单独列出来 */
    private val shardRegex = Regex("""-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

    private fun shardInfo(name: String): Pair<Int, Int>? {
        val m = shardRegex.find(name) ?: return null
        return (m.groupValues[1].toIntOrNull() ?: 1) to (m.groupValues[2].toIntOrNull() ?: 1)
    }

    /**
     * P2/P9：设备选择写进状态流（切页再回来时从这里恢复，不再弹回 CPU）。
     *
     * 日志/报错改成三档**共用**后（用户要求），切档**不再**切换日志槽位 ——
     * 共用日志始终显示同一条流（含自动回退全过程），error 也是共用的一份。
     * 这里只更新「当前选中档位」，供界面高亮胶囊跟随。
     */
    fun selectDevice(device: LocalDevice) {
        _state.update { st ->
            st.copy(device = device)
        }
    }

    /**
     * 反馈：选好模型 → 启动 → 停止，选择会跳回列表第一个（切页回来也一样）。
     * 和 selectDevice 同一套路：把「用户挑中的模型」也写进状态流，
     * 停止引擎只清 activeModel，selectedModel 保持不变，界面就停在用户挑的那个上。
     */
    fun selectModel(path: String) {
        _state.update { it.copy(selectedModel = path) }
        // ⑨ 修复：真机包切模型也同步持久化到 AppSettings.modelId（从模型文件名提取，去掉 .gguf 后缀）
        val modelId = path.substringAfterLast('/').removeSuffix(".gguf")
        appContext?.let { ctx ->
            com.example.roleplay.data.AppSettings.get(ctx).update { s ->
                s.copy(modelId = modelId)
            }
        }
    }

    override fun rescan() {
        val ctx = appContext ?: return
        _state.update { it.copy(scanning = true) }
        scope.launch {
            // 授权后返回 App 会走到这里（ON_RESUME → rescan），顺手把目录补齐 —— 不用重启 App
            ensureModelDirs()
            val root = rootDir()
            val dir = modelDir()
            // 两个位置都扫：/storage/emulated/0/AI角色扮演/模型/ 和 /storage/emulated/0/AI角色扮演/
            val all = (dir.listFiles()?.toList().orEmpty() + root.listFiles()?.toList().orEmpty())
                .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }

            val models = all
                .filter { f -> (shardInfo(f.name)?.first ?: 1) == 1 }
                .map { first ->
                    val info = shardInfo(first.name)
                    val parts = info?.second ?: 1
                    val total = if (parts > 1) {
                        // 前缀 = 去掉 "-00001-of-0000N.gguf" 之后的部分，用它把同组切片找齐
                        val m = shardRegex.find(first.name)
                        val prefix = if (m != null) first.name.substring(0, m.range.first) else first.name
                        all.filter { it.name.startsWith(prefix) }.sumOf { it.length() }
                    } else {
                        first.length()
                    }
                    LocalModel(first.absolutePath, first.name, total, parts)
                }
                .sortedBy { it.name.lowercase() }

            _state.update {
                // 重新扫描 = 一次「重新来过」：顺手清掉**共用**报错
                // （典型场景：NPU 报过 code=-1，用户重扫一遍再启动，旧报错不该继续挂着）。
                // 需求 4：扫描结果不经过任何白名单，选中项按「上次挑的那个文件名」恢复。
                it.sharedErrorSet("").copy(
                    models = models,
                    scanning = false,
                    hasPermission = hasAllFilesAccess(ctx),
                    selectedModel = restoredSelection(it, models)
                )
            }
        }
    }

    /** gguf 文件名 → 持久化用的 id（和 selectModel 写进 AppSettings 的口径完全一致） */
    private fun modelIdOf(fileName: String): String =
        fileName.replace(Regex("""\.gguf$""", RegexOption.IGNORE_CASE), "")

    /**
     * 冷启动 / 重新扫描后，决定「列表里该选中哪一个」（需求 4 的一环）。
     *
     * 背景：以前 selectedModel 只在会话内有效，App 一重启就回到空串，界面跳回列表第一个，
     * 用户上次挑的模型白挑了。这里三级回退：
     *   ① 会话内当前选中的（还在列表里就保持不动，绝不抢用户的选择）；
     *   ② [AppSettings.modelId]（= 文件名去 .gguf，由 selectModel 写入）能对上就用它；
     *   ③ 都找不到（被删/改名）才退回列表第一个。
     * 全程只按**文件名**匹配本地扫描结果，不查 ModelRegistry 之类的内置清单 ——
     * 任意合规 gguf 丢进目录都该能选上、能启动。
     */
    private fun restoredSelection(st: LocalEngineState, models: List<LocalModel>): String {
        if (models.isEmpty()) return ""
        st.selectedModel.takeIf { p -> models.any { it.path == p } }?.let { return it }
        val remembered = runCatching {
            appContext?.let { com.example.roleplay.data.AppSettings.get(it).current.modelId }
        }.getOrNull().orEmpty()
        if (remembered.isNotBlank()) {
            models.firstOrNull { modelIdOf(it.name).equals(remembered, ignoreCase = true) }
                ?.let { return it.path }
        }
        return models.first().path
    }

    // ==================== 机型 / HTP 版本 / OpenCL 探测 ====================

    /**
     * 一次探测的结论（进程内缓存，机型不会中途变）。
     *
     * @param socDesc       给人看的机型摘要
     * @param htpArch       选定并会通过 `GGML_HEXAGON_ARCH` 下发的 HTP 版本（0 = 没探到，交给引擎自己查）
     * @param htpReason     为什么选它（依据原文，进日志）
     * @param availableArches jniLibs 里实际存在的 libggml-htp-v*.so 版本号
     * @param icdPath       设备上的 Adreno OpenCL 厂商 ICD 路径（找到才非空）
     */
    private data class BackendProbe(
        val socDesc: String,
        val htpArch: Int,
        val htpReason: String,
        val availableArches: List<Int>,
        val icdPath: String
    )

    @Volatile
    private var cachedProbe: BackendProbe? = null

    /** 读系统属性：先反射 SystemProperties（可能被非 SDK 接口策略挡掉），失败再退回 getprop 进程 */
    private fun sysProp(name: String): String {
        runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val v = c.getMethod("get", String::class.java).invoke(null, name) as? String
            if (!v.isNullOrBlank()) return v
        }
        return runCatching {
            val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        }.getOrDefault("")
    }

    /** 机型相关属性，按「信息量」从高到低读 */
    private fun socProps(): Map<String, String> = linkedMapOf(
        "ro.board.platform" to sysProp("ro.board.platform"),
        "ro.soc.model" to sysProp("ro.soc.model"),
        "ro.soc.manufacturer" to sysProp("ro.soc.manufacturer"),
        "ro.hardware" to sysProp("ro.hardware"),
        "ro.product.board" to sysProp("ro.product.board")
    ).filterValues { it.isNotBlank() }

    /**
     * 骁龙平台代号/型号 → Hexagon HTP 架构版本（保守子集，只收有把握的）。
     *
     * 依据：高通公开的代际对应 —— 8 Elite(SM8750/Sun)=v79、8 Gen 3(SM8650/Pineapple)=v75、
     * 8 Gen 2(SM8550/Kalama)=v73。8+ Gen 1/8 Gen 1/888 的 DSP 实际是 v66~v69，低于上游
     * v73 下限，映到 73 只是「尝试失败后自动回退」，与引擎自查时 cap 到 v73 的结果一致
     * （K40 = SM8350 属此类，保留原映射不动）。
     * 拿不准的型号（sm7635、sm7475、cliffs）**故意不收** —— 让引擎自己查，比猜错强。
     * 真机教训：sm8650 曾被误标 v79，强制下发了不匹配的 skel，魅族 21 Pro 的 NPU 直接
     * 起不来（旧版没有强制机制、引擎自查到 v75，一切正常）。
     * **优先级低于「厂商 DSP 库探测」**：设备上真躺着的 libQnnHtpV79Skel.so 比这张表可信。
     */
    private val socArchTable = mapOf(
        // 8 Elite（Sun 系，v79）
        "sm8750" to 79, "sm8750p" to 79, "sun" to 79,
        // 8 Gen 3 / 7+ Gen 3（Pineapple 系，v75）
        "sm8650" to 75, "sm8650p" to 75, "pineapple" to 75, "sm7675" to 75,
        // 8 Gen 2（Kalama）/ 7 Gen 3（v73）
        "sm8550" to 73, "sm8550p" to 73, "kalama" to 73, "sm7550" to 73,
        // 8+ Gen 1 / 8 Gen 1 / 888 / 7 系（DSP v66~v69 低于下限：尝试后回退）
        "sm8475" to 73, "cape" to 73, "sm8450" to 73, "waipio" to 73, "taro" to 73,
        "sm8350" to 73, "lahaina" to 73, "sm7325" to 73, "yupik" to 73, "sm6375" to 73
    )

    /** 厂商 DSP 库文件名里的 HTP 版本：libQnnHtpV79Skel.so / libQnnHtpV75Stub.so / libggml-htp-v73.so */
    private val vendorHtpNameRegex = Regex("""lib[a-z0-9_]*htpv(\d+)[a-z0-9_]*\.so""", RegexOption.IGNORE_CASE)

    /** 设备上可能放厂商 HTP skel 的目录（被 namespace 挡住也只会 listFiles() 返回 null，不会崩） */
    private val vendorHtpDirs = listOf(
        "/vendor/lib64/rfsa/adsp",
        "/vendor/lib/rfsa/adsp",
        "/vendor/dsp/cdsp",
        "/vendor/dsp/adsp",
        "/vendor/lib64",
        "/vendor/lib",
        "/odm/lib64",
        "/odm/lib/rfsa/adsp",
        "/system/lib64"
    )

    /**
     * 随包内置的 **Adreno OpenCL 实现本体**文件名 —— 就是 `libOpenCL.so` 本身。
     *
     * 它取自设备 `/vendor/lib64/libOpenCL.so`（115KB，SONAME 也是 libOpenCL.so，
     * 导出全部 147 个 `cl*` 公开 API 以及 `clIcdGetPlatformIDsKHR`）：厂商这份
     * **既是 ICD 也是完整实现**，所以引擎直接链它就够了，不需要中间那层 loader。
     *
     * 为什么必须随包、为什么不能再用 loader：
     *   · App 派生的引擎子进程在 (default) linker namespace，permitted_paths 不含
     *     /vendor/lib64，`/vendor` 路径上的库一律加载不到；nativeLibraryDir 里的可以
     *     （引擎自己的 libllama* 就是这么起来的）。
     *   · 早先包里放的是高通魔改过的 ICD loader（`khrIcd*` 符号、写死
     *     /system/vendor/lib64/egl），它**不认 OCL_ICD_FILENAMES**，于是平台数恒为 0。
     *
     * 驱动的真正实现（libgsl.so / libCB.so / libllvm-glnext.so）留在 /vendor/lib64，
     * 由实现内部调 `android_load_sphal_library` 走 sphal 命名空间加载
     * （libCB 还依赖 libgralloc.qti.so 等厂商 HAL，只有 sphal 这条路能拿到）。
     */
    private val BUNDLED_ICD_NAME = "libOpenCL.so"

    /**
     * Adreno OpenCL 真正干活的驱动库（设备 /vendor/lib64 下，随包不分发）。
     *
     * 厂商那份 libOpenCL.so 只是"前台"，它内部用 android_load_sphal_library 去取这几个：
     *   · libgsl.so            图形/计算服务层（2.1MB）
     *   · libCB.so             Adreno 编译器后端（2.9MB）
     *   · libllvm-glnext.so    LLVM 编译器（14MB）
     *   · libq3dtools_adreno.so 调试/工具层（0.23MB，设备上位于 /vendor/lib64/egl/）
     * 这句 sphal 加载在 App 的 untrusted_app 上下文里会**静默失败**：平台仍然枚举得出来
     * （clGetPlatformIDs 正常），但任何内核都编不出来（clBuildProgram 恒返回 -6）。
     * 所以启动前把它们拷到 app 可写的 filesDir/ocllibs 并挂进 LD_LIBRARY_PATH，
     * 让厂商实现的普通 dlopen 回退路径按名字能找到。
     * 驱动自己的依赖（libion/libsync/libz/libgralloc.qti 等）都在 /system/lib64，
     * 子进程默认可见，不需要一起拷。
     */
    private val OPENCL_DRIVER_LIBS = listOf(
        "libgsl.so",
        "libCB.so",
        "libllvm-glnext.so",
        "libq3dtools_adreno.so",
    )

    /**
     * Adreno OpenCL 厂商 ICD：打包进来的 libOpenCL.so 是 **Khronos ICD loader**（khrIcd* 符号），
     * 它按 OCL_ICD_FILENAMES 去 dlopen 厂商 ICD。
     *
     * 这条列表只用于**兜底**：首选是随包内置的 [BUNDLED_ICD_NAME]（见 [probeBackends]）。
     * 实测（SM8350 的 Redmi K40）：厂商 ICD 就叫 `/vendor/lib64/libOpenCL.so`（115KB），
     * 设备上**没有** libOpenCL_adreno.so。历史命名留在后面兜底。
     */
    private val adrenoIcdCandidates = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/vendor/lib64/libOpenCL_adreno.so",
        "/vendor/lib/libOpenCL_adreno.so",
        "/system/vendor/lib64/libOpenCL_adreno.so",
        "/odm/lib64/libOpenCL_adreno.so"
    )

    /** 扫设备上的厂商 QNN HTP skel —— 这是「这台机器的 DSP 到底是第几代」最硬的本地证据 */
    private fun detectVendorHtpArch(): Pair<Int, String> {
        var best = 0
        var bestPath = ""
        for (dir in vendorHtpDirs) {
            val files = runCatching { File(dir).listFiles() }.getOrNull() ?: continue
            for (f in files) {
                val m = vendorHtpNameRegex.find(f.name) ?: continue
                val v = m.groupValues[1].toIntOrNull() ?: continue
                if (v in 60..99 && v > best) {
                    best = v
                    bestPath = f.absolutePath
                }
            }
        }
        return best to bestPath
    }

    /** jniLibs 里实际存在的 libggml-htp-v*.so（只在这几个版本里选，选了别的也加载不了） */
    private fun availableHtpArches(nativeDir: String): List<Int> {
        val regex = Regex("""^libggml-htp-v(\d+)\.so$""", RegexOption.IGNORE_CASE)
        return runCatching { File(nativeDir).listFiles() }.getOrNull().orEmpty()
            .mapNotNull { regex.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
    }

    /**
     * 探测一次并把结论缓存下来。
     *
     * 为什么必须做这件事（真机日志的根因）：引擎查不到 HTP 版本时会**硬退到 v73**
     * （ggml-hexagon.cpp:8699-8711 `failed to query HTP version (err %d) defaulting to v73`），
     * 而 skel 版本必须和设备 DSP 代际匹配（会话 URI 就是 `file:///libggml-htp-v<arch>.so...`，
     * ggml-hexagon.cpp:4578）——真机日志里紧接着的
     * `forcing ndev to 1 for SoCs archs lower than v75`（ggml-hexagon.cpp:8932-8937）
     * 正好证明它当时按 v73 在跑。上游留了 `GGML_HEXAGON_ARCH` 这个覆盖开关
     * （ggml-hexagon.cpp:8690/8712-8717），我们按真实机型把它显式喂进去。
     */
    private fun probeBackends(ctx: Context): BackendProbe {
        cachedProbe?.let { return it }
        synchronized(this) {
            cachedProbe?.let { return it }
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val arches = availableHtpArches(nativeDir)

            val props = socProps()
            val (vendorArch, vendorPath) = detectVendorHtpArch()

            // 机型摘要：属性 + 版本（有些 ROM 把 ro.board.platform 抹了，所以要容忍空）
            val socBits = buildList {
                props["ro.soc.model"]?.let { add(it) }
                props["ro.board.platform"]?.let { add("($it)") }
                props["ro.soc.manufacturer"]?.let { if (it.isNotBlank()) add("· $it") }
            }
            val socDesc = socBits.joinToString(" ").ifBlank { "未知机型（属性读不到）" }

            // ① 设备侧厂商库（最可信）→ ② 代号/型号表 → ③ 探不到就不设，交给引擎自己查
            var detected = vendorArch
            var reason = if (vendorArch > 0) "厂商 DSP 库 $vendorPath" else ""
            if (detected == 0) {
                for ((k, v) in socArchTable) {
                    val hit = props.any { (_, raw) ->
                        val t = raw.lowercase().substringBefore('-').substringBefore(' ').trim()
                        t == k || (k.length >= 5 && t.startsWith(k))
                    }
                    if (hit) {
                        detected = v
                        reason = "机型表命中 $k → v$v"
                        break
                    }
                }
            }

            val chosen = when {
                detected == 0 -> 0
                arches.isEmpty() -> 0
                arches.contains(detected) -> detected
                // 就近取：优先不高于实际代际的最高版本，否则取最低的（差太远本来也跑不起来）
                else -> arches.lastOrNull { it < detected } ?: arches.first()
            }
            if (chosen != 0 && chosen != detected) {
                reason = "$reason；但 jniLibs 里没有 v$detected，就近取 v$chosen（可用：${arches.joinToString("/") { "v$it" }}）"
            } else if (chosen != 0) {
                reason = "$reason（可用：${arches.joinToString("/") { "v$it" }}）"
            } else {
                reason = "探不到 HTP 版本，交给引擎自己查询（可用：${arches.joinToString("/") { "v$it" }.ifBlank { "无" }}）"
            }

            // 优先用**随包的厂商 ICD 本体**（nativeLibraryDir 对子进程可见，直接可加载）；
            // /vendor 原路径只作兜底 —— 对 App 派生的子进程会被 linker namespace 挡住。
            val bundledIcd = File(nativeDir, BUNDLED_ICD_NAME)
            val icd = (listOf(bundledIcd.absolutePath) + adrenoIcdCandidates)
                .firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }.orEmpty()

            val p = BackendProbe(socDesc, chosen, reason, arches, icd)
            Log.i(
                TAG, "机型探测：$socDesc｜属性=${props.entries.joinToString(",") { "${it.key}=${it.value}" }}" +
                    "｜HTP=${if (chosen > 0) "v$chosen" else "未定"}（$reason）" +
                    "｜OpenCL ICD=${icd.ifBlank { "未在设备上找到厂商 OpenCL ICD（/vendor/lib64/libOpenCL.so 等）" }}"
            )
            cachedProbe = p
            return p
        }
    }

    /**
     * 启动前把「这个 gguf 能不能用」判掉（需求 4：要给人看得懂的原因，而不是 code=-1）。
     *
     * @return null = 没问题；非 null = 直接显示给用户的错误文案
     */
    private fun validateModelFile(model: LocalModel): String? {
        val f = File(model.path)
        if (!f.isFile) {
            return "模型文件不存在：${model.name}（可能已被删除/改名，点「重新扫描」再看看）"
        }
        if (f.length() < 16) {
            return "模型文件是空的或不完整：${model.name}"
        }
        // GGUF 文件头 4 字节魔数；下到一半/改错后缀的都能在这一步拦下
        val magic = runCatching {
            f.inputStream().use { ins ->
                val b = ByteArray(4)
                if (ins.read(b) != 4) "" else String(b, Charsets.US_ASCII)
            }
        }.getOrDefault("")
        if (magic != "GGUF") {
            return "这不是有效的 .gguf：${model.name}（文件头不是 GGUF，下载不完整或后缀被改过？）"
        }
        // 分片模型：把同组切片都点一遍名，缺片就别丢给引擎
        if (model.parts > 1) {
            val m = shardRegex.find(model.name)
                ?: return "分片模型命名不对：${model.name}（应形如 xxx-00001-of-0000N.gguf）"
            val prefix = model.name.substring(0, m.range.first)
            for (i in 1..model.parts) {
                val name = String.format("%s-%05d-of-%05d.gguf", prefix, i, model.parts)
                if (!File(f.parentFile, name).isFile) {
                    return "分片缺失：$name（把同组切片都放进模型目录）"
                }
            }
        }
        return null
    }

    // ==================== 引擎进程 ====================

    override fun start(model: LocalModel, device: LocalDevice) {
        // 用户手动点「启动引擎」= 新一轮意图：清掉上一轮的自动回退提示，回退链从头开始
        _state.update { it.copy(notice = "") }
        startInternal(model, device, mutableSetOf(), "")
    }

    /**
     * 组装引擎**完整启动参数**——startInternal 的唯一口径。
     *
     * 装配顺序：
     * 基础参数（模型/监听/上下文/线程/KV 量化/关思考/防复读/no-warmup）
     * → 档位专属参数（device.extraArgs，NPU 档带 `--device HTP`）
     * → 设备名归一化（normalizeDeviceArgs：CPU 档补 `-dev none`、GPU 补 `-dev GPUOpenCL`、
     *   NPU 归一为 HTP0，见其函数注释）。
     */
    private fun buildEngineArgs(ctx: Context, device: LocalDevice, modelPath: String): List<String> {
        // 上限 6（大核超订反拖慢 llama.cpp），下限 1（双核/未来小机型不再被强塞 4 线程超订）。
        // K40（8 核）仍取 6，行为不变；2 核机型取 2，弱机 CPU 档也稳。
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
        // 用户追加需求：上下文长度接上设置页的「上下文长度」。
        // 以前这里固定写 CTX_SIZE(2048)，而设置里的 contextSize（默认 8192）
        // 只参与历史 token 预算换算 —— 用户改了它，引擎实际上下文纹丝不动。
        // 现在直接读同一份设置（AppSettings.get(ctx).current.contextSize，
        // 与 SettingsScreen 的滑块同源），读不到就退回 CTX_SIZE；
        // 并做上下限夹取：太小会频繁截断，太大在 8GB 机型上会把 KV cache 撑爆。
        val ctxSize = runCatching {
            com.example.roleplay.data.AppSettings.get(ctx).current.contextSize
        // 下限取 2048 而不是 512：角色卡 system prompt 本身常 600~900 token，
        // 再加 max_tokens（默认 1024），-c 过小会触发 context shift 把角色卡挤掉，
        // 表现出来就是"角色突然失忆、人设崩了"。2048 是安全底线。
        }.getOrDefault(CTX_SIZE).coerceIn(2048, 32768)
        val args = mutableListOf(
            engineFile(ctx).absolutePath,
            "-m", modelPath,
            "--host", LOCAL_HOST,
            "--port", LOCAL_PORT.toString(),
            "-c", ctxSize.toString(),
            "-np", "1",
            "-t", threads.toString(),
            "-ngl", device.ngl.toString(),
            // 用户要求追加的启动参数（均为上游长期稳定存在的选项）：
            //   KV cache 量化成 q8_0 —— 8GB 机型上把 KV 内存占用大致砍半，
            //   同样的内存能塞下更长的上下文；对角色扮演的文本质量影响可忽略。
            // 注：这里以前还显式写过 `--jinja`，已按用户要求去掉 ——
            //   b11523 里 --jinja 本来就是默认 enabled（写上去是 no-op），
            //   保持默认即可，不再把这个默认项写进参数表。
            "--cache-type-k", "q8_0",
            "--cache-type-v", "q8_0",
            // 用户要求：把"关思考"从请求体的 chat_template_kwargs.enable_thinking
            // 挪到启动参数。实测 b11523 支持 `-rea, --reasoning [on|off|auto]`，
            // 服务端一次生效，比每轮往 body 里塞模板参数更干净。
            "--reasoning", "off",
            // 第 5 项「真机防复读参数与 PC 对齐」：
            // PC 端 run_servers.bat 一直带着 --repeat-last-n 512（外加一组 dry-*），
            // 而真机这边一直是引擎默认值 64 —— 同样开着 repeat_penalty 1.1，
            // 惩罚只看最近 64 个 token，长回复里更早的复读惩罚不到，表现就是真机更容易
            // 车轱辘话。512 与 PC 一致，`--repeat-last-n` 已用设备上 b11523 的
            // `llama-server --help` 全文核实存在（默认 64）。
            "--repeat-last-n", "512",
            "--no-warmup"
        )
        args += device.extraArgs

        // 设备选择归一化（见函数注释）：既兜住 GPU 档「不点名就静默落到 NPU」，
        // 也兜住需求 7 的自定义参数写成 `--device HTP` 这种前缀写法。
        // 放在最后、覆盖前面所有来源（含用户自定义参数），保证引擎一定拿到合法设备名。
        normalizeDeviceArgs(args, device)
        return args
    }

    /**
     * 真正干活的启动函数。
     *
     * @param tried 本轮已经试过并失败的档位（自动回退用，防止「回退→再回退」死循环）
     * @param note  「引擎替你做了什么」的提示：自动回退时写清楚，
     *              用户手动启动传空串（那种情况不该有任何意外动作）
     */
    private fun startInternal(
        model: LocalModel,
        device: LocalDevice,
        tried: MutableSet<LocalDevice>,
        note: String,
        archOverride: Int = 0,

        /**
         * 降档重试的「上一轮」代际：只喂给诊断日志的「降档重试（上一轮强制 vXX 失败）」，
         * 不参与任何逻辑 —— 修的是那行日志曾把**新档位**误写成上一轮的老毛病
         * （旧实现把 archOverride 同时当 retryFrom 用，重试 v73 时日志反说「上一轮 v73」）。
         */
        prevHtpArch: Int = 0
    ) {
        val ctx = appContext ?: return
        if (!hasAllFilesAccess(ctx)) {
            // 前置检查失败（权限）：新一轮意图，共用日志/报错一起清空，只留这条报错。
            // 报错改共用后不再按档隔离 —— 谁在哪个档按的启动都一样，直接显示在共用行上。
            precheckFail(device, "还没有「所有文件访问权限」，先点上面的「去授权」")
            return
        }
        val exe = engineFile(ctx)
        if (!exe.exists()) {
            precheckFail(device, "引擎文件缺失：${exe.absolutePath}")
            return
        }
        // 需求 4：文件不存在 / 不是 gguf / 分片缺失，都在这里给可读原因
        validateModelFile(model)?.let { problem ->
            precheckFail(device, problem)
            return
        }
        // 真机 GPU（Adreno OpenCL）：这里原本会因为「上一轮 OpenCL 不可用」直接跳过 GPU 档，
        // 那个结论已被推翻。真因是 App 派生的引擎子进程处于 (default) linker namespace，
        // 看不到 /vendor/lib64、拿不到厂商 ICD。现在厂商 ICD 本体（libOpenCL_adreno.so）
        // 随包放进 nativeLibraryDir，子进程能直接加载；它再用 android_load_sphal_library
        // 走 sphal 命名空间把 libgsl.so / libCB.so 拉起来（和 EGL 驱动同一套机制）。
        // 所以 GPU 档照常起，真起不来就交给下面的自动回退（NPU → GPU → CPU）。
        tried.add(device)
        // 手动启动（本轮第一档）= 全新意图：清掉共用日志/报错，从空白开始；
        // 自动回退（tried>1）与降档重试（archOverride>0）= 保留已累加的共用日志与
        // 上一档报错，只往后追加本档日志 —— 降档前的失败记录必须留给用户看。
        val isFallback = tried.size > 1 || archOverride > 0
        stopInternal(clearLogs = !isFallback)
        // 新一轮启动：递增代号。上一个 pumpLogs 协程即使还没退出，它的补写也会被丢弃，
        // 不会把上一轮的日志尾巴盖到这一轮。
        val gen = ++logGeneration
        // 本档在共用环形缓冲里的日志起点（记在 stopInternal 之后、本档写日志之前）——
        // 失败分类只看本档自己打出来的行（见 rawLogOf）。关键：NPU→GPU→CPU 共用一条流，
        // 若 GPU 档把 NPU 档的「invalid device: HTP0」行也扫进去，会命中 ARGS 的
        // "invalid device:" 而被误判成「参数错误、不回退」，回退链就此停在 GPU、到不了 CPU。
        val stageLogBase = synchronized(logRingLock) { logRing.size }
        // 机型 / HTP / OpenCL 探测结论（进程内只算一次）：既写进状态给界面看，也进日志
        val probe = probeBackends(ctx)
        _state.update { st ->
            st.copy(
                starting = true, running = false,
                activeModel = model.name, device = device,
                // 共用日志：手动启动 stopInternal 已把环形缓冲+logTail 清成空；
                // 自动回退则原样保留（上面 stopInternal(clearLogs=false) 没清），
                // 这里直接沿用 st.logTail，NPU→GPU→CPU 的完整回退过程一条流看全。
                logTail = st.logTail,
                // 共用报错：手动启动 = 新意图，清空重来；自动回退 = 保留上一档的报错
                // （「启动有报错则继续显示」），用户能一直看到是哪一档先失败的。
                error = if (isFallback) st.error else "",
                // 自动回退提示（用户手动启动时 note 为空串，等于没这句话）；
                // 探测结论覆盖式写入，界面「当前设备」下方那行机型信息用它
                notice = note,
                socDesc = probe.socDesc,
                htpArch = probe.htpArch
            )
        }
        scope.launch {
            try {
                val nativeDir = ctx.applicationInfo.nativeLibraryDir
                // HTP vendor 库运行时副本（NPU code=1「failed to load libcdsprpc.so」的根治）：
                // libcdsprpc.so / libvmmem.so / vendor.qti.hardware.dsp@1.0.so 三件套在设备
                // /vendor/lib64（高通 vendor 分区），而 app 进程的 linker namespace 看不到
                // /vendor —— 引擎起 HTP 会话时 dlopen 失败，NPU 直接不可用。
                // 解法与 libOpenCL.so 同一套路：随引擎一起打进 jniLibs，启动时把三个库
                // 复制到 app 可写的 filesDir/htplibs 并恢复原文件名，再把这个目录挂在
                // LD_LIBRARY_PATH 最前面（见下方 env 设置）。
                // 注意：vendor.qti.hardware.dsp@1.0.so 不能以原名进 jniLibs —— Android 安装时
                // 解包会**跳过文件名带 @ 的库**（真机实测：APK 里有、lib/arm64 里没解出来），
                // 所以 jniLibs 里它叫 vndk_dsp_10.so，运行时拷出来时还原原名。
                val htpDir = File(ctx.filesDir, "htplibs").apply { mkdirs() }
                fun copyHtpLib(dstName: String, srcName: String) {
                    val target = File(htpDir, dstName)
                    val src = File(nativeDir, srcName)
                    if (src.exists() && (!target.exists() || target.length() != src.length())) {
                        runCatching { src.copyTo(target, overwrite = true) }
                    }
                }
                copyHtpLib("libcdsprpc.so", "libcdsprpc.so")
                copyHtpLib("libvmmem.so", "libvmmem.so")
                copyHtpLib("vendor.qti.hardware.dsp@1.0.so", "vndk_dsp_10.so")
                // HTP skel（libggml-htp-v<arch>.so）也必须实物落在 htpDir：
                // CDSP 侧按 `ADSP_LIBRARY_PATH` 单目录 + `/cdsp/` 或 `./` 拼路径找 skel
                // （见下方 ADSP_LIBRARY_PATH 的定案注释），所以这里两种形式各放一份。
                // 四个代际全拷（v73/v75/v79/v81）：探测值、GGML_HEXAGON_ARCH 覆盖和
                // 降档重试都会换 arch，拷全了才不会出现"重试时文件刚巧不在"。
                val htpDomainDir = File(htpDir, "cdsp").apply { mkdirs() }
                val skelRegex = Regex("""libggml-htp-v\d+\.so""")
                File(nativeDir).listFiles()?.forEach { src ->
                    if (!skelRegex.matches(src.name)) return@forEach
                    for (dst in listOf(File(htpDir, src.name), File(htpDomainDir, src.name))) {
                        if (!dst.exists() || dst.length() != src.length()) {
                            runCatching { src.copyTo(dst, overwrite = true) }
                        }
                    }
                }
                // GPU 档的同类根治（详见 OPENCL_DRIVER_LIBS 的注释）：App 上下文里厂商实现
                // 内部那句 android_load_sphal_library 取不到驱动编译器，表现为"平台有、内核全编不出"。
                // 把驱动库拷进 app 私有目录再挂上 LD_LIBRARY_PATH。
                val oclDir = File(ctx.filesDir, "ocllibs").apply { mkdirs() }
                for (name in OPENCL_DRIVER_LIBS) {
                    val src = listOf(File("/vendor/lib64", name), File("/vendor/lib64/egl", name))
                        .firstOrNull { it.exists() && it.canRead() } ?: continue
                    val target = File(oclDir, name)
                    if (!target.exists() || target.length() != src.length()) {
                        runCatching { src.copyTo(target, overwrite = true) }
                    }
                }
                // 第 2 项（兼容后续其他机型，最次 CPU 能跑）：线程数按「本机实际核数」取，
                // 完整启动参数装配已抽成 buildEngineArgs（见函数注释）：startInternal 与
                // 其它调用点共用同一份口径，两边永远一致。
                val args = buildEngineArgs(ctx, device, model.path)

                Log.i(TAG, "启动引擎：" + args.joinToString(" "))

                val pb = ProcessBuilder(args)
                    .directory(ctx.filesDir)
                    .redirectErrorStream(true)
                // htpDir 排最前：HTP 三件套的原名（带 @ 的那个）只有运行时副本里有；
                // nativeDir 兜底其余 16 个引擎库（libllama-* / libggml-* / libOpenCL.so …）
                pb.environment()["LD_LIBRARY_PATH"] =
                    oclDir.absolutePath + File.pathSeparator +
                        htpDir.absolutePath + File.pathSeparator + nativeDir
                // 第 7 项核查（对照官方 docs/backend/snapdragon/README.md、linux.md 与
                // scripts/snapdragon/run.py 的 adb/ssh/本地三条路径）：官方**都**会显式设置
                // ADSP_LIBRARY_PATH，我们此前只设了 LD_LIBRARY_PATH。
                // 原理：NPU 走 unsigned PD，libggml-hexagon.so 是让 CDSP 按
                // file:///libggml-htp-v<arch>.so 从 HLOS 侧加载 skel 的，该查找依赖这个变量；
                // 而设备自带的 /vendor/lib64/rfsa/adsp 里只有高通自己的 libQnnHtpV75Skel.so，
                // 没有我们的 libggml-htp-v75.so —— 不设它 NPU 很可能直接起不来。
                //
                // 【2026-10-12 真机定案】这个变量**只能给一个目录**，不能给冒号列表：
                // 写入 name[:name] 时，CDSP 侧 apps_std 会把整串（含冒号）当成单个目录名，
                // 拼出 `<nativeDir>:<htpDir>/cdsp/./libggml-htp-v75.so` 这种不存在的路径，
                // skel 永远找不到 —— 表现就是 remote_handle_open_domain 报
                // `0x80000406` + `dlerror ... cannot open libggml-htp-v75.so, errno 2`，
                // 魅族 21 Pro（SM8650/Android 16）实测两版引擎库、两代 skel 全是这个码，
                // 而官方老包（v1.0.6）能起来，差别只在它没写冒号列表。
                // 落地方案：skel 实物**落在** htpDir（并额外复制一份到 htpDir/cdsp，见上方
                // copyHtpSkel），这里只挂 htpDir 这一个目录，两种探测路径都能命中。
                pb.environment()["ADSP_LIBRARY_PATH"] = htpDir.absolutePath
                // 第 2 项「按官方 README 复查」新增：官方 runner（scripts/snapdragon/run.py:305）
                // 每次运行都会设 GGML_HEXAGON_OPPOLL=1，linux.md 示例里也有，只有我们漏了。
                // 后端默认 opt_oppoll=0（ggml-hexagon.cpp:116），语义是
                // `timeo = opt_oppoll ? 0 : DSPQUEUE_TIMEOUT(1000000)`（:4028 / htp-drv.h:88）——
                // 即 DSP 队列改轮询等待而非阻塞等待。零风险，且是官方默认口径。
                pb.environment()["GGML_HEXAGON_OPPOLL"] = "1"
                // 需求 1 的核心：不再让引擎「查不到 HTP 版本就硬退 v73」。
                // 上游留了 GGML_HEXAGON_ARCH 覆盖开关（ggml-hexagon.cpp:8690 读该变量，
                // 8712-8717 直接采用、跳过 htpdrv_get_arch 的失败分支），
                // 我们把探测到的真实版本显式喂进去；探测不到（chosen=0）时**不设**，
                // 保持原来的「引擎自己查」行为，不猜。
                // archOverride：降档重试时用低一代的值顶替探测值，只影响这一轮进程。
                val effectiveHtpArch = if (archOverride > 0) archOverride else probe.htpArch
                if (effectiveHtpArch > 0) {
                    pb.environment()["GGML_HEXAGON_ARCH"] = effectiveHtpArch.toString()
                }
                // DSP 域/会话显式化：NPU 档固定用物理核 0（默认域名 cdsp/domain 3，
                // ggml-hexagon.cpp:8578-8595 的静态回退表；设备名 "HTP0" 见 :8928）。
                // 写出来是为了：① 日志里能一眼看到用的是哪个域；② 以后要多核时改一处就行。
                if (device == LocalDevice.NPU) {
                    pb.environment()["GGML_HEXAGON_DEVICES"] = HTP_DEVICE_NAME
                }
                // 需求 5 的「能修就修」那一半：打包进来的 libOpenCL.so 是**高通版 ICD loader**
                // （.so 里可读字符串：vendor/qcom/proprietary/graphics/adreno200/cb/src/loader/icd.c、
                //  khrIcdVendorsEnumerateOnce、clGetICDLoaderInfoOCLICD、
                //  默认厂商库名 libOpenCL_adreno.so、OCL_ICD_FILENAMES），
                // 它自己按名字 dlopen 厂商 ICD，而 app 的 linker namespace 里没有那个名字
                // （实测多数机型叫 /vendor/lib64/libOpenCL.so，不是 libOpenCL_adreno.so）
                // → 一个平台都拿不到，于是「ggml_opencl: platform IDs not available.」。
                // 找到厂商库就用 OCL_ICD_FILENAMES 把绝对路径告诉 loader。
                // 注意：放在**所有档位**都下发，不只是 GPU 档 —— 引擎初始化时会枚举全部后端，
                // 只要 libggml-opencl.so 被加载就会走这一步，那时 device 还没轮到 GPU 档。
                if (probe.icdPath.isNotBlank()) {
                    pb.environment()["OCL_ICD_FILENAMES"] = probe.icdPath
                }
                // GPU 档踩到的坑：Adreno 的 OpenCL 编译器要往临时目录写中间文件，
                // 拿不到可写 TMPDIR 时 clBuildProgram 会返回 CL_OUT_OF_HOST_MEMORY(-6)
                // 且 build log 是空的（报诊断之前就失败）。App 进程的 TMPDIR 由 zygote
                // 指向 app 私有 cache，但派生子进程不保证继承，这里显式覆盖成
                // 一定可写的 cacheDir；HOME 一并给出，部分编译器会去找它。
                pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
                pb.environment()["HOME"] = ctx.filesDir.absolutePath
                // GPU 档在部分骁龙机型上**没法用**，原因已定位到驱动编译器本身（真机 SM8350 /
                // Adreno 660 / Android 14 实测）：Adreno 的 OpenCL 编译器编不过 llama.cpp 的
                // 一批内核 —— 其中 rms_norm(11031B) / mean(3394B) / sum_rows(3388B) /
                // ssm_scan(14474B) / mul_mv_f16_f32_l4(37919B) 五个直接把编译器打成 SIGSEGV
                // （整个进程一起死，无法在进程内捕获），另有约 31 个内核稳定返回
                // CL_OUT_OF_HOST_MEMORY(-6) 且 build log 只有 "Pass"。
                // 逐项排除过：编译选项、-cl-opt-disable、CL1.2/CL2.0、内存压力、SELinux 域、
                // OCL_ICD_FILENAMES/TMPDIR/LD_LIBRARY_PATH 均无关；`cl_khr_subgroups` 是支持的，
                // 但 `cl_khr_integer_dot_product` 不支持。即使把坏内核全部跳过、并容忍加载期失败，
                // 引擎也只在图计算阶段改为段错误 —— 所以这里不做任何规避，保持上游行为，
                // 让引擎快速失败并由上层回退到 CPU 档（见 classifyFailure / 设置页提示）。
                // 需求 1「要能诊断」：把这一轮**真正生效**的环境与参数写进本档日志。
                // 放在 env 全部设置完之后，写的就是 ProcessBuilder 里那份真身，
                // 不会出现「日志说的和实际跑的不一样」。
                seedDiagnosisLog(device, gen, probe, effectiveHtpArch, prevHtpArch, args, pb.environment())
                val p = pb.start()
                proc = p
                childPid = pidOf(p)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_PID, childPid).apply()

                logJob = scope.launch { pumpLogs(p, device, gen) }

                val pump = logJob
                scope.launch {
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    Log.i(TAG, "引擎进程结束 code=$code")
                    // 判定失败原因要用**完整**输出，而读流是另一个协程：先等它把管道读干净
                    // （最多 2 秒；进程已退出，管道里的残余读完就结束，不会真等满）。
                    runCatching { withTimeoutOrNull(2000) { pump?.join() } }
                    // 双保险：进程已经不是「当前这个」、或这一轮已经被 stop/新启动作废 → 直接退出，
                    // 绝不能在用户点「停止」之后又自动回退把引擎拉起来
                    if (proc !== p || logGeneration != gen) return@launch
                    proc = null
                    childPid = -1
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PID).apply()

                    // 需求 1/5：把引擎日志里的失败分类成人话，并据此决定「要不要自动换档」
                    val rawLog = rawLogOf(device, stageLogBase)
                    val failure = if (code == 0) null else classifyFailure(rawLog, device, code)
                    // 顺手把 OpenCL 的结论固化下来（下一轮 GPU 档直接跳过，不再白起一次）
                    if (failure?.kind == FailKind.OPENCL && _state.value.openclOk != false) {
                        _state.update { it.copy(openclOk = false, openclReason = failure.detail) }
                    }

                    // 降档自愈：NPU 档失败且当前强制代际还不是包内最低可用 ——
                    // 换低一代 skel 立即重试（v79→v75→v73），全部失败才走 GPU/CPU 回退。
                    // 只为「机型表猜错代际」兜底（真机教训：sm8650 曾误标 v79）；
                    // 已在下限的机型（如 K40 的 v73）lowerArch 为 null，条件不成立，
                    // 行为与从前完全一致。
                    if (failure != null && failure.fallbackAllowed && device == LocalDevice.NPU) {
                        val currentArch = if (archOverride > 0) archOverride else probe.htpArch
                        val lowerArch = probe.availableArches.lastOrNull { it < currentArch }
                        if (currentArch > 0 && lowerArch != null) {
                            Log.i(TAG, "HTP v$currentArch 失败，降档 v$lowerArch 重试")
                            // prevHtpArch 传**上一轮**的 currentArch：日志里「上一轮强制 vXX 失败」
                            // 要的是旧值；新值（lowerArch）由 archOverride 负责，两者别再混用 ——
                            // 旧实现把 archOverride 同时当 retryFrom 用，重试 v73 时日志反说「上一轮 v73」。
                            startInternal(model, device, tried, note, archOverride = lowerArch, prevHtpArch = currentArch)
                            return@launch
                        }
                    }

                    // 自动回退的目标：按 NPU → GPU → CPU 走，跳过已知不可用的档
                    val next = if (failure != null && failure.fallbackAllowed) resolveFallback(device, tried) else null
                    // 回退提示必须可见（需求：不许静默失败）：
                    // 关键修正——回退链是 NPU→GPU→CPU，若只写「当前这一档失败→下一档」，
                    // 用户选了 NPU 最后会看到「GPU 档失败，已改用 CPU」，完全看不到自己最初选的
                    // NPU 也失败了（真机反馈：「显示回退 cpu，但日志还是 gpu 回退，不是 npu 回退」）。
                    // 所以把**本轮已经试过的所有档**（tried，已按 NPU→GPU→CPU 顺序）都列出来：
                    //   第一跳（只试过 NPU）：「NPU · Hexagon 启动失败（…），已自动改用 GPU · Adreno」
                    //   最后一跳（试过 NPU+GPU）：「NPU · Hexagon、GPU · Adreno 均不可用，已自动改用 CPU」
                    // 用户一眼看清：我从 NPU 一路退到 CPU 的完整过程。
                    val fallbackNote = if (failure != null && next != null) {
                        val triedChain = tried.joinToString("、") { it.label }
                        if (tried.size == 1) {
                            "$triedChain 启动失败（${failure.tag}），已自动改用 ${next.label}"
                        } else {
                            "$triedChain 均不可用，已自动改用 ${next.label}"
                        }
                    } else {
                        null
                    }

                    // 这一档的错误槽位：优先用分类出来的「短句」，没有分类就用原来的 code 文案
                    val shortError = when {
                        // code=0 = 正常退出（用户切档/引擎自己收工），绝不能写成「引擎退出了」
                        code == 0 -> ""
                        failure != null && failure.userMessage.isNotBlank() -> failure.userMessage
                        failure != null && next != null -> "引擎退出了（code=$code）：${failure.tag}，已自动换档重试"
                        else -> "引擎退出了（code=$code）。内存不够时换小模型，或把设备切回 CPU 再试"
                    }
                    _state.update { st ->
                        st.copy(
                            running = false, starting = false,
                            // 共用报错：保留已经报过的那条（「启动有报错则继续显示」），
                            // 只有当前为空且本档有新报错（shortError 非空）才写进去；
                            // code=0 正常退出时 shortError=""，不会误写成「引擎退出了」。
                            error = st.error.ifBlank { shortError },
                            notice = fallbackNote ?: st.notice
                        )
                    }
                    // 把可操作建议写进共用日志（错误行只有 2 行，放不下长建议）
                    if (failure != null && failure.advice.isNotBlank()) {
                        appendLocalLog(device, gen, listOf("【建议】${failure.advice}"))
                    }

                    if (fallbackNote != null && next != null) {
                        Log.i(TAG, "自动回退：${device.label} → ${next.label}（${failure?.tag}）")
                        // note 一定要带过去：换档那一刻的状态更新会重写 notice，
                        // 传空串会把刚写好的「已自动改用 X」抹掉（用户就看不到回退了）
                        startInternal(model, next, tried, fallbackNote)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "启动失败", t)
                _state.update { it.copy(starting = false, running = false, error = "启动失败：${t.message}") }
            }
        }
    }

    /** 拿子进程 pid（走反射读 Process 的私有字段，失败就返回 -1，不影响功能） */
    private fun pidOf(p: Process): Int = runCatching {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    }.getOrDefault(-1)

    // ==================== 失败分类 / 设备参数归一化 / 诊断日志 ====================

    /** 引擎里的设备名，必须**逐字**等于后端注册名：匹配是 striequals 全等比较 */
    private const val HTP_DEVICE_NAME = "HTP0"
    private const val GPU_DEVICE_NAME = "GPUOpenCL"

    /** 失败大类：决定「给人看什么」和「要不要自动换到更保守的档位」 */
    private enum class FailKind {
        /** HTP / FastRPC 会话打不开（NPU 档专属） */
        NPU,

        /** OpenCL 平台/设备拿不到（GPU 档专属） */
        OPENCL,

        /** 模型文件本身有问题 —— 换档也一样失败，不能回退 */
        MODEL,

        /** 启动参数有问题 —— 换档也一样失败，不能回退 */
        ARGS,

        /** 内存不足（换 CPU 反而更省内存，允许回退） */
        OOM,

        /** 其它非 0 退出（先当设备相关处理，允许回退） */
        OTHER
    }

    private data class EngineFailure(
        val kind: FailKind,
        /** 落 error 槽位的短句（界面只有 2 行，长话进日志） */
        val userMessage: String,
        /** 分类标签，进 notice 与日志 */
        val tag: String,
        /** 证据原文（引擎日志里的那一行，OpenCL 原因要单独取它） */
        val detail: String,
        /** 长版可操作建议（进本档日志） */
        val advice: String,
        /** 是否允许自动换到更保守的档位 */
        val fallbackAllowed: Boolean
    )

    /**
     * 取共用日志里**从 [fromOffset] 行起**的日志（供失败分类扫关键词）。
     * [fromOffset] 是本档在环形缓冲里的起点（本档开跑时记的 logRing.size）——
     * 分类只看本档自己打出来的行，不把上一档（NPU→GPU→CPU 共用同一条流）泄漏过来的
     * 行误判成本档的原因：NPU 档失败后，GPU 档不能再看到 NPU 的「invalid device」行
     * 而被打成「参数错误、不回退」，否则回退链会停在 GPU、永远到不了 CPU。
     * device 参数仅为调用点兼容保留。
     */
    private fun rawLogOf(@Suppress("UNUSED_PARAMETER") device: LocalDevice, fromOffset: Int): String =
        synchronized(logRingLock) { logRing.drop(fromOffset).joinToString("\n") }

    /** 往**共用**日志里追加几行「我们自己的」话（诊断依据 / 建议），并同步到界面 */
    private fun appendLocalLog(device: LocalDevice, gen: Int, lines: List<String>) {
        if (lines.isEmpty()) return
        lines.forEach { Log.i(TAG, it) }
        if (logGeneration != gen) return
        val text = sharedLogAppend(lines)
        _state.update { st ->
            st.copy(logTail = text)
        }
    }

    /**
     * 启动前把「这次是按什么依据在跑」写进日志（需求 1：HTP 版本 / DSP 域 / 库路径要可诊断）。
     *
     * 为什么塞进日志框而不只是 Log.i：真机上用户能直接在设置页看到判定依据，
     * 排障不用先连电脑；adb logcat -s PhysEngine 也能看到同一批行。
     */
    private fun seedDiagnosisLog(
        device: LocalDevice,
        gen: Int,
        probe: BackendProbe,
        effectiveArch: Int,
        retryFrom: Int,
        args: List<String>,
        env: Map<String, String>
    ) {
        appendLocalLog(
            device, gen, buildList {
                add("【机型】${probe.socDesc}")
                add(
                    "【HTP】" + (if (effectiveArch > 0) "选定 v$effectiveArch（已下发 GGML_HEXAGON_ARCH）"
                    else "未探测到，交给引擎自己查询") +
                            (if (retryFrom > 0) "｜降档重试（上一轮强制 v$retryFrom 失败）" else "") +
                            "｜依据：${probe.htpReason}"
                )
                add("【DSP】域 cdsp（domain 3，物理核 0）｜设备名 $HTP_DEVICE_NAME")
                add("【库路径】ADSP_LIBRARY_PATH=${env["ADSP_LIBRARY_PATH"].orEmpty()}")
                add("【库路径】LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"].orEmpty()}")
                add(
                    "【OpenCL】" + (if (probe.icdPath.isNotBlank())
                        "厂商 ICD=${probe.icdPath}" + (if (probe.icdPath.endsWith(BUNDLED_ICD_NAME))
                            "（随包内置，已用 OCL_ICD_FILENAMES 下发；子进程可直接加载，" +
                                "驱动侧靠 android_load_sphal_library 走 sphal 命名空间取 libgsl/libCB）"
                        else "（取自设备 /vendor，已用 OCL_ICD_FILENAMES 下发；子进程被 namespace 挡住时会失败）")
                    else "包内与设备上都没找到厂商 OpenCL ICD（libOpenCL_adreno.so / /vendor/lib64/libOpenCL.so），" +
                        "GPU 档可能报 platform IDs not available")
                )
                add("【参数】${args.joinToString(" ")}")
            }
        )
    }

    /**
     * 设备选择参数归一化（需求 1 的引擎侧兜底，两件事）。
     *
     * 1) **补齐**：没写 `-dev/--device` 时，GPU 档补 `-dev GPUOpenCL`、NPU 档补 `-dev HTP0`、
     *    CPU 档补 `-dev none`。为什么不靠枚举里的 extraArgs：档位定义要保持人类可读，而这里必须在
     *    「用户自定义参数也拼进来之后」统一收敛。GPU 档尤其不能省 —— OpenCL 不可用时它一个设备都不注册
     *    （ggml-opencl.cpp:6310-6312），而不点名设备时 llama.cpp 会把**同为 GPU 类型**的
     *    Hexagon 设备当成默认设备（src/llama.cpp:222-279 + ggml-hexagon.cpp:7938），
     *    于是「选 GPU」会静默跑到 NPU 上、NPU 一失败整个引擎退出（真机日志就是这个形态）。
     *    CPU 档同样不能省（b11550 起的坑）：libggml.so 修好 Hexagon 注册后，HTP0 一定在册，
     *    而 b11550 的建上下文路径会探测**所有在册设备**（common_fit_params 量显存），
     *    App 域里 HTP 会话打不开（0x80000406）会让整个 `llama_init_from_model` 直接失败，
     *    表现就是「明明选了 CPU 也启动报错」—— 必须显式把设备集收窄；且 parse_device_list
     *    拒绝 CPU 类型设备，所以只能用特殊值 "none"（common/arg.cpp:1129-1131）。
     * 2) **归一化**：`--device HTP` / `--device GPU` 这类前缀写法换成后端真正注册的设备名
     *    （HTP→HTP0、GPU/OpenCL→GPUOpenCL）。依据：设备名匹配走
     *    ggml-backend-reg.cpp:345-353 的 `striequals`（**全等**），
     *    common/arg.cpp:1129-1133 拿到 nullptr 就 `invalid device: %s` 抛异常退出；
     *    而 "HTP0"/"GPUOpenCL" 分别是 ggml-hexagon.cpp:8928 与 ggml-opencl.cpp:12904 的注册名。
     *    注意 `HTP0:0`（多会话写法）不归一化，原样放行。
     */
    private fun normalizeDeviceArgs(args: MutableList<String>, device: LocalDevice) {
        // 主设备列表与草稿（speculative decoding）设备列表取值语法相同
        // （common/arg.cpp:2738 `-dev/--device`，:4228 `--spec-draft-device/-devd/--device-draft`，
        //  都是 `<dev1,dev2,..>` 走 parse_device_list），所以同一套名字归一化对两者都适用。
        val mainKeys = setOf("-dev", "--device")
        val draftKeys = setOf("-devd", "--device-draft", "--spec-draft-device")
        var seenMain = false
        var i = 0
        while (i < args.size) {
            val isMain = args[i] in mainKeys
            val isDraft = !isMain && args[i] in draftKeys
            if ((isMain || isDraft) && i + 1 < args.size) {
                if (isMain) seenMain = true
                val raw = args[i + 1]
                val fixed = raw.split(',').joinToString(",") { canonicalDeviceName(it) }
                if (fixed != raw) {
                    Log.i(TAG, "设备名归一化：'$raw' → '$fixed'（引擎按全等比较设备名，前缀写法会被判 invalid device）")
                    args[i + 1] = fixed
                }
                i++
            }
            i++
        }
        if (seenMain) return
        val name = when (device) {
            LocalDevice.GPU -> GPU_DEVICE_NAME
            LocalDevice.NPU -> HTP_DEVICE_NAME
            // b11550 的 parse_device_list（common/arg.cpp:1129-1140）**明确拒绝 CPU 类型设备**，
            // 写 "-dev CPU" 会直接 invalid device 退出；官方的「只用 CPU」写法是特殊值 "none"
            // （devices = [nullptr]），canonicalDeviceName 对它原样放行。
            LocalDevice.CPU -> "none"
        }
        if (name != null) {
            Log.i(TAG, "未指定推理设备，按档位补上 -dev $name（避免默认设备选到别的后端）")
            args += listOf("-dev", name)
        }
    }

    /** 前缀 → 后端注册的设备名（只认我们确有的三个名字，别的一律原样放行） */
    private fun canonicalDeviceName(token: String): String {
        val t = token.trim()
        if (t.isEmpty() || t.equals("none", ignoreCase = true)) return t
        if (t.equals(HTP_DEVICE_NAME, ignoreCase = true) || t.equals(GPU_DEVICE_NAME, ignoreCase = true)) return t
        return when {
            t.equals("gpu", ignoreCase = true) || t.equals("opencl", ignoreCase = true) -> GPU_DEVICE_NAME
            // 长度 ≥3 才做前缀匹配，避免 "H"/"G" 这种字母误伤
            t.length >= 3 && HTP_DEVICE_NAME.startsWith(t, ignoreCase = true) -> HTP_DEVICE_NAME
            t.length >= 3 && GPU_DEVICE_NAME.startsWith(t, ignoreCase = true) -> GPU_DEVICE_NAME
            else -> t
        }
    }

    /**
     * 自动回退的下一站：NPU → GPU → CPU，跳过本轮已经试过的档。
     *
     * 不再因为「OpenCL 曾经失败」跳过 GPU：厂商 ICD 已随包下发（libOpenCL_adreno.so），
     * GPU 档值得再试一次；真的起不来它自己会被归类成 OPENCL 失败，然后继续回退到 CPU。
     */
    private fun resolveFallback(device: LocalDevice, tried: Set<LocalDevice>): LocalDevice? {
        var next = device.fallback()
        while (next != null && tried.contains(next)) next = next.fallback()
        return next
    }

    /**
     * 把引擎日志归类成「人话 + 该不该换档」。
     *
     * 判定顺序很重要：`invalid device: GPUOpenCL` 同时含 "invalid device:"（参数类），
     * 所以要**先**判 OPENCL，再判 ARGS；NPU 的会话错误同理要先于通用错误。
     */
    private fun classifyFailure(rawLog: String, device: LocalDevice, exitCode: Int = 0): EngineFailure? {
        if (rawLog.isBlank()) return null
        val lines = rawLog.split('\n')
        fun hit(vararg keys: String): String? =
            lines.firstOrNull { l -> keys.any { k -> l.contains(k, ignoreCase = true) } }

        // ---- NPU：连设备名都注册不出来（HTP 后端根本没起来）----
        // 实测（SM8350 / Redmi K40）：本机 DSP 是 v68，而包内 skel 只有 v73/v75/v79/v81，
        // 于是 libggml-hexagon.so 注册不出任何 HTP 设备，llama.cpp 解析 --device 时直接
        // `error while handling argument "--device": invalid device: HTP0` 退出。
        // 这**不是**用户参数写错，而是本机没有可用的 HTP 设备 —— 换 CPU/GPU 就能跑，
        // 所以必须归到「可回退」的 NPU 类，而不是下面那个 fallbackAllowed=false 的参数类。
        val npuDeviceLine = if (device == LocalDevice.NPU) {
            hit("invalid device: HTP", "invalid device: $HTP_DEVICE_NAME")
        } else {
            null
        }
        if (npuDeviceLine != null) {
            return EngineFailure(
                kind = FailKind.NPU,
                userMessage = "NPU 档不可用（本机没有注册出可用的 HTP 设备）：已自动改用更稳的后端",
                tag = "HTP 设备不可用",
                detail = npuDeviceLine.trim(),
                advice = "原因：引擎初始化 HTP 后端时**一个设备都没注册出来**，于是 --device $HTP_DEVICE_NAME 被判非法。" +
                    "本包内置的 skel 是 v73/v75/v79/v81，而不少骁龙 870/888 机型的 DSP 代际更早。" +
                    "核对方法：adb shell ls /vendor/lib64/rfsa/adsp（部分机型在 /vendor/lib/rfsa/adsp），" +
                    "看里面的 QNN 库叫什么 —— 常见是带代际的 libQnnHtpV79Skel.so，" +
                    "也有 ROM 只放一个不带版本号的 libQnnHtpSkel.so / libQnnHtpAltPrepSkel.so" +
                    "（App 启动前已经扫过这两个目录，本档日志开头就有探测结果）。" +
                    "另外，`0x80000406` 一类的「会话打不开」以最新版实测为准：本版已按真机定案修正" +
                    "App 侧的库路径写法（ADSP_LIBRARY_PATH 必须是单个目录、skel 实物落盘，见 docs §5.15）。" +
                    "现在就能用：CPU 档（实测骁龙 888 上 4B-Q4 生成约 5～6 tok/s，聊天够用但偏慢）；" +
                    "想用 NPU 需要从本机提取对应代际的 QNN skel 重新打包。",
                fallbackAllowed = true
            )
        }

        // ---- NPU：HTP / FastRPC 会话打不开 ----
        // 只在 NPU 档认这一类关键词：hexagon 后端在**任何**档启动时都会被枚举一遍，
        // 它的探测失败行（"failed to load libcdsprpc" 等）在 CPU/GPU 档只是噪声，
        // 不可能是那两档退出的原因 —— 不加这个门，GPU 档的失败会被误诊成「NPU 打不开会话」。
        val npuLine = if (device == LocalDevice.NPU) {
            hit("failed to open session")
                ?: hit(
                    "failed to query HTP version", "FastRPC capability query failed",
                    "failed to enable unsigned PD", "invalid physical CDSP core",
                    "failed to reserve new session", "failed to load libcdsprpc",
                    "ggml-hex: no devices", "hexagon: failed", "failed to create dspqueue"
                )
        } else {
            null
        }
        if (npuLine != null) {
            val code = Regex("""error 0x([0-9a-fA-F]+)""").find(npuLine)?.groupValues?.get(1)
            val codeText = code?.let { "0x$it" } ?: "无错误码"
            return EngineFailure(
                kind = FailKind.NPU,
                userMessage = "NPU 打不开 HTP 会话（HTP 错误 $codeText）：本档日志有完整原因，已自动改用更稳的后端",
                tag = "HTP 会话失败 $codeText",
                detail = npuLine.trim(),
                advice = "HTP $codeText 的常见原因与可操作项：" +
                    "① 先切 CPU/GPU 把对话跑起来（点上面的设备档位再启动）；" +
                    "② 本次已按机型探测值下发 GGML_HEXAGON_ARCH；若仍失败，先确认是最新版（本版已把" +
                    "ADSP_LIBRARY_PATH 改成单目录、并把 skel 实物拷进 files/htplibs 与 files/htplibs/cdsp，" +
                    "2026-10-12 定案见 docs §5.15），再核对 skel 版本与本机 DSP 代际是否匹配；" +
                    "③ 第三方 ROM 可能需要从设备提取 /vendor/lib64/rfsa/adsp 下的 QNN skel 再重试；" +
                    "④ 完整日志：adb logcat -s PhysEngine 或本档日志框。",
                fallbackAllowed = true
            )
        }

        // ---- OpenCL：平台/设备拿不到 ----
        // 同样只在 GPU 档认：`platform IDs not available` 是 OpenCL 后端在**每次**启动时
        // 探测平台的产物（ggml-opencl.cpp:6310-6312），CPU/NPU 档也有这一行，不能当失败原因。
        val openclLine = if (device == LocalDevice.GPU) {
            hit(
                "invalid device: GPUOpenCL", "platform IDs not available", "could find any OpenCL devices",
                "ggml_opencl: no devices", "failed to get extension function address clIcdGetPlatformIDsKHR"
            )
        } else {
            null
        }
        if (openclLine != null) {
            val reason = openclLine.substringAfter("ggml_opencl: ", openclLine).trim()
            return EngineFailure(
                kind = FailKind.OPENCL,
                userMessage = "OpenCL 不可用（$reason）：Adreno GPU 后端起不来，已自动改用 CPU",
                tag = "OpenCL 不可用",
                detail = reason,
                advice = "包内 libOpenCL.so 直接就是**厂商 OpenCL 实现本体**（115KB，取自设备 " +
                    "/vendor/lib64/libOpenCL.so，SONAME 一致，导出 147 个 cl* 公开 API 与 clIcdGetPlatformIDsKHR）。" +
                    "两个坑都踩过、也都被绕开了：① 包内原先是高通魔改的 ICD loader，它不认 OCL_ICD_FILENAMES、" +
                    "内部写死找 /system/vendor/lib64/egl，于是平台数恒为 0；② 本机 Adreno 驱动是 OpenCL 2.0，" +
                    "而 OpenCL 后端原先按 3.0 目标编译，引用了 3.0 才有的 clCreateBufferWithProperties，" +
                    "装载期直接 cannot locate symbol。现在 libggml-opencl.so 以 GGML_OPENCL_TARGET_VERSION=200 " +
                    "编译，直链厂商实现，不需要 loader 层。" +
                    "驱动真正干活的库（libgsl.so / libCB.so / libllvm-glnext.so）仍留在 /vendor/lib64，" +
                    "由实现内部调 android_load_sphal_library 走 sphal 命名空间加载 —— libCB 还依赖 " +
                    "libgralloc.qti.so 等厂商 HAL，只有 sphal 这条通道能拿到。" +
                    "排查步骤：① adb logcat | grep -Ei 'ggml_opencl|opencl|vndksupport|linker'；" +
                    "② 出现 cannot locate symbol 说明后端目标版本又高于驱动，重编为 200/210；" +
                    "③ 出现 vndksupport 失败说明卡在厂商驱动加载；④ 仍失败就用 CPU 档（K40 上 4B-Q4 实测生成约 5～6 tok/s）。",
                fallbackAllowed = true
            )
        }

        // ---- 模型文件本身 ----
        hit(
            "failed to load model from file", "error loading model", "model loading error",
            "unknown model architecture", "unknown magic", "not a valid gguf", "invalid model",
            "unsupported model", "no such file or directory", "failed to open file", "failed to load model"
        )?.let { line ->
            val why = line.substringAfter("error: ", line).trim().take(90)
            return EngineFailure(
                kind = FailKind.MODEL,
                userMessage = "模型加载失败：$why",
                tag = "模型问题",
                detail = line.trim(),
                advice = "确认是完整下载的 .gguf（文件头必须是 GGUF，启动前已校验过一次）；" +
                    "分片模型要把同组所有切片放进模型目录；架构/量化过新时换 Q4_0 / Q4_K_M 再试；" +
                    "换后端没用（同一个文件在 CPU/GPU/NPU 上一样读不了），所以本轮不做自动回退。",
                fallbackAllowed = false
            )
        }

        // ---- 启动参数 ----
        hit(
            "invalid argument", "unrecognized argument", "unknown argument", "cannot be used multiple times",
            "invalid device:", "failed to parse", "error: invalid"
        )?.let { line ->
            val why = line.substringAfter("error: ", line).trim().take(90)
            return EngineFailure(
                kind = FailKind.ARGS,
                userMessage = "启动参数错误：$why",
                tag = "参数错误",
                detail = line.trim(),
                advice = "启动参数由 App 按档位自动装配（设置页已不提供手写入口）：CPU 档不点名设备、" +
                    "GPU 档用 $GPU_DEVICE_NAME、NPU 档用 $HTP_DEVICE_NAME（写 HTP 也会被归一化成 $HTP_DEVICE_NAME）。" +
                    "出现参数错误通常是引擎版本与档位参数不匹配，请在设置页换档重试并反馈日志；" +
                    "参数错误换后端也没用，本轮不做自动回退。",
                fallbackAllowed = false
            )
        }

        // ---- 内存 ----
        hit("Killed", "out of memory", "unable to allocate", "cannot allocate memory", "malloc failed")
            ?.let { line ->
                return EngineFailure(
                    kind = FailKind.OOM,
                    userMessage = "引擎被系统杀掉了（内存不足）：换更小的模型，或把设备切回 CPU",
                    tag = "内存不足",
                    detail = line.trim(),
                    advice = "真机可用内存不足时的顺序：① 换更小的模型（2B 级）；② 用 CPU 档（显存/共享内存占用最低）；" +
                        "③ 在设置里调小上下文长度。",
                    fallbackAllowed = true
                )
            }

        // ---- GPU：OpenCL 平台有了，但驱动编译器编不过内核 ----
        // 实测（SM8350 / Adreno 660，2024 版 MIUI/A14）：平台与设备能正常枚举，
        // 但 clBuildProgram 要么返回 CL_OUT_OF_HOST_MEMORY(-6)（build log 只有 "Pass"），
        // 要么直接把引擎进程打成 SIGSEGV —— 崩溃点在 /vendor/lib/libCB.so 的
        // QGPUPeepholeOptimizer::buildClusterOp（空指针解引用）。
        // 用独立探针 + fork 逐内核隔离编译，一次拿到全量清单：182 个内核里
        // 5 个必崩（rms_norm / mean / sum_rows / ssm_scan / mul_mv_f16_f32_l4）、
        // 31 个稳定失败（cumsum / sigmoid / group_norm / repeat / flash_attn_* /
        // gemm_noshuffle_* / gemv_noshuffle_* 等），其余 146 个正常。
        // 已逐项排除：编译选项任意组合（含 -cl-opt-disable）、CL1.2/CL2.0、内存压力、
        // SELinux 域、OCL_ICD_FILENAMES/TMPDIR/LD_LIBRARY_PATH、内核二进制缓存、编译顺序。
        // 注意驱动的编译器是**有状态**的：单个内核在独立进程里能编过，到了引擎那种
        // "一口气连编上百个" 的进程里仍会退化成 -6，所以探针能过不等于引擎能过。
        // 结论：Adreno 660 该版驱动的编译器缺陷，App 侧无法修复。
        val oclBuildLine = hit("kernel compile error")
        if (oclBuildLine != null) {
            return EngineFailure(
                kind = FailKind.OTHER,
                userMessage = "GPU 档不可用（OpenCL 驱动内核编译失败）：已自动改用更稳的后端",
                tag = "OpenCL 内核编译失败",
                detail = oclBuildLine.trim(),
                advice = "现象：OpenCL 平台与设备都能枚举出来（clGetPlatformIDs / clCreateContext 正常），" +
                    "但驱动编译器编不过 llama.cpp 的内核 —— 有的返回 CL_OUT_OF_HOST_MEMORY(-6)（build log 只有 Pass），" +
                    "有的直接把引擎进程打成 SIGSEGV。" +
                    "定位方式：独立探针用与引擎完全一致的环境启动，并让**每个内核在独立子进程里单独编译**" +
                    "（段错误无法在进程内捕获），一次拿到全量清单。" +
                    "逐项排除过、都不是原因：编译选项任意组合（含 -cl-opt-disable）、CL1.2/CL2.0、内存压力、" +
                    "SELinux 域、OCL_ICD_FILENAMES/TMPDIR/LD_LIBRARY_PATH、内核缓存与编译顺序。" +
                    "设备能力上 cl_khr_subgroups 支持，但 cl_khr_integer_dot_product 不支持。" +
                    "也试过三种规避：跳过必崩内核 + 容忍加载期失败（引擎能加载完模型并开始 GPU 图计算，" +
                    "随后仍在图计算阶段崩溃）、给主编译路径接上上游自带的重试、把失败算子用 " +
                    "GGML_OPENCL_OPFILTER 逐个退回 CPU —— 都只是把崩溃位置往后推。" +
                    "所以这是该版 Adreno 驱动的编译器缺陷，App 侧改不了。" +
                    "现在就能用：CPU 档（实测骁龙 888 上 4B-Q4 生成约 5～6 tok/s）；" +
                    "想用 GPU 建议换 Adreno 7xx/8xx 机型（上游 OpenCL 后端面向新一代 Adreno）。" +
                    "完整证据链见 docs/android-armv8.2-engine-rebuild.md 第 5.7 节。",
                fallbackAllowed = true
            )
        }

        // ---- GPU：驱动编译器把进程打死，日志里没有可匹配的行 ----
        // 崩在 clBuildProgram 里时进程是信号终止（SIGSEGV=11 → exitCode 139），
        // 来不及打任何日志，上面那条 "kernel compile error" 匹配不到，会落到泛化分支，
        // 用户只能看到"日志里没有明确原因"。这里按已验证的根因直接给出结论。
        if (device == LocalDevice.GPU && exitCode >= 128) {
            return EngineFailure(
                kind = FailKind.OTHER,
                userMessage = "GPU 档不可用（Adreno 驱动编译器崩溃）：已自动改用 CPU 档",
                tag = "OpenCL 驱动编译器崩溃",
                detail = "引擎被信号 ${exitCode - 128} 终止，崩溃发生在 clBuildProgram 内部，没有日志",
                advice = "这是已知的驱动缺陷：Adreno 的 OpenCL 编译器在编译部分 llama.cpp 内核时" +
                    "会直接段错误（崩溃点在 /vendor/lib/libCB.so 的 QGPUPeepholeOptimizer::buildClusterOp），" +
                    "进程被信号打死，所以日志里看不到任何原因。" +
                    "实测（SM8350 / Adreno 660）：182 个内核里 5 个必崩" +
                    "（rms_norm / mean / sum_rows / ssm_scan / mul_mv_f16_f32_l4）、31 个稳定编译失败。" +
                    "试过跳过崩内核、容忍加载期失败、给编译路径加重试、把失败算子退回 CPU，都无法跑通。" +
                    "现在就能用：CPU 档（4B-Q4 生成约 5～6 tok/s）；换 Adreno 7xx/8xx 机型才有机会用上 GPU。" +
                    "完整证据链见 docs/android-armv8.2-engine-rebuild.md 第 5.7 节。",
                fallbackAllowed = true
            )
        }

        // ---- 其它：非 0 退出但认不出原因，按「设备相关」处理（dial 到 CPU 为止） ----
        if (device != LocalDevice.CPU) {
            return EngineFailure(
                kind = FailKind.OTHER,
                userMessage = "",
                tag = "后端异常退出",
                detail = lines.lastOrNull { it.isNotBlank() }?.trim().orEmpty(),
                advice = "引擎在 $device 档异常退出但日志里没有明确原因，已自动换到更保守的档位重试；" +
                    "若 CPU 档也失败，请把本档日志整段发出来定位。",
                fallbackAllowed = true
            )
        }
        return null
    }

    /**
     * 这一行是不是「HTP/QNN 后端探测噪声」。
     *
     * 关键词与原来 pumpLogs 里的 htpBad 判定逐字一致，只是抽成函数，
     * 让「状态判定」和「界面过滤」用同一套规则（不另起一套判定）。
     */
    private fun isHtpProbeNoise(line: String): Boolean =
        line.contains("failed to load libcdsprpc") ||
            line.contains("ggml-hex: no devices") ||
            line.contains("hexagon: failed")

    /**
     * 这一行是不是「OpenCL 后端探测噪声」。
     *
     * 打包进来的 libOpenCL.so 是高通版 ICD loader，它拿不到厂商驱动时只会打一行
     * `ggml_opencl: platform IDs not available.`（ggml-opencl.cpp:6310-6312）——
     * 这一行在 CPU / NPU 档是纯粹的噪声（用户会以为"引擎又报错了"），
     * 在 **GPU 档**却是我们唯一的诊断证据，所以按档位区别对待：
     * GPU 档照常显示，别的档隐藏（原始日志仍完整留在 logcat）。
     */
    private fun isOpenclProbeNoise(line: String): Boolean =
        line.contains("platform IDs not available") ||
            line.contains("could find any OpenCL devices") ||
            line.contains("ggml_opencl: no devices") ||
            line.contains("failed to get extension function address clIcdGetPlatformIDsKHR")

    /** 过滤后的显示结果：text = 真正显示的行；hidden = 被隐藏的 NPU 探测噪声行数 */
    private data class DisplayLog(val text: String, val hidden: Int)

    /**
     * 按「当前设备」决定这条日志该怎么显示。
     *
     * 为什么要过滤：llama.cpp 启动时会枚举/探测所有后端，于是 HTP（QNN/Hexagon）的
     * 初始化失败信息在 CPU / GPU 档也会照样打出来 —— 用户切到 GPU 后看到
     * "failed to load libcdsprpc" 会以为 GPU 也挂了。所以非 NPU 档把这类行从
     * **显示**里滤掉，并给出条数提示；原始日志在内存槽位和 logcat 里都完整保留。
     *
     * OpenCL 的 `platform IDs not available` 同理（需求 5：不再刷错误日志）：
     * CPU / NPU 档隐藏它，GPU 档保留（那是它自己的诊断依据，并且会被
     * [classifyFailure] 翻成一句人话「OpenCL 不可用（原因）」，再明确回退到 CPU）。
     */
    private fun displayLogFor(raw: String, device: LocalDevice): DisplayLog {
        if (raw.isBlank()) return DisplayLog(raw, 0)
        val lines = raw.split('\n')
        val kept = lines.filterNot { line ->
            // NPU 档不过滤 HTP 行（那是它自己的正常输出）
            (device != LocalDevice.NPU && isHtpProbeNoise(line)) ||
                // GPU 档保留 OpenCL 行；CPU/NPU 档隐藏
                (device != LocalDevice.GPU && isOpenclProbeNoise(line))
        }
        return DisplayLog(kept.joinToString("\n"), lines.size - kept.size)
    }

    /**
     * 把引擎 stdout 收进环形缓冲。
     *
     * 三个细节：
     * 1) 每一行都 Log.i 出去，真机排障直接 adb logcat -s PhysEngine 就行；
     * 2) UI 状态更新做了节流（≥300ms），否则加载模型时几百行日志会把
     *    卡片高度顶得一跳一跳的，看着像闪屏；
     * 3) 环形缓冲是 CPU/GPU/NPU **三档共用**一条（用户要求），自动回退时
     *    各档的启动/报错行都累加进同一条流，界面直接看全回退全过程；
     * 4) 每轮启动带 [gen] 代号，只有「代号还是当前代号」时才写状态 ——
     *    防止上一轮的收尾补写污染新一轮。
     */
    private suspend fun pumpLogs(p: Process, device: LocalDevice, gen: Int) = withContext(Dispatchers.IO) {
        // 快照必须和写入同锁（共用环形缓冲 [logRing] + [logRingLock]）
        fun snapshot(): String = sharedLogSnapshot()

        // 已经又按了一次启动/停止 → 本次日志不再参与 UI 显示（logcat 里仍然有）
        fun stale(): Boolean = logGeneration != gen

        fun pushToState() {
            if (stale()) return
            val text = snapshot()
            _state.update { st ->
                // 共用日志：直接写 logTail（不再分设备槽位）
                st.copy(logTail = text)
            }
        }

        var lastPush = 0L
        var dirty = false
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    synchronized(logRingLock) {
                        logRing.addLast(line)
                        // 保留最近 LOG_RING_LINES 行（第 5 项：日志框可滚动，需要足够历史）
                        if (logRing.size > LOG_RING_LINES) logRing.removeFirst()
                    }
                    val ready = line.contains("listening on http")
                    val oom = line.contains("Killed") || line.contains("out of memory", true)
                    // GPU/NPU 后端能不能用，引擎启动日志会自己说清楚，实时反映到界面上
                    // （需求 5：OpenCL 的两种失败形态都要认出来，不能只认一种）
                    val openclBad = line.contains("platform IDs not available") ||
                        line.contains("ggml_opencl: no devices") ||
                        line.contains("could find any OpenCL devices") ||
                        line.contains("invalid device: GPUOpenCL")
                    val openclGood = line.contains("ggml_opencl:") && line.contains("Adreno")
                    // 复用同一份 HTP 判定：这里用于 htpOk 状态，UI 里用于过滤显示
                    val htpBad = isHtpProbeNoise(line)
                    val htpGood = Regex("""ggml-hex:.*(HTP0|hexagon.*(device|session))""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(line)
                    dirty = true
                    val now = System.currentTimeMillis()
                    if (ready || oom || openclBad || htpBad || now - lastPush >= 300L) {
                        lastPush = now
                        dirty = false
                        // 这一轮已经不是「当前这一轮」了，直接跳过状态写入
                        // （Kotlin 的 lambda 里没有 continue，return@forEach 等价于 continue）
                        if (stale()) return@forEach
                        val text = snapshot()
                        _state.update { st ->
                            // OOM 文案写进**共用**报错，且不覆盖已有更具体的错误（ifBlank 保留）。
                            // 引擎成功就绪（ready）= 这一轮跑起来了，共用报错清空（不再挂着旧错）。
                            val sharedErr =
                                if (oom && st.error.isBlank()) st.error.let { "引擎被系统杀掉了（内存不足）" }
                                else if (ready) ""
                                else st.error
                            st.copy(
                                logTail = text,
                                running = st.running || ready,
                                starting = if (ready) false else st.starting,
                                error = sharedErr,
                                openclOk = when {
                                    openclBad -> false
                                    openclGood -> true
                                    else -> st.openclOk
                                },
                                // 需求 5：原因原文也实时记下来，界面/日志里是「OpenCL 不可用（原因）」，
                                // 而不是光一个布尔值 + 一行吓人的 E 日志
                                openclReason = if (openclBad) {
                                    line.substringAfter("ggml_opencl: ", line).trim()
                                } else {
                                    st.openclReason
                                },
                                htpOk = when {
                                    htpBad -> false
                                    htpGood -> true
                                    else -> st.htpOk
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 进程被杀时读流会抛，忽略即可
        }
        if (dirty) pushToState()
    }

    override fun stop() = stopInternal(clearLogs = true)

    /**
     * 停掉引擎进程。
     *
     * @param clearLogs true = 用户手动停止：共用日志环形缓冲 + 共用报错一起清空（引擎停止则清空）；
     *                  false = 引擎自动换档重试（自动回退）：只杀进程，
     *                  **保留共用日志与报错** —— 失败原因必须留给用户看（「启动有报错则继续显示」），
     *                  否则「NPU 为什么挂了」会随着回退被一起抹掉（需求：回退不许静默）。
     */
    private fun stopInternal(clearLogs: Boolean) {
        // 代号先 +1（在杀进程之前）：收尾协程据此判定「这一轮已作废」，
        // 用户点停止后它不会再走自动回退（否则按下停止反而会把引擎在别的档拉起来）
        logGeneration++
        logJob?.cancel()
        logJob = null
        proc?.let { p ->
            runCatching { p.destroy() }
            runCatching { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
        }
        proc = null
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY_PID)?.apply()
        if (!clearLogs) {
            _state.update { it.copy(running = false, starting = false) }
            return
        }
        // 用户手动停止 = 这一轮彻底结束：共用日志环形缓冲清空，下次启动从头生成，
        // 日志不跨轮次越积越多；共用报错一起清（用户是主动停的，旧报错不再有意义）。
        // 代号也已 +1：上一轮收尾还没退干净的日志协程直接变 stale，残留补写不会把刚清空的又填回来。
        synchronized(logRingLock) { logRing.clear() }
        _state.update {
            it.copy(
                running = false,
                starting = false,
                activeModel = "",
                logTail = "",
                error = "",
                // 用户主动停止 = 这一轮结束，自动回退提示也该退场
                notice = ""
            )
        }
    }

    // ==================== 设置页卡片 ====================

    private val CardBg = Color(0xFF1E1E2E)
    private val TitleColor = Color.White
    private val SubColor = Color(0xFFB8B8CC)
    private val DimColor = Color(0xFF7A7A92)
    private val Accent = Color(0xFF9C27B0)
    private val OkColor = Color(0xFF4CAF50)
    private val WarnColor = Color(0xFFFFB74D)
    private val ErrColor = Color(0xFFEF5350)

    @Composable
    override fun SettingsSection(
        onRetryConnect: () -> Unit,
        isConnected: Boolean,
        onShowHint: (String) -> Unit
    ) {
        val ctx = LocalContext.current
        val st by state.collectAsStateWithLifecycle()
        var selected by remember { mutableStateOf(st.selectedModel.takeIf { it.isNotBlank() }) }
        // P2：设备选择从持久化状态初始化（之前写死 CPU，切页回来就弹回去了）
        var device by remember { mutableStateOf(st.device) }

        // 第 3 点：从系统「所有文件访问权限」授权页（或任意退到后台）返回时，自动重查权限。
        // 权限态真变了才 rescan（更新 hasPermission + 模型列表），用户不用再手动点「重新扫描」。
        // 只在**权限态变化**时触发，避免每次回到前台都白扫一遍、误清共用日志。
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            val nowPerm = hasAllFilesAccess(ctx)
            if (nowPerm != state.value.hasPermission) rescan()
        }

        // 第 4 点：引擎自动回退（NPU→GPU→CPU）时，st.device 会一路被改到最终落地的档。
        // 本地 device（胶囊高亮 + 「当前设备」标签）跟随 st.device 同步，
        // 让界面如实显示「现在到底跑在哪一档」。全程都在固定高度区域里改，不闪屏。
        LaunchedEffect(st.device) {
            if (device != st.device) device = st.device
        }

        // 上次挑中的模型还在列表里就继续用它；只有它消失了（换目录/删了文件）才退回第一个。
        LaunchedEffect(st.models, st.selectedModel) {
            if (st.models.isEmpty()) return@LaunchedEffect
            if (selected == null || st.models.none { it.path == selected }) {
                val remembered = st.selectedModel.takeIf { p -> st.models.any { it.path == p } }
                selected = remembered ?: st.models.first().path
            }
        }

        // 引擎起来后自动重连一次，用户不用再去点「重试连接」。
        // 只在「引擎已就绪 且 App 还没连上」时触发：等 900ms 让 HTTP 端口彻底 accept，
        // 连上以后这个 key 不变，不会反复重连、也就不会反复闪加载页。
        LaunchedEffect(st.running, isConnected) {
            if (st.running && !isConnected) {
                kotlinx.coroutines.delay(900)
                onRetryConnect()
            }
        }

        // 与设置页其它卡片（SettingsScreen.SettingCard）严格对齐：
        // 外层 Column 已经给了 padding(16.dp) + spacedBy(12.dp)，
        // 这里再叠一层 horizontal=16/vertical=6 会让真机卡整体缩进、间距也变宽 ——
        // 所以只 fillMaxWidth()，圆角同样用 12dp（原来 14dp 也和别的卡不一样）。
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(Modifier.padding(16.dp)) {

                // ---- 标题行 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("真机本地推理", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TitleColor)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                when {
                                    st.running -> OkColor
                                    st.starting -> WarnColor
                                    else -> DimColor
                                },
                                RoundedCornerShape(4.dp)
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            st.starting -> "启动中…"
                            st.running -> "引擎运行中"
                            st.enginePresent -> "引擎就绪"
                            else -> "引擎缺失"
                        },
                        fontSize = 11.sp,
                        color = if (st.running) OkColor else SubColor
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    if (st.running && st.activeModel.isNotBlank())
                        "本地模型：${st.activeModel}（${st.device.label}）"
                    else
                        "模型放手机里，全离线推理",
                    fontSize = 11.sp, color = SubColor
                )

                // ---- 权限（第 1 点：已授权后不再留 112dp 空槽 —— 之前那块定高槽把
                //      「模型放手机里」和「模型目录」隔出一大段空白，真机嫌间距过大；
                //      现在只在未授权时才渲染整块「需要权限 + 去授权」，已授权零占位，
                //      间距恢复到加权限提示之前的样子）----
                if (!st.hasPermission) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A2216), RoundedCornerShape(10.dp))
                            .border(1.dp, WarnColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            "需要「所有文件访问权限」才能读写 /storage/emulated/0/AI角色扮演 里的模型",
                            fontSize = 11.sp, color = WarnColor
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { openStoragePermission(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("去授权", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // ---- 模型目录 ----
                Spacer(Modifier.height(10.dp))
                Text("模型目录", fontSize = 11.sp, color = DimColor)
                Text(
                    st.modelDir.ifBlank { "…" },
                    fontSize = 11.sp,
                    color = SubColor,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "把 .gguf 丢进这个目录，点下面的「重新扫描」即可；高通处理器优先顺序：NPU→GPU→CPU，其余设备仅为CPU。",
                    fontSize = 10.sp,
                    color = DimColor,
                    // 换行行距收紧：默认多行文本行高很松（实测换行墨水间距 ≈25px），
                    // 与上面「路径行 → 提示词首行」两个独立文本块自然相接的紧凑间距（≈5px）不一致。
                    // 13sp 行高（10sp 字号）把换行墨水间距压到 ≈5px，与「路径→提示词」间距一致。
                    lineHeight = 13.sp
                )

                // ---- 模型列表 ----
                // 真机反馈：模型容器改**动态高度** —— ≤3 个模型时按内容自适应（以前三态
                // 一律固定 180dp，2 个模型时下面空一大块）；>3 个固定只显示 3 行、内部
                // 可下拉滚动看全部，「扫到 N 个模型」标题行顺带提示。
                // 行高固定 44dp（RadioButton 最小触控目标的实测量级），3 行 = 132dp，
                // 滚动窗口高度稳定。注：这条按用户最新要求取代了旧版「固定 180dp 防跳屏」。
                Spacer(Modifier.height(10.dp))
                when {
                    st.scanning -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("扫描中…", fontSize = 11.sp, color = SubColor)
                    }

                    st.models.isEmpty() -> Text(
                        "还没扫到模型。目录建好了，把 gguf 放进去再刷新～",
                        fontSize = 11.sp, color = WarnColor
                    )

                    else -> {
                        val MODELS_SHOWN = 3
                        val MODEL_ROW_H = 44.dp
                        val many = st.models.size > MODELS_SHOWN
                        Text(
                            if (many) "扫到 ${st.models.size} 个模型（可下拉查看全部）"
                            else "扫到 ${st.models.size} 个模型",
                            fontSize = 11.sp, color = DimColor
                        )
                        Spacer(Modifier.height(4.dp))
                        val listScroll = rememberScrollState()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(if (many) Modifier.height(MODEL_ROW_H * MODELS_SHOWN) else Modifier)
                                .then(if (many) Modifier.verticalScroll(listScroll) else Modifier)
                        ) {
                            st.models.forEach { m ->
                                val isActive = st.running && st.activeModel == m.name
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(MODEL_ROW_H)
                                        .padding(vertical = 2.dp)
                                        .clickable(enabled = !st.running) { selected = m.path; selectModel(m.path) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selected == m.path,
                                        onClick = { selected = m.path; selectModel(m.path) },
                                        enabled = !st.running,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Accent, unselectedColor = DimColor
                                        )
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            m.name,
                                            fontSize = 12.sp,
                                            color = if (isActive) OkColor else TitleColor,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        Text(
                                            m.sizeText + if (m.parts > 1) " · ${m.parts} 片" else "",
                                            fontSize = 10.sp, color = DimColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 推理设备 ----
                // 真机反馈：模型容器与「推理设备」之间的间距再增加一倍（10dp → 20dp）。
                Spacer(Modifier.height(20.dp))
                Text("推理设备", fontSize = 11.sp, color = DimColor)
                // 用户要求：「推理设备」↔设备按钮 与 设备按钮↔下方描述 两处间距保持一致，
                // 且描述下方留白要更大一些。原来前者 4dp、后者 0dp，都不统一。
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LocalDevice.values().forEach { d ->
                        val on = device == d
                        Box(
                            Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(if (on) Accent else Color(0xFF2A2A3E), RoundedCornerShape(9.dp))
                                .border(
                                    1.dp,
                                    if (on) Accent else Color(0xFF3A3A52),
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable(enabled = !st.running) {
                                    device = d
                                    // P2：记住选择；P9：切到该设备自己的日志槽位（不再清空历史）
                                    selectDevice(d)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                d.label,
                                fontSize = 11.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) Color.White else SubColor
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 用户要求：提示区压到「简短一行」+ 行高写死。三档都是固定短文案（中性、不带 ⚠ 告警），
                // 固定 1 行高度（14dp 盒 + lineHeight 14sp）：切设备行数永远不变，卡片高度恒定不跳屏。
                // 报错不再和提示词混用 —— 错误信息在下面 LOG_AREA 里独立一行（无错则整行不渲染）。
                Text(
                    when (device) {
                        // 第 6 点：CPU 提示固定成这一句（真机反馈的最终文案）
                        LocalDevice.CPU ->
                            "CPU：最稳. 但生成 tok/s 速度相对较慢"
                        // 第 2 点：GPU / NPU 提示词恢复原文（上一轮按「全部删掉」字面理解误删了）
                        LocalDevice.GPU ->
                            "Adreno GPU：OpenCL 后端，实测速度也挺快"

                        LocalDevice.NPU ->
                            "Hexagon NPU：走 HTP 后端，机型/驱动支持才真正加速"
                    },
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = DimColor
                )
                // 第 2 项（本轮 · 用户第二次调整）：间距改成「推理设备 → CPU 档位」的 **2 倍**。
                // 基准是上面「推理设备」标题行到三个档位胶囊之间的 8dp（真机视觉 ≈50px，
                // 因为胶囊高 48dp、文字居中，文字间看起来约 50px）。
                // 现在这里给 32dp（= 8dp 的 4 倍间距令牌，真机节点间距 ≈96px），
                // 正好是基准视觉间距的 2 倍 —— 修掉了上一轮"改小成 8dp 结果比基准还小"的问题。
                //   提示词 →(32dp)→ [报错行，默认隐藏] →(8dp)→ 当前设备行 →(4dp)→ 日志框
                Spacer(Modifier.height(32.dp))

                // ---- 错误 + 日志 ----
                // 第 4 项收尾（用户反馈「GPU 启动引擎后会跳屏」）：这一整块以前是
                // "有错误/有日志才渲染"，引擎一启动、第一行日志到达时，卡片瞬间长高
                // 约 110dp，把整页内容顶下去。现在外面套一个**恒定高度**的 Box，
                // 内容按底部对齐：有没有日志、错误行在不在，卡片高度都一样；
                // 没日志时下面两个 if 不渲染，这块表现为一段安静的留白（高度已预留，不会位移）。
                // 第 1 项（报错日志显示不全）：日志框点按可展开/收起。收起=定高不跳屏（流式），
                // 展开=铺全最近 ~20 行，让用户看清报错上下文；再点一次收起。
                var logExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (logExpanded) LOG_AREA_HEIGHT_EXPANDED else LOG_AREA_HEIGHT)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 第 2 项：内部改成**顶部对齐** —— 提示词到「当前设备」的可见留白
                            // 就正好是外面那个 8dp 间距（和「推理设备 → CPU」一致），
                            // 外壳里多出来的几 dp 余量落在底部，压在按钮上方的 12dp 里看不见。
                            .align(Alignment.TopStart)
                    ) {
                // 第 15 项 / 第 3 项：错误信息已搬到上面「设备备注提示词」正下方的恒定
                // 36dp 槽位里，这里不再重复渲染，这一块只负责日志与设备标注行。
                // 日志改三档**共用**（用户要求）：直接读 st.logTail（CPU/GPU/NPU 同一条流），
                // 自动回退全过程累加在里面，不再按设备切槽。
                val deviceLog = st.logTail
                val shown = displayLogFor(deviceLog, device)
                // 日志框可滚动 + 默认贴底：每次日志内容变化都等一帧布局完成再贴底
                // （终端式行为：流式输出时始终看到最新行；引擎安静下来后可自由上翻看历史）。
                val logScroll = rememberScrollState()
                LaunchedEffect(shown.text) {
                    // 等约一帧让布局完成（此时 logScroll.maxValue 才有效），再贴底显示最新
                    delay(16)
                    logScroll.scrollTo(logScroll.maxValue)
                }
                // 标注行 + 日志框**常显**（日志清空/未启动时也占着固定高度）：
                // 停止引擎清日志、启动重新生成（用户要求），高度永远不变，不跳屏。
                // 第 2 项：报错行放在这一块的**最顶部**（也就是设备提示词的正下方）——
                // 默认隐藏，只有真报错时才占 28dp（2 行 × 14sp，高度钉死，1 行/2 行都不改布局），
                // 并在它下面补 8dp，让"报错 → 当前设备"和"提示词 → 当前设备"用同一个间距令牌。
                // 需求 1/5 复用同一个 28dp 槽位显示「引擎替你做了什么」（notice）：
                // 有报错就显示报错，没报错但有回退提示就显示提示 —— 高度恒定，卡片不跳屏。
                val slotText = st.error.ifBlank { st.notice }
                if (slotText.isNotBlank()) {
                    Text(
                        slotText,
                        modifier = Modifier.fillMaxWidth().height(ERR_BLOCK_HEIGHT),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // 报错红、回退提示用暖黄：一眼区分「失败了」和「已经换好了」
                        color = if (st.error.isNotBlank()) ErrColor else WarnColor
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 第 7 / 8 点：GPU / NPU 档只保留「设备名 · 后端名」（GPU · Adreno / NPU · Hexagon），
                        // Adreno / Hexagon 后面原本跟的机型、QTI、HTP 版本等提示词全部删掉
                        //（明细没丢：机型推断 / 【HTP】选定版本 / 库路径 都还在下面的日志里）。
                        "当前设备：${device.label}",
                        modifier = Modifier,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 9.sp, color = DimColor
                    )
                    // 第 4 点：「当前设备：X」后面跟一小段引擎状态（实时刷新，口径同卡片右上方那颗状态点）：
                    //   未启动/启动失败 → 引擎就绪；启动中 → 启动中…；运行中 → 引擎运行中；缺失 → 引擎缺失。
                    // 小圆点 + 小字，颜色随状态（运行绿/启动中黄/就绪灰/缺失红），一眼看清引擎此刻啥情况。
                    Spacer(Modifier.width(6.dp))
                    val engineStatusColor = when {
                        st.running -> OkColor
                        st.starting -> WarnColor
                        st.enginePresent -> DimColor
                        else -> ErrColor
                    }
                    val engineStatusText = when {
                        st.starting -> "启动中…"
                        st.running -> "引擎运行中"
                        st.enginePresent -> "引擎就绪"
                        else -> "引擎缺失"
                    }
                    Box(Modifier.size(5.dp).background(engineStatusColor, RoundedCornerShape(50)))
                    Spacer(Modifier.width(3.dp))
                    Text(engineStatusText, fontSize = 9.sp, color = engineStatusColor)
                    // 撑满中间空隙，把右侧提示推到行尾
                    Spacer(Modifier.weight(1f))
                    if (shown.hidden > 0) {
                        // 很淡的小字：告诉用户原始日志里有后端探测噪声被隐藏了（NPU 的 HTP 探测 +
                        // 非 GPU 档的 OpenCL 平台探测），但 logcat / 内存里的原始日志一条都没丢
                        Text(
                            "已隐藏 ${shown.hidden} 条",
                            fontSize = 9.sp, color = DimColor.copy(alpha = 0.65f)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // 第 3 点：复制按钮（在 展开/收起 左边）—— 把共用环形缓冲里的**全部原始日志**
                    // （未过滤、最多 300 行，含机型推断 /【HTP】选定 / 库路径明细）写进系统剪贴板；
                    // 没有日志时不显示。成功提示走 onShowHint（顶部药丸，真机反馈：
                    // 别用系统 Toast，和全局的复制/删除提示统一成一套）。
                    if (st.logTail.isNotBlank()) {
                        Text(
                            "复制",
                            modifier = Modifier
                                .clickable {
                                    val full = st.logTail
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as? android.content.ClipboardManager
                                    if (cm != null && full.isNotBlank()) {
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText("引擎日志", full)
                                        )
                                        // 文案与对话页/历史页的复制药丸保持同一句
                                        onShowHint("已复制到剪贴板")
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp, color = Accent
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // 第 1 点：只有**有报错或警告日志**（slotText 非空）时才显示 展开/收起 按钮。
                    // 平时（正常日志 / 无报错）不显示，界面更干净；真出问题了按钮才冒出来让用户展开看全上下文。
                    if (slotText.isNotBlank()) {
                        Text(
                            if (logExpanded) "点按收起" else "点按展开",
                            modifier = Modifier
                                .clickable { logExpanded = !logExpanded }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp, color = Accent
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 固定高度 + 内部可滚动：日志刷新时卡片高度不跳（真机「闪屏」根治之一）。
                // 高度 150dp：内边距 8dp×2 之后可用 134dp ≈ 一屏 11 行 × 12sp lineHeight。
                // 第 2 项：有报错时缩到 114dp，把 36dp（= ERR_BLOCK_HEIGHT 28dp + 8dp 间距）
                // 让给上面的报错行 → 外壳高度恒定，报错出现/消失都不跳屏。
                // 整段日志（环形缓冲最多 300 行）都铺进去，由 verticalScroll 在框内滚动；
                // 默认贴底显示最新，上翻看历史。空日志时显示一行占位文案。
                // ⚠ 改这个高度必须同步重算文件顶部的 LOG_AREA_HEIGHT。
                // 第 1 项（显示不全）：点按在 收起(定高) ↔ 展开(LOG_BOX_HEIGHT_EXPANDED) 间切换；
                // 展开时外壳同步长到 LOG_AREA_HEIGHT_EXPANDED，把报错上下文铺全。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            if (logExpanded) LOG_BOX_HEIGHT_EXPANDED
                            else if (slotText.isNotBlank()) LOG_BOX_HEIGHT_WITH_ERR
                            else LOG_BOX_HEIGHT
                        )
                        .background(Color(0xFF16161F), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { logExpanded = !logExpanded }
                ) {
                    Text(
                        if (shown.text.isNotBlank()) shown.text else "（暂无日志 · 启动引擎后开始生成）",
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        color = if (shown.text.isNotBlank()) DimColor else DimColor.copy(alpha = 0.55f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(logScroll)
                    )
                }

                    }
                }

                // ---- 操作按钮 ----
                // P3：样式与 App 其他页面统一（10dp 圆角 + 白字 SemiBold 12sp；
                // 停止是红色填充款，重扫是描边款）；P6：扫描时按钮变「扫描中...」
                // 带转圈，扫描成功后模型列表自动刷新、按钮变回「重新扫描」。
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { rescan() },
                        enabled = !st.running && !st.scanning,
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCE93D8))
                    ) {
                        if (st.scanning) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp), strokeWidth = 2.dp,
                                color = Color(0xFFCE93D8)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("扫描中…", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("重新扫描", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (st.running || st.starting) {
                        Button(
                            onClick = { stop() },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7A2E3A))
                        ) {
                            Text("停止引擎", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    } else {
                        val pick = st.models.firstOrNull { it.path == selected }
                        Button(
                            onClick = { pick?.let { start(it, device) } },
                            enabled = pick != null && st.hasPermission,
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Text("启动引擎", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
                if (!st.hasPermission) {
                    Spacer(Modifier.height(6.dp))
                    Text("授权后回到本页，权限状态会自动更新", fontSize = 10.sp, color = DimColor)
                }
            }
        }
    }
}
