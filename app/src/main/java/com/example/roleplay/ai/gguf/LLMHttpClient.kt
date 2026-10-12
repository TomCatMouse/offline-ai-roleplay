package com.example.roleplay.ai.gguf

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用户主动停止生成。
 *
 * **故意携带已经生成的部分文本**：用户按"停止"通常不是想丢弃，
 * 而是"够了，就用这些"。丢掉的话，等了 40 秒的成果全白费。
 */
class GenerationCancelledException(val partial: String) : Exception("已停止生成")

/**
 * LLM HTTP Client - Single model inference
 *
 * Server (Windows PC):
 *   - 8080: llama-server（模型由启动脚本 -m 指定）
 *
 * Inference URL: http://host:8080/v1/chat/completions
 *
 * 关于取消：本类的读写都是 `HttpURLConnection` 的**阻塞调用**，
 * 协程的 `cancel()` 不会中断阻塞中的 `readLine()`。
 * 所以取消必须靠 [shouldStop] 回调在 SSE 循环里主动轮询，
 * 命中后立刻 `disconnect()` 断开连接，否则会一直读到模型把 512 token 吐完。
 */
class LLMHttpClient(
    private val serverHost: String = DEFAULT_SERVER_HOST,
    private val serverPort: Int = DEFAULT_SERVER_PORT
) {
    companion object {
        private const val TAG = "LLMHttpClient"
        const val DEFAULT_SERVER_HOST = "192.168.1.100"
        const val DEFAULT_SERVER_PORT = 8080

        /**
         * 采样参数的"不传参"默认值。
         *
         * 本轮修正：原先这里是 4096 / 0.8，与存储层默认值（AppSettings.Snapshot
         * 的 maxTokens = 1024、temperature = 0.75，见 AppSettings.kt:43,45）**不一致**。
         * 目前所有调用链（ChatViewModel → GGUFAIService:79-82 / MainActivity:548-552、
         * 598-609 生成角色卡）都显式传了这四个参数，所以差异暂时不生效；
         * 但只要将来有人漏传一个，请求体就会静默用上 4096/0.8，
         * 用户"设置页调好的值"被无声忽略，且极难排查。
         *
         * 现在把默认值对齐存储层，保证「不传参 == 设置页默认」语义一致。
         * top_p / repeat_penalty 本来就与存储层相同（0.9 / 1.1），保持不变。
         * 注意：这里**不是**去读 AppSettings —— 客户端不该依赖配置存储，
         * 显式传参仍是正道，默认值只是兜底。
         */
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TEMPERATURE = 0.75f
        const val DEFAULT_TOP_P = 0.9f

        /** 重复惩罚，抑制复读与自问自答 */
        const val DEFAULT_REPEAT_PENALTY = 1.1f

        /**
         * 存在惩罚：某个词只要出现过就扣分，专治跨轮"口头禅"复读。
         * 注意不能调大：过强会把模型逼向异体字/繁体字等怪异替代（实测 0.4 会出现该退化）。
         */
        const val DEFAULT_PRESENCE_PENALTY = 0.2f

        /** 频率惩罚：出现次数越多扣分越重，同样保持温和 */
        const val DEFAULT_FREQUENCY_PENALTY = 0.1f

        /**
         * 正在进行的 HTTP 连接（key = clientId，空串代表未指定）。
         *
         * 为什么需要它（反馈 #8「点停止不能立即停」）：
         * 光靠 SSE 循环里轮询 shouldStop 是不够的 —— 模型在 **prefill / 憋第一个
         * token** 时 readLine() 会一直阻塞（长上下文下可能几十秒），这段时间里
         * 轮询根本没有机会执行，用户就感觉"点了停止没反应"。
         * 现在把连接登记下来，停止时从**另一个线程**直接 disconnect()，
         * 阻塞中的读立刻抛异常，实现"按下即停"。
         */
        private val activeConnections =
            java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()

        /** 立刻掐断所有正在进行的生成（线程安全，可从任意线程调用） */
        fun cancelActiveConnections() {
            val conns = activeConnections.values.toList()
            if (conns.isEmpty()) return
            Log.i(TAG, "主动断开 ${conns.size} 个进行中的连接")
            conns.forEach { c ->
                try {
                    c.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "disconnect 失败: ${e.message}")
                }
            }
            activeConnections.clear()
        }

        private const val THINK_OPEN = " thinking"
        private const val THINK_CLOSE = "</think>"

        /**
         * 去掉  thinking...</think> 思考块，并做中文归一化（非流式场景用）。
         *
         * 三步走，缺一不可：
         *  1. 成对的  thinking...</think>
         *  2. 只有开头没有结尾的（被 max_tokens 截断）
         *  3. 残留的裸标记 —— 实测 Qwen3-1.7B 会直接吐出 "</think>" 而没有对应开头，
         *     若不单独清理就会原样显示给用户
         */
        fun stripThink(text: String): String =
            ChineseTextNormalizer.normalize(
                text.replace(Regex("(?s) thinking.*?</think>"), "")
                    .replace(Regex("(?s) thinking.*$"), "")
                    .replace(THINK_CLOSE, "")
                    .replace(THINK_OPEN, "")
            ).trim()
    }

    private val baseUrl: String
        get() = "http://$serverHost:$serverPort"

    /** Check if server is reachable */
    fun ping(): Boolean {
        return try {
            Log.d(TAG, "Connecting ${baseUrl}/health ...")
            val conn = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "health response: $code")
            code == 200
        } catch (e: Exception) {
            Log.w(TAG, "Connection failed: ${e.message}")
            false
        }
    }

    /**
     * Streaming inference (SSE): callbacks onToken per token, returns full text
     * @param clientId llama-server client_id to pin slot for KV cache reuse
     */
    fun generateStream(
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE,
        topP: Float = DEFAULT_TOP_P,
        repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
        clientId: String? = null,
        shouldStop: () -> Boolean = { false },
        onToken: (String) -> Unit = {}
    ): String {
        // filter 提到 try 外面：连接被停止信号掐断时，catch 分支还要用它取已生成的部分
        val filter = ThinkFilter(onToken)
        return try {
            Log.i(TAG, "Streaming inference: host=$serverHost:$serverPort, history=${history.size} msgs, maxTokens=$maxTokens, temp=$temperature, clientId=$clientId")

            // 流式场景下实时剥离  thinking 思考块，保证 UI 与入库文本都干净
            try {
                requestOnce(
                    systemPrompt, userMessage, history,
                    maxTokens, temperature, topP, repeatPenalty,
                    clientId, stream = true, shouldStop = shouldStop
                ) { delta -> filter.feed(delta) }
            } catch (e: GenerationCancelledException) {
                // 把过滤器里已经可见的部分取出来，连同取消信号一起往上抛
                filter.flush()
                throw GenerationCancelledException(filter.text().trim())
            }
            filter.flush()
            val full = filter.text().trim()

            // 保底：若流式内容为空（思考占满配额），用 2 倍配额非流式重试
            if (full.isEmpty()) {
                val retryTokens = maxTokens * 2
                Log.w(TAG, "Streaming content is empty, retrying non-streaming with $retryTokens tokens")
                val retried = requestOnce(
                    systemPrompt, userMessage, history,
                    retryTokens, temperature, topP, repeatPenalty,
                    clientId, stream = false, shouldStop = shouldStop, onToken = null
                ).trim()
                if (retried.isNotEmpty()) onToken(retried)
                if (retried.isEmpty()) {
                    throw Exception("Model returned empty twice (reasoning content too long). Restart inference service with --reasoning off")
                }
                Log.i(TAG, "Streaming retry successful, response length: ${retried.length}")
                return retried
            }

            Log.i(TAG, "Streaming inference successful, response length: ${full.length}")
            full
        } catch (e: GenerationCancelledException) {
            throw e   // 取消不是错误，原样上抛，别被下面的 catch 包成"推理失败"
        } catch (e: Exception) {
            // 连接被"停止"掐断时可能抛在这些位置（写 body / 读响应头阶段），
            // 同样按取消处理，保留已生成的部分
            if (shouldStop()) {
                Log.i(TAG, "生成被停止（${e.javaClass.simpleName}），保留 ${filter.text().length} 字")
                filter.flush()
                throw GenerationCancelledException(filter.text().trim())
            }
            Log.e(TAG, "Streaming inference failed: ${e.message}", e)
            throw Exception("Inference service call failed: ${e.message}")
        }
    }

    /**
     * Send a single inference request (supports streaming and non-streaming)
     * @param clientId optional llama-server client_id (pins slot for KV cache reuse)
     * @param stream whether to use streaming (SSE)
     * @param onToken callback per token during streaming; ignored for non-streaming
     * @return full content string
     */
    private fun requestOnce(
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        clientId: String?,
        stream: Boolean = false,
        shouldStop: () -> Boolean = { false },
        onToken: ((String) -> Unit)? = null
    ): String {
        val messages = JSONArray()

        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", systemPrompt)
        messages.put(systemMsg)

        history.forEach { (role, content) ->
            val m = JSONObject()
            m.put("role", if (role == "assistant") "assistant" else "user")
            m.put("content", content)
            messages.put(m)
        }

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userMessage)
        messages.put(userMsg)

        val body = JSONObject()
        body.put("messages", messages)
        body.put("max_tokens", maxTokens)
        body.put("temperature", temperature.toDouble())
        body.put("top_p", topP.toDouble())
        body.put("repeat_penalty", repeatPenalty.toDouble())
        body.put("presence_penalty", DEFAULT_PRESENCE_PENALTY.toDouble())
        body.put("frequency_penalty", DEFAULT_FREQUENCY_PENALTY.toDouble())
        body.put("stream", stream)
        if (!clientId.isNullOrBlank()) {
            body.put("client_id", clientId)
        }

        // 真机本地引擎专用：Qwen3 / Qwen3.5 这一系小模型默认开「思考模式」，
        // 会在  thinking…</think> 里先写上几百上千字。App 侧虽然会实时剥掉思考块，
        // 但用户看到的就是气泡里一直「模型思考中...」（真机反馈的正是这个现象）。
        //
        // 用户要求（本轮追加需求 2）：不再用请求体的 chat_template_kwargs.enable_thinking
        // 来关，改成在**引擎启动参数**里加 `--reasoning off`（见 PhysLocalEngine 的启动参数列表）。
        // 实测确认 b11523 的 llama-server 支持 `-rea, --reasoning [on|off|auto]`，拼写无误；
        // 放在服务端一次生效，所有请求都受益，也不用每轮都往 body 里塞模板参数。
        // 默认包连的是 PC 服务端（本来就用 --reasoning off 起的），行为同样不变。

        Log.d(TAG, "Request body length: ${body.toString().length} chars")

        val conn = (URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 300000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        // 登记连接：停止按钮可以从别的线程直接掐断它（反馈 #8）
        val connKey = clientId?.takeIf { it.isNotBlank() } ?: ""
        activeConnections[connKey] = conn
        // 极端情况：用户在写 body 之前就按了停止
        if (shouldStop()) {
            activeConnections.remove(connKey)
            conn.disconnect()
            throw GenerationCancelledException("")
        }
        conn.outputStream.use { out ->
            out.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val inputStream = conn.inputStream
        val reader = inputStream.bufferedReader(Charsets.UTF_8)

        return try {
            if (code != 200) {
                val errText = reader.readText()
                Log.e(TAG, "HTTP $code: $errText")
                throw Exception("Server error $code: $errText")
            }

            if (stream) {
                readSSEStream(conn, shouldStop, onToken)
            } else {
                val text = reader.readText()
                val json = JSONObject(text)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    // 同流式路径：JSON null 不能用 optString 取，否则会得到字符串 "null"
                    val raw = if (message == null || message.isNull("content")) "" else message.optString("content", "")
                    stripThink(raw)
                } else {
                    ""
                }
            }
        } finally {
            activeConnections.remove(connKey)
            reader.close()
            conn.disconnect()
        }
    }

    // ===== SSE Stream Parsing =====

    /**
     * Parse SSE stream, callback onToken for each token
     *
     * llama-server SSE format:
     *   event: message
     *   data: {"choices":[{"delta":{"content":"..."},"finish_reason":null}]}
     *
     *   event: done
     *   data: {"done":true}
     */
    private fun readSSEStream(
        conn: HttpURLConnection,
        shouldStop: () -> Boolean,
        onToken: ((String) -> Unit)?
    ): String {
        val sb = StringBuilder()
        val input = conn.inputStream.bufferedReader(Charsets.UTF_8)
        var line: String?

        try {
            while (input.readLine().also { line = it } != null) {
                // 每个 chunk 检查一次取消。
                // 这里是 SSE 循环里的"取消逃生口"；真正的"按下即停"由
                // cancelActiveConnections() 从另一个线程 disconnect() 保证 ——
                // 因为 prefill 阶段 readLine() 会长时间阻塞，轮询根本排不上队。
                if (shouldStop()) {
                    Log.i(TAG, "检测到停止信号，已接收 ${sb.length} 字，断开连接")
                    throw GenerationCancelledException("")
                }

                val raw = line!!
                if (!raw.startsWith("data:")) continue
                val jsonStr = raw.removePrefix("data:").trim()
                if (jsonStr.isBlank()) continue

                try {
                    val json = JSONObject(jsonStr)
                    val choices = json.optJSONArray("choices") ?: continue
                    val choice = choices.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue
                    // 注意：llama-server 首个 chunk 通常是 {"delta":{"role":"assistant","content":null}}。
                    // Android 的 org.json 里 optString 遇到 JSONObject.NULL 会返回字符串 "null"，
                    // 直接用就会把 "null" 当 token 拼进回复，并随历史回灌污染后续上下文。必须显式判空。
                    val content = if (delta.isNull("content")) "" else delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        sb.append(content)
                        onToken?.invoke(content)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE parse failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // 被 cancelActiveConnections() 掐断时，readLine() 会抛 IOException /
            // SocketException。如果这正是用户按的停止，就翻译成"取消"而不是"推理失败"。
            if (shouldStop()) {
                Log.i(TAG, "连接被停止信号掐断，已接收 ${sb.length} 字")
                throw GenerationCancelledException("")
            }
            throw e
        } finally {
            // 正常结束、取消、异常都要断，否则连接会挂在那占着服务端的 slot
            try { input.close() } catch (_: Exception) {}
            conn.disconnect()
        }
        return sb.toString()
    }

    /**
     * 流式过滤  thinking...</think> 思考块。
     *
     * 小模型（Qwen3 等）即使服务端 --reasoning off，仍可能吐出思考标记；
     * 直接展示会让用户看到模型"自言自语"，也会污染入库历史。
     *
     * 实现要点：跨 SSE chunk 的标记会被切成两半（如 "<th" + "ink>"），
     * 因此未命中标记时只放行到"可能是半个标记"之前，尾巴留在缓冲区等下一个 chunk。
     */
    private class ThinkFilter(private val emit: (String) -> Unit) {
        private val visible = StringBuilder()
        private val hold = StringBuilder()
        private var inThink = false

        fun feed(delta: String) {
            hold.append(delta)
            process(final = false)
        }

        fun flush() {
            process(final = true)
        }

        fun text(): String = visible.toString()

        private fun process(final: Boolean) {
            while (true) {
                if (inThink) {
                    val idx = hold.indexOf(THINK_CLOSE)
                    if (idx >= 0) {
                        hold.delete(0, idx + THINK_CLOSE.length)
                        inThink = false
                    } else {
                        // 只保留可能是"半个结束标记"的尾巴
                        val keep = THINK_CLOSE.length - 1
                        if (final) hold.setLength(0)
                        else if (hold.length > keep) hold.delete(0, hold.length - keep)
                        return
                    }
                } else {
                    // 同时找开标记和闭标记，取更靠前的那个处理。
                    // 只看开标记是不够的：实测模型会吐出没有配对的裸 "</think>"，
                    // 那样就会被当成正文发给用户。
                    val openIdx = hold.indexOf(THINK_OPEN)
                    val closeIdx = hold.indexOf(THINK_CLOSE)
                    val idx = when {
                        openIdx < 0 -> closeIdx
                        closeIdx < 0 -> openIdx
                        else -> minOf(openIdx, closeIdx)
                    }
                    if (idx > 0) {
                        emitVisible(hold.substring(0, idx))
                        val isOpen = (idx == openIdx)
                        hold.delete(0, idx + if (isOpen) THINK_OPEN.length else THINK_CLOSE.length)
                        if (isOpen) inThink = true
                    } else if (idx == 0) {
                        val isOpen = (openIdx == 0)
                        hold.delete(0, if (isOpen) THINK_OPEN.length else THINK_CLOSE.length)
                        if (isOpen) inThink = true
                    } else {
                        // 尾巴可能正好是半个标记，开闭标记都要防，保留较长的那个长度
                        val keep = maxOf(THINK_OPEN.length, THINK_CLOSE.length) - 1
                        if (final) {
                            emitVisible(hold.toString())
                            hold.setLength(0)
                        } else if (hold.length > keep) {
                            val cut = hold.length - keep
                            emitVisible(hold.substring(0, cut))
                            hold.delete(0, cut)
                        }
                        return
                    }
                }
            }
        }

        private fun emitVisible(s: String) {
            if (s.isEmpty()) return
            // 输出侧兜底：繁体字漂移与外文标点在逐字流式阶段就修掉
            val clean = ChineseTextNormalizer.normalize(s)
            visible.append(clean)
            emit(clean)
        }
    }
}
