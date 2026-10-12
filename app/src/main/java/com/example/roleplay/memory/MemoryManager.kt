package com.example.roleplay.memory

import com.example.roleplay.data.model.ChatMessage
import com.example.roleplay.data.model.Memory

/**
 * 记忆管理器 - 从对话里抽取值得长期记住的条目。
 *
 * 设计取舍：**用确定性规则而不是再调一次模型**。
 * 让模型总结质量更高，但每次都要多等十几秒（4B 模型 4.7 tok/s），
 * 而且要在后台悄悄插入一次推理调用，会打断用户正在进行的对话。
 * 规则法零延迟、零成本、结果可预测，对这个场景足够。
 *
 * 上一版有个真 bug 已修复，见 [getRelevantMemories]。
 */
class MemoryManager {
    private val MIN_OVERLAP = 0.2f

    companion object {
        /** 单个角色最多保留的记忆条数，超出后按重要度+时间淘汰 */
        private const val MAX_MEMORIES = 40

        /** 记忆内容的长度上限，避免一条记忆吃掉整个预算 */
        private const val MAX_CONTENT_LEN = 80

        /** 偏好类关键词（命中即提取） */
        private val PREFERENCE_KEYWORDS = listOf(
            "我喜欢", "我不喜欢", "我爱", "我讨厌", "我想要", "我害怕",
            "我擅长", "我不擅长", "我最喜欢", "我的爱好", "我怕"
        )

        /** 事件类关键词 */
        private val EVENT_KEYWORDS = listOf(
            "我们", "第一次", "那天", "后来", "记得", "以前", "小时候",
            "约定", "答应", "秘密", "生日", "名字叫"
        )

        /** 身份/事实类关键词 —— 用户主动交代的设定信息最值得记 */
        private val FACT_KEYWORDS = listOf(
            "我是", "我叫", "我的名字", "我住", "我在", "我今年", "我今年",
            "我的工作", "我是做", "我养", "我有一只"
        )
    }

    /**
     * 从一段对话里提取记忆。
     *
     * @param messages    要分析的对话（通常只传"还没被总结过"的那一段）
     * @param characterId 归属角色
     */
    fun extractMemories(messages: List<ChatMessage>, characterId: String): List<Memory> {
        if (messages.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val out = mutableListOf<Memory>()
        val seen = mutableSetOf<String>()

        for (msg in messages) {
            val text = msg.getFullText().trim()
            if (text.length < 4) continue

            val (type, importance) = when {
                PREFERENCE_KEYWORDS.any { text.contains(it) } -> Memory.TYPE_PREFERENCE to 3
                FACT_KEYWORDS.any { text.contains(it) } -> Memory.TYPE_FACT to 3
                // 事件只从用户的叙述里提取：AI 自己编的情节不该固化成"记忆"，
                // 否则模型中途跑偏一次，那个跑偏的内容就会被永久记住并不断强化。
                !msg.isAssistant && EVENT_KEYWORDS.any { text.contains(it) } ->
                    Memory.TYPE_EVENT to 2
                else -> continue
            }

            val content = condense(text)
            if (content.isBlank()) continue
            // 内容级去重：同一件事说两遍只记一条
            if (!seen.add(content)) continue

            out.add(
                Memory(
                    id = java.util.UUID.randomUUID().toString(),
                    characterId = characterId,
                    type = type,
                    content = content,
                    importance = importance,
                    timestamp = msg.timestamp.takeIf { it > 0 } ?: now
                )
            )
        }
        return out
    }

    /**
     * 取与当前输入最相关的记忆。
     *
     * **修复的 bug**：上一版是
     * ```
     * query.any { it in memory.content }
     * ```
     * `query` 是 String，`any` 遍历的是**单个字符** ——
     * 只要记忆里出现过查询语句中的任意一个字就算"相关"。
     * 中文常用字就那几百个，等于几乎所有记忆都命中，排序完全失效。
     *
     * 现在改成算**有效字符重合率**（去重后的字符集合交并比），
     * 并对命中重要度做加权。
     */
    fun getRelevantMemories(
        allMemories: List<Memory>,
        query: String,
        limit: Int = 6
    ): List<Memory> {
        if (allMemories.isEmpty()) return emptyList()

        val qSet = meaningfulChars(query)
        if (qSet.isEmpty()) {
            // 查询里没有有效字符（比如用户只发了个表情），退化成按重要度+时间取
            return allMemories
                .sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.timestamp })
                .take(limit)
        }

        return allMemories
            .map { it to overlapRatio(qSet, meaningfulChars(it.content)) }
            .filter { it.second >= MIN_OVERLAP }
            .sortedWith(
                compareByDescending<Pair<Memory, Float>> { it.second }
                    .thenByDescending { it.first.importance }
                    .thenByDescending { it.first.timestamp }
            )
            .take(limit)
            .map { it.first }
    }

    /**
     * 合并新旧记忆并按容量淘汰。
     *
     * 淘汰顺序：先按重要度，再按时间。低重要度的旧条目先走。
     */
    fun mergeMemories(existing: List<Memory>, new: List<Memory>): List<Memory> {
        val combined = existing.toMutableList()
        val known = existing.map { it.content }.toMutableSet()
        new.forEach { m ->
            if (known.add(m.content)) combined.add(m)
        }
        return combined
            .sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.timestamp })
            .take(MAX_MEMORIES)
    }

    // ==================== 内部工具 ====================

    /**
     * 压缩成适合当记忆的一句话。
     * 取第一个句子，并截断到 [MAX_CONTENT_LEN]。
     */
    private fun condense(text: String): String {
        val firstSentence = text.split(Regex("[。！？!?\\n]"))
            .firstOrNull { it.trim().length >= 4 }
            ?.trim()
            ?: text.trim()
        return if (firstSentence.length > MAX_CONTENT_LEN) {
            firstSentence.take(MAX_CONTENT_LEN) + "…"
        } else {
            firstSentence
        }
    }

    /**
     * 提取"有效字符"集合。
     * 只保留汉字和字母数字，丢掉标点和语气词，避免"的了吗呢"这类高频字造成假命中。
     */
    private fun meaningfulChars(text: String): Set<Char> =
        text.asSequence()
            .filter { c ->
                val code = c.code
                (code in 0x4E00..0x9FFF) || c.isLetterOrDigit()
            }
            .filter { it !in STOP_CHARS }
            .toSet()

    /** 重合率 = 交集 / 查询字符数（不对称：以"查询被覆盖了多少"为准） */
    private fun overlapRatio(query: Set<Char>, target: Set<Char>): Float {
        if (query.isEmpty()) return 0f
        val hit = query.count { it in target }
        return hit.toFloat() / query.size
    }

    private val STOP_CHARS = "的了吗呢吧啊呀哦嗯是有在个我你他她它们和与就都也很不这那".toSet()


}

/** 单例实例 */

