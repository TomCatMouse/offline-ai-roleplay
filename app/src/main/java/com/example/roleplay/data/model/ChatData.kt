package com.example.roleplay.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对话会话实体
 *
 * M8：`title` = 用户给剧情线起的名字（null = 用首条消息推导）。
 * 老库升级时是空列，兼容无感（Migration 1→2 只加列，不动旧数据）。
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val characterId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val title: String? = null
)

/**
 * 消息实体 - 支持文本和动作描写分离
 */
@Entity(
    tableName = "messages",
    indices = [androidx.room.Index(value = ["conversationId"])]
)
data class ChatMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,           // "user" or "assistant"
    val content: String,        // 主要文本内容
    val action: String? = null, // 动作描写，如 "(轻轻拉住你的衣袖)"
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 判断是否为AI回复
     */
    val isAssistant get() = role == "assistant"
    
    /**
     * 判断是否包含动作描写
     */
    val hasAction get() = !action.isNullOrBlank()
    
    /**
     * 获取完整显示文本（动作+内容）
     */
    fun getFullText(): String = buildString {
        if (!action.isNullOrBlank()) {
            append(action)
            append(" ")
        }
        append(content)
    }
}

/**
 * 记忆实体 - 用于长期记忆存储
 */
@Entity(
    tableName = "memories",
    indices = [androidx.room.Index(value = ["characterId"])]
)
data class Memory(
    @PrimaryKey val id: String,
    val characterId: String,
    val type: String,           // "event" / "preference" / "fact"
    val content: String,
    val importance: Int = 0,    // 重要性 0-3
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_EVENT = "event"
        const val TYPE_PREFERENCE = "preference"
        const val TYPE_FACT = "fact"
    }
}

/**
 * 对话历史上下文（不含完整消息，仅用于Prompt构建）
 */
data class ConversationContext(
    val character: Character,
    val messages: List<ChatMessage>,
    val memories: List<Memory> = emptyList()
)
