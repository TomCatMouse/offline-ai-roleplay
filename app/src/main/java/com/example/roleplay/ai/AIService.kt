package com.example.roleplay.ai

import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.ChatMessage
import com.example.roleplay.data.model.Memory

/**
 * AI 服务接口 - 定义对话生成契约
 *
 * 实现：GGUFAIService（llama-server 真实模型推理）
 * 推理失败直接抛出异常，不做任何降级/模板回复
 */
interface AIService {

    /**
     * 生成 AI 回复
     *
     * @param memories   长期记忆（超出上下文窗口的往事），拼进角色卡的 system 通道
     * @param shouldStop 取消回调。**必须**被传到底层阻塞读循环里轮询 ——
     *                   协程 cancel() 无法中断 HttpURLConnection 的阻塞读
     * @param clientTag  会话标识，传 llama-server 作为 client_id 以钉住 slot 复用 KV 缓存
     */
    suspend fun generateResponse(
        character: Character,
        userMessage: String,
        history: List<ChatMessage>,
        memories: List<Memory> = emptyList(),
        onToken: (String) -> Unit = {},
        shouldStop: () -> Boolean = { false },
        clientTag: String? = null
    ): String

    /** 检查是否可用 */
    fun isAvailable(): Boolean

    /**
     * 立刻掐断正在进行的生成（反馈 #8）。
     *
     * 与 [generateResponse] 的 `shouldStop` 回调的区别：回调只能在 SSE 循环
     * 转到下一次迭代时生效，而 prefill 阶段阻塞读可能几十秒不返回；
     * 本方法是**主动断开底层连接**，按下即停。默认空实现，方便测试替身。
     */
    fun cancelGeneration() {}

    /** 加载模型 */
    suspend fun loadModel(): Result<Unit>

    /** 获取模型状态 */
    fun getModelStatus(): String
}
