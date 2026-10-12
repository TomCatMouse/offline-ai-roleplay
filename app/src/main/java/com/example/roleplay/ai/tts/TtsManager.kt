package com.example.roleplay.ai.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 第 21 项：朗读的播放状态。气泡上那个图标按它三态切换：
 * IDLE（喇叭，点一下开始读）→ SPEAKING（暂停图标、动态变色，点一下暂停）
 * → PAUSED（播放图标，点一下从当前这句继续）。
 */
enum class TtsPlayState { IDLE, SPEAKING, PAUSED }

/**
 * AI 回复的语音朗读。
 *
 * 用系统自带 TextToSpeech：不占包体、不用联网、有中文引擎就能用
 * （MuMu / 真机通常自带讯飞或 Google 的中文引擎）。
 *
 * 一个刻意的取舍：**朗读时跳过圆括号里的动作描写**。
 * 角色卡强制模型把神态动作写在（ ）里，全念出来会变成
 * "她轻轻握住你的手 括号 指尖微微发颤 括号"，非常出戏。
 *
 * 第 21 项的暂停/继续：Android 的 TextToSpeech **没有真正的 pause/resume**
 * （只有 stop），所以这里把要读的文本先按句切块，逐句 QUEUE_FLUSH 播；
 * 暂停 = stop() 并记住"停在第几句"，继续 = 从那一句开头重读。
 * 听感上等价于"从刚才断的地方接着念"，且不会因为 stop 丢掉进度。
 */
class TtsManager(context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID = "roleplay_reply"

        /**
         * TTS 不可用时的统一提示语（反馈 #3）。
         *
         * 消息气泡上的「朗读」图标点不动时弹出的就是这句，与
         * 设置 → 语音朗读 (TTS) → 「去安装 / 检查 TTS 引擎」的说明保持同一份文案。
         */
        const val UNAVAILABLE_HINT =
            "手机需安装系统 TTS 引擎（讯飞 / Google 语音），装好后朗读才生效"
    }

    private var tts: TextToSpeech? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 引擎初始化失败的原因，给设置页显示用 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 第 21 项：三态播放状态（气泡图标用） */
    private val _playState = MutableStateFlow(TtsPlayState.IDLE)
    val playState: StateFlow<TtsPlayState> = _playState.asStateFlow()

    /**
     * 第 21 项：当前正在读的**气泡原文**（含动作描写，和 message.getFullText() 同一份）。
     * 聊天页拿它和每条消息比对，只有"正在读的那条"才显示暂停/继续图标。
     */
    private val _activeText = MutableStateFlow("")
    val activeText: StateFlow<String> = _activeText.asStateFlow()

    // ---- 分句朗读的进度 ----
    /** 待读的句子队列（extractSpeech 之后的文本切块） */
    private var chunks: List<String> = emptyList()
    /** 当前读到第几句（暂停后继续就从这里重读） */
    private var chunkIndex = 0
    private var speedNow: Float = 1.0f
    private var pitchNow: Float = 1.0f
    /**
     * 播放令牌：每次 speak / 暂停 都自增。
     * 旧 utterance 的回调（onDone/onError）带的令牌不匹配就直接丢弃 ——
     * 否则"暂停瞬间到达的 onDone"会把进度偷偷往前推一句。
     */
    private var playToken = 0

    /** 引擎还没就绪时暂存的朗读请求 */
    private var pendingText: String? = null
    private var pendingOriginal: String = ""
    private var pendingSpeed: Float = 1.0f
    private var pendingPitch: Float = 1.0f

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine == null) {
                    _error.value = "语音引擎初始化异常"
                    return@TextToSpeech
                }
                val zh = engine.setLanguage(Locale.CHINESE)
                if (zh == TextToSpeech.LANG_MISSING_DATA || zh == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 修 #7：系统没装中文语音包 ≠ 完全不能朗读。
                    // 回退到默认语言（引擎还能发声，中文按近似音读），
                    // 把原因写进 error 让设置页能看见，而不是装死。
                    engine.setLanguage(Locale.getDefault())
                    _error.value = "未安装中文语音包，已回退系统默认语音（建议安装讯飞/Google 语音）"
                    Log.w(TAG, _error.value!!)
                } else {
                    _error.value = null
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // 只认当前令牌：过期的开始回调不影响现在的状态
                        if (parseUtterance(utteranceId)?.first != playToken) return
                        _speaking.value = true
                        _playState.value = TtsPlayState.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        onChunkFinished(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "朗读出错: $utteranceId")
                        onChunkFinished(utteranceId)
                    }
                })
                _ready.value = true
                pendingText?.let { text ->
                    pendingText = null
                    // 用原文（气泡里那份）作为 activeText，图标比对才认得出来
                    speak(pendingOriginal.ifBlank { text }, pendingSpeed, pendingPitch)
                }
            } else {
                // 修 #7：把"为什么没声音"说清楚，设置页会显示
                _error.value = "系统没有可用的 TTS 服务（手机需安装系统 TTS 引擎，如讯飞/Google 语音）"
                Log.w(TAG, "TextToSpeech 初始化失败: $status")
            }
        }
    }

    /**
     * 朗读一段文本（从头开始）。
     *
     * @param text  AI 回复原文（含动作描写）——原样存进 activeText，供气泡图标比对
     * @param speed 语速 0.5~2.0
     * @param pitch 音调 0.5~2.0
     */
    fun speak(text: String, speed: Float, pitch: Float) {
        val spoken = extractSpeech(text)
        if (spoken.isBlank()) return

        _activeText.value = text
        chunks = splitChunks(spoken)
        chunkIndex = 0
        speedNow = speed
        pitchNow = pitch

        val engine = tts
        if (engine == null || !_ready.value) {
            // 引擎还没就绪（冷启动常见），先记下来，就绪后自动补读（保留语速音调）
            pendingText = spoken
            pendingOriginal = text
            pendingSpeed = speed
            pendingPitch = pitch
            Log.i(TAG, "引擎未就绪，暂存朗读: ${spoken.take(20)}...")
            return
        }
        playToken++
        speakChunk()
    }

    /**
     * 气泡上"朗读"图标的统一点击入口。
     *
     * 第 4 项（本轮）：改成**喇叭开 / 关两态** ——
     * - 点的是**正在读 / 暂停中**的那条 → 直接停止（图标回到最初的线框喇叭，
     *   对应"再点击又变回原本的喇叭符号"）
     * - 点的是别的消息（或读完了）→ 从这条的头开始读（图标变实心喇叭）
     * pause() / resume() 保留给系统自动暂停（来电、切后台）使用，不再由这个按钮触发；
     * 否则第二次点击只会停在"继续朗读"，永远回不到最初那个喇叭。
     */
    fun toggle(text: String, speed: Float, pitch: Float) {
        val same = _activeText.value == text
        when {
            same && _playState.value != TtsPlayState.IDLE -> stop()
            else -> speak(text, speed, pitch)
        }
    }

    /** 暂停：停住声音并记住进度（继续时从当前这句开头重读） */
    fun pause() {
        if (_playState.value != TtsPlayState.SPEAKING) return
        // 先改状态再 stop：stop 触发的 onDone 会看到 PAUSED / 旧令牌而丢弃，
        // 不会把 chunkIndex 往前推
        _playState.value = TtsPlayState.PAUSED
        _speaking.value = false
        playToken++
        tts?.stop()
    }

    /** 继续：从暂停的那一句接着读 */
    fun resume() {
        if (_playState.value != TtsPlayState.PAUSED) return
        if (chunks.isEmpty() || chunkIndex !in chunks.indices) {
            finishPlayback()
            return
        }
        _playState.value = TtsPlayState.SPEAKING
        speakChunk()
    }

    /** 彻底停止（切角色 / 页面销毁 / 手动打断），并清掉"正在读哪条"的标记 */
    fun stop() {
        playToken++
        tts?.stop()
        _speaking.value = false
        _playState.value = TtsPlayState.IDLE
        chunks = emptyList()
        chunkIndex = 0
        pendingText = null
        _activeText.value = ""
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        _ready.value = false
    }

    // ==================== 内部实现 ====================

    /** 播当前这一句（QUEUE_FLUSH：永远只播最新那一句，不排队积压） */
    private fun speakChunk() {
        val engine = tts ?: return
        if (chunkIndex !in chunks.indices) {
            finishPlayback()
            return
        }
        engine.setSpeechRate(speedNow.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitchNow.coerceIn(0.5f, 2.0f))
        engine.speak(
            chunks[chunkIndex],
            TextToSpeech.QUEUE_FLUSH,
            null,
            "$UTTERANCE_ID#$playToken#$chunkIndex"
        )
    }

    /** 一句播完：还有下一句就继续，否则整段结束回到 IDLE */
    private fun onChunkFinished(utteranceId: String?) {
        val parsed = parseUtterance(utteranceId) ?: return finishPlayback()
        val (token, idx) = parsed
        // 过期回调（已被暂停 / 切换 / 停止作废）——什么都不做
        if (token != playToken) return
        if (_playState.value != TtsPlayState.SPEAKING) return
        if (idx != chunkIndex) return
        if (chunkIndex + 1 < chunks.size) {
            chunkIndex++
            speakChunk()
        } else {
            finishPlayback()
        }
    }

    private fun finishPlayback() {
        _speaking.value = false
        _playState.value = TtsPlayState.IDLE
        chunks = emptyList()
        chunkIndex = 0
    }

    /** "roleplay_reply#令牌#句序号" → (令牌, 句序号) */
    private fun parseUtterance(id: String?): Pair<Int, Int>? {
        val parts = id?.split('#') ?: return null
        if (parts.size < 3) return null
        val token = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return token to index
    }

    /** 按句末标点切块：暂停/继续的粒度就是一句，太长会听着断得不自然，太短回调太密 */
    private fun splitChunks(text: String): List<String> {
        val parts = text.split(Regex("(?<=[。！？!?；;…])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(text) else parts
    }

    /**
     * 把回复整理成适合朗读的文本：去掉动作描写与装饰性 emoji。
     *
     * 注意用**全角括号**匹配：角色卡要求模型把动作写在（ ）里，
     * 半角 ( ) 在中英混排时也可能是正文的一部分，不做处理更安全。
     */
    private fun extractSpeech(text: String): String =
        text.replace(Regex("[（(][^）)]*[）)]"), "")
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
