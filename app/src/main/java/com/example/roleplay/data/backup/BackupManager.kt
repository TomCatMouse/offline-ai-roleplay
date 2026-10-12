package com.example.roleplay.data.backup

import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.ChatMessage
import com.example.roleplay.data.model.Conversation
import com.example.roleplay.data.model.Memory
import com.example.roleplay.data.repository.CharacterRepository
import com.example.roleplay.data.repository.ConversationRepository
import com.example.roleplay.data.repository.MessageRepository
import com.example.roleplay.data.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据备份 / 恢复（建议 #5）。
 *
 * 四张表（characters / conversations / messages / memories）整体打包成一个
 * JSON 文件。导出走系统文件选择器（SAF），导入时先全清再按外键顺序写入，
 * 保证不会出现"角色没了但消息还在"的孤儿数据。
 */
class BackupManager(
    private val characterRepo: CharacterRepository,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val memoryRepo: MemoryRepository
) {

    data class RestoreResult(
        val ok: Boolean,
        val message: String
    )

    /** 导出为 JSON 字符串 */
    suspend fun exportJson(): String {
        val characters = characterRepo.allCharacters.first()
        val conversations = conversationRepo.allConversations.first()
        val messages = conversations.flatMap { messageRepo.getAllMessages(it.id) }
        val memories = memoryRepo.getAllMemories()

        val root = JSONObject()
        root.put("app", "offline-ai-roleplay")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("characters", charactersToJson(characters))
        root.put("conversations", conversationsToJson(conversations))
        root.put("messages", messagesToJson(messages))
        root.put("memories", memoriesToJson(memories))
        return root.toString()
    }

    /**
     * 导入 JSON 备份。
     *
     * 策略是"全清 + 全写"：备份文件即数据源的唯一事实。
     * 顺序必须按外键依赖：先角色，再会话，再消息，最后记忆。
     */
    suspend fun importJson(json: String): RestoreResult {
        return try {
            val root = JSONObject(json)
            if (root.optString("app") != "offline-ai-roleplay") {
                return RestoreResult(false, "不是本应用的备份文件")
            }

            val characters = jsonToCharacters(root.optJSONArray("characters"))
            val conversations = jsonToConversations(root.optJSONArray("conversations"))
            val messages = jsonToMessages(root.optJSONArray("messages"))
            val memories = jsonToMemories(root.optJSONArray("memories"))

            // 1) 全清（按外键反序：消息 → 会话 → 记忆 → 角色）
            messageRepo.deleteAllMessages()
            conversationRepo.deleteAllConversations()
            memoryRepo.clearAllMemories()
            characterRepo.allCharacters.first().forEach { characterRepo.deleteCharacter(it) }

            // 2) 全写（正序）
            characterRepo.insertCharacters(characters)
            conversations.forEach { conversationRepo.insertConversation(it) }
            if (messages.isNotEmpty()) messageRepo.insertMessages(messages)
            memories.forEach { memoryRepo.insertMemory(it) }

            RestoreResult(
                ok = true,
                message = "已恢复 ${characters.size} 个角色 / ${conversations.size} 条会话 / ${messages.size} 条消息"
            )
        } catch (e: Exception) {
            RestoreResult(false, "导入失败：${e.message}")
        }
    }

    // ==================== JSON 编解码（org.json，Android 自带）====================

    private fun charactersToJson(list: List<Character>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("avatarAsset", it.avatarAsset ?: JSONObject.NULL)
                put("personality", it.personality)
                put("speakingStyle", it.speakingStyle)
                put("backstory", it.backstory)
                put("greeting", it.greeting)
                put("tags", it.tags)
                put("rarity", it.rarity)
                put("level", it.level)
                put("favorite", it.favorite)
                put("createdAt", it.createdAt)
            })
        }
    }

    private fun jsonToCharacters(arr: JSONArray?): List<Character> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Character(
                id = o.getString("id"),
                name = o.getString("name"),
                avatarAsset = o.optString("avatarAsset", null).takeIf { s -> !s.isNullOrBlank() && s != "null" },
                personality = o.optString("personality"),
                speakingStyle = o.optString("speakingStyle"),
                backstory = o.optString("backstory"),
                greeting = o.optString("greeting"),
                tags = o.optString("tags"),
                rarity = o.optInt("rarity", 0),
                level = o.optInt("level", 1),
                favorite = o.optBoolean("favorite", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private fun conversationsToJson(list: List<Conversation>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().apply {
                put("id", it.id)
                put("characterId", it.characterId)
                put("createdAt", it.createdAt)
            })
        }
    }

    private fun jsonToConversations(arr: JSONArray?): List<Conversation> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Conversation(
                id = o.getString("id"),
                characterId = o.getString("characterId"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private fun messagesToJson(list: List<ChatMessage>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().apply {
                put("id", it.id)
                put("conversationId", it.conversationId)
                put("role", it.role)
                put("content", it.content)
                put("action", it.action ?: JSONObject.NULL)
                put("timestamp", it.timestamp)
            })
        }
    }

    private fun jsonToMessages(arr: JSONArray?): List<ChatMessage> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ChatMessage(
                id = o.getString("id"),
                conversationId = o.getString("conversationId"),
                role = o.getString("role"),
                content = o.getString("content"),
                action = o.optString("action", null).takeIf { s -> !s.isNullOrBlank() && s != "null" },
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    private fun memoriesToJson(list: List<Memory>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().apply {
                put("id", it.id)
                put("characterId", it.characterId)
                put("type", it.type)
                put("content", it.content)
                put("importance", it.importance)
                put("timestamp", it.timestamp)
            })
        }
    }

    private fun jsonToMemories(arr: JSONArray?): List<Memory> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Memory(
                id = o.getString("id"),
                characterId = o.getString("characterId"),
                type = o.getString("type"),
                content = o.getString("content"),
                importance = o.optInt("importance", 0),
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}
