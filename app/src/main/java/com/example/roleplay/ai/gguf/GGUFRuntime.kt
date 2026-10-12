package com.example.roleplay.ai.gguf

import android.content.Context
import android.util.Log
import com.example.roleplay.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 推理服务管理器。
 *
 * 架构：
 *   App --推理(8080)--> PC llama-server（由 model_switcher.py 托管）
 *   App --控制(8090)--> PC model_switcher.py（切换模型）
 *
 * 与旧版的区别：
 *   1. 服务地址不再写死，每次请求都从 [AppSettings] 现读 —— 设置页改完立刻生效
 *   2. 生成支持中途取消（详见 [LLMHttpClient] 的注释）
 *   3. 新增模型切换能力
 */
class GGUFRuntime(
    private val context: Context,
    private val settings: AppSettings
) {

    companion object {
        private const val TAG = "GGUFRuntime"
    }

    /** UI 展示用：当前模型信息 */
    data class ModelInfo(
        val id: String,
        val name: String,
        val description: String,
        val active: Boolean
    )

    private val _modelInfos = MutableStateFlow<List<ModelInfo>>(emptyList())
    val modelInfos: StateFlow<List<ModelInfo>> = _modelInfos.asStateFlow()

    /** 服务端实际加载的模型 id（可能和设置里选的不一致，以服务端为准） */
    private val _activeModelId = MutableStateFlow("")
    val activeModelId: StateFlow<String> = _activeModelId.asStateFlow()

    /**
     * 反馈 #11：PC 端模型清单缓存（配置项 + models/gguf 自动扫到的）。
     * null = 还没成功拉到过，此时退回 [ModelRegistry] 的内置清单。
     */
    @Volatile private var remoteModels: List<ModelSwitcher.RemoteModel>? = null

    @Volatile private var isLoaded = false

    /** 每次都用最新的地址构造，构造开销可忽略（无状态的 HTTP 封装） */
    private fun client() = LLMHttpClient(
        serverHost = settings.current.serverHost,
        serverPort = settings.current.serverPort
    )

    private val switcher = ModelSwitcher(
        hostProvider = { settings.current.serverHost },
        portProvider = { settings.current.switchPort }
    )

    /**
     * 初始化：连接推理服务并同步服务端的真实模型。
     */
    suspend fun loadModel(): Result<Unit> {
        return try {
            val host = settings.current.serverHost
            val port = settings.current.serverPort
            Log.i(TAG, "初始化推理服务 $host:$port")

            val reachable = withContext(Dispatchers.IO) { client().ping() }
            if (!reachable) {
                isLoaded = false
                // G20：文案不带「PC 端」—— 真机包里推理服务就跑在本机，
                // 统一写成中性的「推理服务」（主机 + 端口已附上，排障信息不丢）
                return Result.failure(
                    Exception("推理服务不可达 $host:$port，请确认推理服务已启动")
                )
            }

            isLoaded = true
            refreshActiveModel()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败: ${e.message}", e)
            isLoaded = false
            Result.failure(e)
        }
    }

    /**
     * 从切换器读取真正在跑的模型，并用它刷新展示列表。
     *
     * 为什么必须问服务端而不是直接用设置：设置里存的只是"用户希望用哪个"，
     * 而服务端可能因为启动顺序、切换失败等原因跑的是另一个。
     * 界面显示的名字必须等于实际在跑的模型，否则就是在骗用户。
     */
    suspend fun refreshActiveModel() {
        // 真机包连的是手机本机引擎（127.0.0.1:8080），根本没有 PC 的 model_switcher（8090）。
        // 不拦的话每 30 秒的自动重连都会去连一次 8090 并打一条 warning：
        //   「切换器不可用，回退到设置值: Failed to connect to /127.0.0.1:8090」
        // 既没意义，也和「真机完全离线、服务器配置整块隐藏」的设定自相矛盾。
        // 真机的模型名以本地引擎为准（MainActivity 里用 localModelName 覆盖）。
        if (com.example.roleplay.local.LocalEngineProvider.isPhysBuild) return
        val status = switcher.status()
        status.onSuccess { active ->
            _activeModelId.value = active.id
            Log.i(TAG, "服务端当前模型: ${active.id} (${active.name})")
            // 修 #3：把设置里"用户选中的模型"同步成服务端**真正在跑**的模型。
            // 否则服务端重启/换模型后，设置页会一边显示 A（已选）一边显示 B（运行中），
            // 用户看到的就是"两个地方的模型不一致"。
            if (active.id.isNotBlank() && active.id != settings.current.modelId) {
                Log.i(TAG, "同步选中模型: ${settings.current.modelId} → ${active.id}")
                settings.update { it.copy(modelId = active.id) }
            }
        }.onFailure {
            // 切换器不在线（比如用户直接跑 run_servers.bat），退回用设置里的值展示
            Log.w(TAG, "切换器不可用，回退到设置值: ${it.message}")
            _activeModelId.value = settings.current.modelId
        }
        // 反馈 #11：顺手刷新 PC 端模型清单（含 models/gguf 自动扫到的模型）
        refreshModelList()
        updateModelInfos()
    }

    /**
     * 生成 AI 回复。
     *
     * 消息结构：角色卡走 system，对话历史是带 role 的独立消息，
     * 只有"用户这一句"走 user。历史若被拍平成一段文本塞进 user，
     * 模型会把它当"待分析的材料"而不是"已经发生的对话"，表现为答非所问。
     *
     * @param clientTag llama-server 的 client_id，钉住 slot 复用 KV 缓存（同一会话内应保持不变）
     * @param externalStop 外部取消信号（用户点了"停止"）
     */
    suspend fun generateResponse(
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        clientTag: String? = null,
        externalStop: () -> Boolean = { false },
        onToken: (String) -> Unit = {}
    ): Result<String> {
        return try {
            if (!isLoaded) {
                return Result.failure(Exception("推理服务未连接"))
            }

            Log.d(TAG, "开始生成: system=${systemPrompt.length}字, 历史=${history.size}条, 用户输入=${userMessage.length}字")

            val result = withContext(Dispatchers.IO) {
                // 两个取消来源：用户在 UI 上按了停止，或这个协程所在的作用域被取消
                // （比如退出聊天页）。阻塞中的 readLine 不会响应协程取消，
                // 必须靠这个回调把信号传进 SSE 循环。
                val job = currentCoroutineContext()[Job]
                client().generateStream(
                    systemPrompt = systemPrompt,
                    userMessage = userMessage,
                    history = history,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    repeatPenalty = repeatPenalty,
                    clientId = clientTag,
                    shouldStop = { externalStop() || job?.isActive == false },
                    onToken = onToken
                )
            }
            Log.d(TAG, "推理完成，回复长度: ${result.length}")
            Result.success(result)
        } catch (e: GenerationCancelledException) {
            Log.i(TAG, "生成被用户停止，保留 ${e.partial.length} 字")
            // 取消不是失败：把已生成的部分作为成功结果返回，由调用方决定去留
            Result.success(e.partial)
        } catch (e: Exception) {
            Log.e(TAG, "推理失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 切换服务端加载的模型（阻塞到新模型就绪） */
    suspend fun switchModel(modelId: String): Result<String> {
        val result = switcher.switch(modelId)
        if (result.isSuccess) {
            // 切完服务端要重新加载 gguf，重新 ping 一次确认真的通了
            isLoaded = withContext(Dispatchers.IO) { client().ping() }
            refreshActiveModel()
        }
        return result
    }

    /** 切换器（8090）是否在线 */
    suspend fun isSwitcherAvailable(): Boolean = switcher.isAvailable()

    /** 建议 #6：PC 端模型文件 + 磁盘空间（只读） */
    suspend fun diskInfo(): Result<ModelSwitcher.DiskInfo> = switcher.diskInfo()

    /** 测试给定地址是否能连通（设置页的「测试连接」按钮） */
    suspend fun testConnection(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            LLMHttpClient(host, port).ping()
        } catch (e: Exception) {
            false
        }
    }

    private fun updateModelInfos() {
        val activeId = _activeModelId.value
        val remote = remoteModels
        _modelInfos.value = if (remote.isNullOrEmpty()) {
            // 还没从 PC 拉到清单（切换器离线）→ 用 App 内置的静态清单兜底
            ModelRegistry.models.map { profile ->
                ModelInfo(
                    id = profile.id,
                    name = profile.name,
                    description = profile.description,
                    active = profile.id == activeId
                )
            }
        } else {
            // 反馈 #11：以 PC 端清单为准 —— 里面既有配置项，也有 models/gguf 里
            // 自动扫到的模型，所以"丢个 gguf 进文件夹"手机上立刻能看到。
            remote.map { m ->
                val known = ModelRegistry.models.firstOrNull { it.id == m.id }
                ModelInfo(
                    id = m.id,
                    name = m.name,
                    description = known?.description ?: buildString {
                        append("models/gguf 自动扫描")
                        if (m.size > 0) {
                            append("（")
                            append(String.format(java.util.Locale.US, "%.2f", m.size / 1073741824.0))
                            append(" GB）")
                        }
                    },
                    active = m.id == activeId
                )
            }
        }
    }

    /**
     * 反馈 #11：拉取 PC 端完整模型清单（配置项 + models/gguf 自动扫到的）。
     * 失败不做任何破坏性处理 —— 继续用上一次的清单 / 内置清单。
     */
    suspend fun refreshModelList() {
        switcher.listModels()
            .onSuccess { list ->
                if (list.isNotEmpty()) {
                    remoteModels = list
                    Log.i(TAG, "模型清单已更新: ${list.size} 个（含自动扫描 ${list.count { it.auto }} 个）")
                }
            }
            .onFailure { Log.w(TAG, "模型清单拉取失败，沿用上一份: ${it.message}") }
    }

    fun isModelLoaded(): Boolean = isLoaded

    /**
     * 立刻掐断正在进行的生成（反馈 #8）。
     * 委托给 [LLMHttpClient.cancelActiveConnections]，从调用线程直接 disconnect。
     */
    fun cancelGeneration() {
        LLMHttpClient.cancelActiveConnections()
    }

    fun getActiveModelName(): String {
        val id = _activeModelId.value.ifBlank { settings.current.modelId }
        return remoteModels?.firstOrNull { it.id == id }?.name
            ?: ModelRegistry.models.firstOrNull { it.id == id }?.name
            ?: ModelRegistry.defaultModel.name
    }

    fun release() {
        Log.d(TAG, "释放推理服务...")
        isLoaded = false
    }

    fun getModelInfo(): String {
        val s = settings.current
        return buildString {
            appendLine("推理服务:")
            appendLine("  - 已连接: $isLoaded")
            appendLine("  - 当前模型: ${getActiveModelName()}")
            appendLine("  - 推理: ${s.serverHost}:${s.serverPort}")
            appendLine("  - 切换器: ${s.serverHost}:${s.switchPort}")
        }
    }
}
