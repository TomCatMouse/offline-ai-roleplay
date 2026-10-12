package com.example.roleplay.data.dao

import androidx.room.*
import com.example.roleplay.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: ChatMessage): Long
    
    @Insert
    suspend fun insertAll(messages: List<ChatMessage>)
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<ChatMessage>>
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: String, limit: Int = 50): List<ChatMessage>
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getAllMessages(conversationId: String): List<ChatMessage>
    
    @Update
    suspend fun update(message: ChatMessage)
    
    @Delete
    suspend fun delete(message: ChatMessage)
    
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
    
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 删除某角色下全部消息（删除角色级联用，先删消息再删会话） */
    @Query("DELETE FROM messages WHERE conversationId IN (SELECT id FROM conversations WHERE characterId = :characterId)")
    suspend fun deleteByCharacter(characterId: String)

    /** 清空全部消息（设置页「清除所有对话」用） */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun getMessageCount(conversationId: String): Flow<Int>

    /**
     * item6：全部消息总数（跨角色、跨剧情线）。
     *
     * 设置页「我的资料」用它算**用户自己的等级与经验条** ——
     * 和角色羁绊同一套规则（[com.example.roleplay.data.model.BondLevel]：每 12 条一级），
     * 这样"等级"在 App 里只有一个含义：聊得越多越高。
     */
    @Query("SELECT COUNT(*) FROM messages")
    fun getTotalMessageCount(): Flow<Int>

    /**
     * 取该会话第一条用户消息，用来给会话起个可读的标题。
     * 不做 schema 变更（Conversation 表不加 title 字段），避免破坏性迁移清库。
     */
    @Query("SELECT content FROM messages WHERE conversationId = :conversationId AND role = 'user' ORDER BY timestamp ASC LIMIT 1")
    suspend fun getFirstUserMessage(conversationId: String): String?

    /** 最后一条 assistant 消息，重新生成时用 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'assistant' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastAssistantMessage(conversationId: String): ChatMessage?

    /**
     * 某角色**所有会话**的消息总数，用于计算羁绊等级。
     * 跨会话累计：换一条剧情线不代表关系清零。
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE conversationId IN " +
                "(SELECT id FROM conversations WHERE characterId = :characterId)"
    )
    suspend fun countByCharacter(characterId: String): Int

    /**
     * 一次性拿到所有角色的消息数，供首页画羁绊进度条。
     *
     * 为什么用一条聚合查询而不是每个角色查一次：
     * 首页要同时显示 N 张卡片，逐个查会变成 N 次 IO + N 次重组，
     * 角色一多就明显卡顿。
     */
    @Query(
        "SELECT c.characterId AS characterId, COUNT(m.id) AS messageCount " +
                "FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id " +
                "GROUP BY c.characterId"
    )
    fun getMessageCountsByCharacter(): Flow<List<CharacterMessageCount>>
}

/** 聚合查询的投影结果，Room 按字段名映射 */
data class CharacterMessageCount(
    val characterId: String,
    val messageCount: Int
)
