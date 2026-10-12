package com.example.roleplay.data.dao

import androidx.room.*
import com.example.roleplay.data.model.Memory
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: Memory)
    
    @Query("SELECT * FROM memories WHERE characterId = :characterId ORDER BY timestamp DESC")
    fun getMemories(characterId: String): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories WHERE characterId = :characterId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemories(characterId: String, limit: Int = 20): List<Memory>
    
    @Query("SELECT * FROM memories WHERE characterId = :characterId AND type = :type ORDER BY timestamp DESC")
    fun getMemoriesByType(characterId: String, type: String): Flow<List<Memory>>
    
    @Update
    suspend fun update(memory: Memory)
    
    @Delete
    suspend fun delete(memory: Memory)
    
    @Query("DELETE FROM memories WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: String)
    
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空全部角色的长期记忆（设置页「清空所有长期记忆」用） */
    @Query("DELETE FROM memories")
    suspend fun deleteAll()
    
    /** 全部角色的长期记忆（备份导出用） */
    @Query("SELECT * FROM memories")
    suspend fun getAllMemories(): List<Memory>
     
    @Query("SELECT COUNT(*) FROM memories WHERE characterId = :characterId")
    fun getMemoryCount(characterId: String): Flow<Int>
}
