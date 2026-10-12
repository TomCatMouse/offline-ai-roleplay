package com.example.roleplay.data.dao

import androidx.room.*
import com.example.roleplay.data.model.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long
    
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<Conversation>>

    /**
     * item1：只列「有聊天记录」的会话。
     *
     * 「新建剧情线」会立刻落一条空会话，但它一句话都没说过 —— 按需求这种不算一段对话：
     * 不进「对话历史」列表，也不计入首页的「对话」统计。
     * （聊天页自己的剧情线菜单仍用 [getByCharacter]，空剧情线必须留在里面，
     *   否则刚新建的剧情线在菜单里会"隐身"。）
     *
     * 用 EXISTS 子查询而不是 JOIN：一条会话有 N 条消息时 JOIN 会返回 N 行（还得 DISTINCT），
     * EXISTS 只判断"有没有消息"，语义更直白，也让 Room 同时观察 messages 表 ——
     * 发出第一条消息时会自动重新发射，历史列表立刻就出现这一条。
     */
    @Query(
        "SELECT * FROM conversations WHERE EXISTS " +
                "(SELECT 1 FROM messages WHERE messages.conversationId = conversations.id) " +
                "ORDER BY updatedAt DESC"
    )
    fun getAllWithMessages(): Flow<List<Conversation>>
    
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): Conversation?
    
    @Query("SELECT * FROM conversations WHERE characterId = :characterId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestByCharacter(characterId: String): Conversation?

    /** 某角色下的全部会话（多剧情线），按最近活动排序 */
    @Query("SELECT * FROM conversations WHERE characterId = :characterId ORDER BY updatedAt DESC")
    fun getByCharacter(characterId: String): Flow<List<Conversation>>

    /** 某角色下的会话数，用于判断是否需要自动开新会话 */
    @Query("SELECT COUNT(*) FROM conversations WHERE characterId = :characterId")
    suspend fun countByCharacter(characterId: String): Int
    
    @Update
    suspend fun update(conversation: Conversation)
    
    /** M8：给用户起的剧情线名字（null = 用首条消息推导） */
    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String?)
    
    @Delete
    suspend fun delete(conversation: Conversation)
    
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 删除某角色下全部会话（删除角色级联用） */
    @Query("DELETE FROM conversations WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: String)

    /** 清空全部会话（设置页「清除所有对话」用） */
    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /**
     * 会话总数。
     *
     * item1 起「一段对话」的定义是**有聊天记录的会话**：首页统计改用
     * [getAllWithMessages] 的结果计数（见 ChatViewModel.allConversationsWithMessages），
     * 这个原始计数只作调试/备份用，别拿它当"对话数"展示给用户。
     */
    @Query("SELECT COUNT(*) FROM conversations")
    fun getCount(): Flow<Int>
}
