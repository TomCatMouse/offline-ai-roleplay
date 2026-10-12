package com.example.roleplay.data.repository

import com.example.roleplay.data.dao.*
import com.example.roleplay.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 角色数据仓库
 */
class CharacterRepository(private val characterDao: CharacterDao) {
    
    val allCharacters: Flow<List<Character>> = characterDao.getAll()
    val favorites: Flow<List<Character>> = characterDao.getFavorites()
    
    suspend fun getCharacter(id: String): Character? = characterDao.getById(id)
    
    suspend fun insertCharacter(character: Character) {
        characterDao.insert(character)
    }
    
    suspend fun insertCharacters(characters: List<Character>) {
        characterDao.insertAll(characters)
    }
    
    suspend fun updateFavorite(id: String, favorite: Boolean) {
        characterDao.updateFavorite(id, favorite)
    }

    /** 羁绊等级由聊天行为驱动，见 [BondLevel] */
    suspend fun updateLevel(id: String, level: Int) {
        characterDao.updateLevel(id, level)
    }

    /** 整体覆盖保存（编辑器用） */
    suspend fun updateCharacter(character: Character) {
        characterDao.insert(character)
    }

    suspend fun deleteCharacter(character: Character) {
        characterDao.delete(character)
    }
}

/**
 * 对话数据仓库
 */
class ConversationRepository(private val conversationDao: ConversationDao) {
    
    val allConversations: Flow<List<Conversation>> = conversationDao.getAll()

    /** item1：只含"有聊天记录"的会话，供对话历史列表 + 首页「对话」统计 */
    val allConversationsWithMessages: Flow<List<Conversation>> = conversationDao.getAllWithMessages()

    val conversationCount: Flow<Int> = conversationDao.getCount()
    
    suspend fun getConversation(id: String): Conversation? = conversationDao.getById(id)
    
    suspend fun getLatestByCharacter(characterId: String): Conversation? = 
        conversationDao.getLatestByCharacter(characterId)

    /** 某角色下的全部会话，供多剧情线切换 */
    fun getByCharacter(characterId: String): Flow<List<Conversation>> =
        conversationDao.getByCharacter(characterId)

    suspend fun countByCharacter(characterId: String): Int =
        conversationDao.countByCharacter(characterId)
    
    suspend fun insertConversation(conversation: Conversation): Long = 
        conversationDao.insert(conversation)
    
    suspend fun updateConversation(conversation: Conversation) {
        conversationDao.update(conversation)
    }
    
    /** M8：改剧情线自定义名称（传 null 恢复"按首条消息推导"） */
    suspend fun renameConversation(id: String, title: String?) {
        conversationDao.setTitle(id, title)
    }
    
    suspend fun deleteConversation(id: String) {
        conversationDao.deleteById(id)
    }

    /** 删除某角色下全部会话（删除角色级联用） */
    suspend fun deleteByCharacter(characterId: String) {
        conversationDao.deleteByCharacter(characterId)
    }

    /** 清空全部会话（设置页「清除所有对话」用） */
    suspend fun deleteAllConversations() {
        conversationDao.deleteAll()
    }
}

/**
 * 消息数据仓库
 */
class MessageRepository(private val messageDao: MessageDao) {
    
    fun getMessages(conversationId: String): Flow<List<ChatMessage>> = 
        messageDao.getMessages(conversationId)
    
    /**
     * 取最近 N 条消息，**按时间正序**返回（旧 → 新）。
     *
     * DAO 用 `ORDER BY timestamp DESC LIMIT n` 取"最新的 n 条"，取出后必须反转，
     * 否则拼进 Prompt 的历史是倒序的，模型会把对话理解反 → 答非所问。
     */
    suspend fun getRecentMessages(conversationId: String, limit: Int = 50): List<ChatMessage> =
        messageDao.getRecentMessages(conversationId, limit).asReversed()
    
    suspend fun getAllMessages(conversationId: String): List<ChatMessage> =
        messageDao.getAllMessages(conversationId)
    
    suspend fun insertMessage(message: ChatMessage): Long = 
        messageDao.insert(message)
    
    suspend fun insertMessages(messages: List<ChatMessage>) {
        messageDao.insertAll(messages)
    }
    
    suspend fun deleteMessages(conversationId: String) {
        messageDao.deleteByConversation(conversationId)
    }

    suspend fun deleteMessage(id: String) {
        messageDao.deleteById(id)
    }

    /** 删除某角色下全部消息（删除角色级联用） */
    suspend fun deleteByCharacter(characterId: String) {
        messageDao.deleteByCharacter(characterId)
    }

    /** 清空全部消息（设置页「清除所有对话」用，先清消息再清会话避免残留） */
    suspend fun deleteAllMessages() {
        messageDao.deleteAll()
    }

    suspend fun updateMessage(message: ChatMessage) {
        messageDao.update(message)
    }

    /** 会话标题：取第一条用户消息，截断到 18 字 */
    suspend fun getConversationTitle(conversationId: String): String? =
        messageDao.getFirstUserMessage(conversationId)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(18)

    suspend fun getLastAssistantMessage(conversationId: String): ChatMessage? =
        messageDao.getLastAssistantMessage(conversationId)

    /** 该角色跨全部会话的消息数，用于算羁绊等级 */
    suspend fun countByCharacter(characterId: String): Int =
        messageDao.countByCharacter(characterId)

    /** item6：全部消息总数（设置页「我的资料」→ 用户等级 / 经验条） */
    val totalMessageCount: Flow<Int> = messageDao.getTotalMessageCount()

    /** 所有角色的消息数（一次查询），首页羁绊进度条用 */
    fun getMessageCountsByCharacter(): Flow<List<CharacterMessageCount>> =
        messageDao.getMessageCountsByCharacter()
}

/**
 * 记忆数据仓库
 */
class MemoryRepository(private val memoryDao: MemoryDao) {
    
    fun getMemories(characterId: String): Flow<List<Memory>> = 
        memoryDao.getMemories(characterId)
    
    suspend fun getRecentMemories(characterId: String, limit: Int = 20): List<Memory> =
        memoryDao.getRecentMemories(characterId, limit)
    
    suspend fun insertMemory(memory: Memory) {
        memoryDao.insert(memory)
    }
    
    suspend fun deleteMemory(id: String) {
        memoryDao.deleteById(id)
    }

    /** 编辑记忆内容（"长期记忆"管理页用） */
    suspend fun updateMemory(memory: com.example.roleplay.data.model.Memory) {
        memoryDao.update(memory)
    }
    
    suspend fun clearMemories(characterId: String) {
        memoryDao.deleteByCharacter(characterId)
    }

    /** 全部角色的长期记忆（备份导出用） */
    suspend fun getAllMemories(): List<Memory> =
        memoryDao.getAllMemories()

    /** 清空全部角色的长期记忆（设置页「清空所有长期记忆」用） */
    suspend fun clearAllMemories() {
        memoryDao.deleteAll()
    }
}
