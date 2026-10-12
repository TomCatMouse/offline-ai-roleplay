package com.example.roleplay.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全 App 可配置项。
 *
 * 为什么需要它：服务地址、推理参数原本全是**编译期常量**
 * （`ModelRegistry.SERVER_HOST`、`GGUFAIService.ROLEPLAY_TEMPERATURE` …），
 * 意味着 PC 换个 IP、想调一下温度，都得重新编译装 APK。
 *
 * 实现用 SharedPreferences 而不是 DataStore：
 * 这个体量的配置项不需要 DataStore 的事务与类型安全，
 * 而 SharedPreferences 能**同步读取**，省掉启动时的一次异步等待。
 *
 * 对外暴露 [state] 供 Compose 观察，UI 改完立刻全局生效。
 */
class AppSettings private constructor(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("roleplay_settings", Context.MODE_PRIVATE)

    /**
     * 一份不可变设置快照。
     *
     * 默认值都来自此前实测调优过的常量，保证「没进过设置页」的用户
     * 拿到的行为和以前完全一致。
     */
    data class Snapshot(
        // ---- 服务连接 ----
        // 默认包 → PC 的局域网地址；真机包 → 手机本机的内置引擎（见 defaultServerHost()）
        val serverHost: String = defaultServerHost(),
        val serverPort: Int = DEFAULT_INFER_PORT,
        val switchPort: Int = DEFAULT_SWITCH_PORT,

        // ---- 模型选择（对应 servers.config.json 里的 id）----
        val modelId: String = DEFAULT_MODEL_ID,

        // ---- 采样参数 ----
        val temperature: Float = 0.75f,
        val topP: Float = 0.9f,
        val maxTokens: Int = 1024,
        val repeatPenalty: Float = 1.1f,

        // ---- 上下文 ----
        /** 服务端 llama-server 的 --ctx-size，仅用于换算预算，不参与请求 */
        val contextSize: Int = 8192,

        // ---- 内容与体验 ----
        /**
         * 甜蜜升级模式。
         * 打开时会在角色卡里显式授权更亲密的剧情描写，并放宽长度限制。
         */
        val adultMode: Boolean = true,

        /**
         * 语音朗读 AI 回复。
         *
         * 本轮复核结论：ttsSpeed / ttsPitch **不是死键** —— 链路仍是活的：
         * MainActivity.kt:443 `tts.speak(text, settingsState.ttsSpeed, settingsState.ttsPitch)`
         *   → TtsManager.kt:102/115-116 `setSpeechRate(speed)` / `setPitch(pitch)`。
         * 只是设置页的朗读整块入口已按用户要求删除（SettingsScreen.kt:71-73 有注释），
         * 所以**没有 UI 能改这两个值**，取值恒为默认 1.0（除非旧版本装过、SP 里还留着改过的值）。
         *
         * 处理：保留这两个键，不删。理由：
         *  ① 消费端仍在读，删键就得连消费端一起改，等于顺手删掉"朗读语速/音调"这项仍在工作的能力；
         *  ② 朗读功能本身还在（气泡上的朗读图标），只是"调节入口"没了；
         *     键留着，将来若恢复入口不会丢用户以前设过的值；
         *  ③ 键保留后 read()/write() 仍然成对，不存在"写法与读法不一致"的脏值风险。
         */
        val ttsEnabled: Boolean = false,
        val ttsSpeed: Float = 1.0f,
        val ttsPitch: Float = 1.0f,

        /** 回复落库（关掉后只当前会话内存里可见） */
        val autoSaveHistory: Boolean = true,

        // ---- 用户自己的身份（本轮需求 #5）----
        /** 昵称：聊天页显示在"我"的气泡上方；空串 = 不显示 */
        val userName: String = "",
        /** 自定义头像的本地绝对路径（filesDir/avatars/user_avatar.jpg）；null = 用内置立绘 */
        val userAvatarPath: String? = null
    ) {
        /**
         * 留给"对话历史"的 token 预算。
         *
         * 只取 ctx 的 65%：还要塞角色卡（约 500 token）、
         * 长期记忆（约 300）、以及模型这次要生成的内容（[maxTokens]）。
         * 按 8192 的 ctx 算，历史能分到约 5300 token。
         */
        val historyTokenBudget: Int get() = (contextSize * 0.65f).toInt()
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** 同步读取当前值，供非 Compose 代码（HTTP 客户端等）使用 */
    val current: Snapshot get() = _state.value

    fun update(transform: (Snapshot) -> Snapshot) {
        val next = transform(_state.value)
        write(next)
        _state.value = next
    }

    /** 恢复出厂设置 */
    fun reset() = update { Snapshot() }

    // ==================== 持久化 ====================

    private fun read(): Snapshot {
        val d = Snapshot()
        return Snapshot(
            serverHost = sp.getString(KEY_HOST, d.serverHost) ?: d.serverHost,
            serverPort = sp.getInt(KEY_PORT, d.serverPort),
            switchPort = sp.getInt(KEY_SWITCH_PORT, d.switchPort),
            modelId = sp.getString(KEY_MODEL, d.modelId) ?: d.modelId,
            temperature = sp.getFloat(KEY_TEMP, d.temperature),
            topP = sp.getFloat(KEY_TOP_P, d.topP),
            maxTokens = sp.getInt(KEY_MAX_TOKENS, d.maxTokens),
            repeatPenalty = sp.getFloat(KEY_REPEAT, d.repeatPenalty),
            contextSize = sp.getInt(KEY_CTX, d.contextSize),
            adultMode = sp.getBoolean(KEY_ADULT, d.adultMode),
            ttsEnabled = sp.getBoolean(KEY_TTS, d.ttsEnabled),
            // UI 入口已移除（见 Snapshot.ttsSpeed 注释），但 TTS 仍在消费这两个值，
            // 所以继续读；没有历史值时 = 默认 1.0（正常语速 / 正常音调）。
            ttsSpeed = sp.getFloat(KEY_TTS_SPEED, d.ttsSpeed),
            ttsPitch = sp.getFloat(KEY_TTS_PITCH, d.ttsPitch),
            autoSaveHistory = sp.getBoolean(KEY_AUTOSAVE, d.autoSaveHistory),
            // 本轮需求 #5：用户昵称 / 自定义头像
            userName = sp.getString(KEY_USER_NAME, d.userName) ?: d.userName,
            userAvatarPath = sp.getString(KEY_USER_AVATAR, d.userAvatarPath)
        )
    }

    private fun write(s: Snapshot) {
        sp.edit()
            .putString(KEY_HOST, s.serverHost)
            .putInt(KEY_PORT, s.serverPort)
            .putInt(KEY_SWITCH_PORT, s.switchPort)
            .putString(KEY_MODEL, s.modelId)
            .putFloat(KEY_TEMP, s.temperature)
            .putFloat(KEY_TOP_P, s.topP)
            .putInt(KEY_MAX_TOKENS, s.maxTokens)
            .putFloat(KEY_REPEAT, s.repeatPenalty)
            .putInt(KEY_CTX, s.contextSize)
            .putBoolean(KEY_ADULT, s.adultMode)
            .putBoolean(KEY_TTS, s.ttsEnabled)
            // 这两个键保留：TTS 消费端仍在用（UI 入口已移除，取值恒为 1.0，见 Snapshot 注释）
            .putFloat(KEY_TTS_SPEED, s.ttsSpeed)
            .putFloat(KEY_TTS_PITCH, s.ttsPitch)
            .putBoolean(KEY_AUTOSAVE, s.autoSaveHistory)
            .putString(KEY_USER_NAME, s.userName)
            .putString(KEY_USER_AVATAR, s.userAvatarPath)
            .apply()
    }

    // ==================== 剧情线记忆（需求 #8）====================
    // 每个角色单独记住"最后一次查看的会话"。
    // 再次进入该角色的聊天时优先恢复这条剧情线；
    // 如果它已被删除，startChat 里查不到会自动回落到"最近一条"。

    /** 读取角色最近查看的会话 id（可能为 null） */
    fun lastViewedConversation(characterId: String): String? =
        sp.getString("last_conv_$characterId", null)

    /** 记住角色查看了某条会话 */
    fun rememberConversation(characterId: String, conversationId: String) {
        sp.edit().putString("last_conv_$characterId", conversationId).apply()
    }

    // ==================== 单例 ====================

    companion object {
        /** PC 版（默认包）默认连的地址 */
        const val DEFAULT_HOST = "192.168.1.100"

        /**
         * 当前这套包默认连哪儿：
         * - 默认包（BuildConfig.LOCAL_ENGINE=false）→ PC 的局域网地址，行为与以前完全一致
         * - 真机包 → 手机本机内置引擎 127.0.0.1（模型跑在手机里，不依赖 PC）
         */
        fun defaultServerHost(): String =
            com.example.roleplay.local.LocalEngineProvider.engine?.defaultServerHost ?: DEFAULT_HOST

        const val DEFAULT_INFER_PORT = 8080
        const val DEFAULT_SWITCH_PORT = 8090

        /**
         * 首次启动（SP 里还没有 model_id）时"用户选中"的模型 id。
         *
         * 修正记录：原值 `qwen3.5-4b-q6-k` 指向 Q6_K —— 虽然磁盘上有这个文件，
         * 但 `scripts/servers.config.json` 的 models[0] 原来也指向它，
         * 而 `DEFAULT_MODEL_ID` 实际对齐的是 PC 端正在跑的那个模型（Q4_K_M）。
         * 这导致设置页显示"已选 `qwen3.5-4b-q4-k-m`"但切换器列表首位却是
         * `qwen3.5-4b-q6-k`，信息不一致。
         *
         * 现已在 2026-10 修复：servers.config.json 的 models[0] 也改为 `qwen3.5-4b-q4-k-m`，
         * 与 DEFAULT_MODEL_ID 完全对齐。三档（Q4_K_M / Q6_K / Q4_0）都真实存在于
         * `models/gguf/`，切换器 GET /models 对它们都返回 `exists: true`。
         *
         * 现在取"对齐实跑模型"：
         *  - 切换器 /status 与 /models 的 active_id = `qwen3.5-4b-q4-k-m`
         *  - `scripts/.last_model` = `qwen3.5-4b-q4-k-m`
         *  - 8080 的 llama-server /v1/models 默认模型 = Qwen3.5-4B-Q4_K_M.gguf
         *  - servers.config.json models[0] = `qwen3.5-4b-q4-k-m` ✅ 已对齐
         *
         * 新 id 体系（与 `scripts/servers.config.json`、`ModelRegistry` 一一对应）：
         *  - `qwen3.5-4b-q4-k-m` → Qwen3.5-4B-Q4_K_M.gguf（默认档，速度/精度均衡）
         *  - `qwen3.5-4b-q6-k`   → Qwen3.5-4B-Q6_K.gguf（精度更高一档，速度更慢）
         *  - `qwen3.5-4b-q4-0`   → Qwen3.5-4B-Q4_0.gguf（体积最小一档，加载最快）
         *
         * 为什么不改成空串 / 运行时从服务端拉：见 GGUFRuntime.getActiveModelName() ——
         * 服务端查不到任何匹配时会回落到 ModelRegistry.defaultModel.name，
         * 空串只会让"显示的名字"依然错，而设置页单选框则一个都不选中。
         *
         * 注：真机包（phys）不使用该值 —— GGUFRuntime.refreshActiveModel() 在真机包直接 return，
         * 设置页的「模型切换」整块也被 hidesRemoteSwitcher 隐藏，模型名以本地引擎为准。
         */
        const val DEFAULT_MODEL_ID = "qwen3.5-4b-q4-k-m"

        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_SWITCH_PORT = "switch_port"
        private const val KEY_MODEL = "model_id"
        private const val KEY_TEMP = "temperature"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_REPEAT = "repeat_penalty"
        private const val KEY_CTX = "context_size"
        private const val KEY_ADULT = "adult_mode"
        private const val KEY_TTS = "tts_enabled"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_AUTOSAVE = "auto_save"
        // 本轮需求 #5：用户自己的昵称与头像
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_AVATAR = "user_avatar_path"

        @Volatile private var INSTANCE: AppSettings? = null

        fun get(context: Context): AppSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettings(context).also { INSTANCE = it }
            }
    }
}

