package com.example.roleplay.data.dao

import androidx.room.*
import com.example.roleplay.data.model.Character
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(character: Character)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(characters: List<Character>)
    
    @Query("SELECT * FROM characters ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Character>>
    
    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: String): Character?
    
    @Query("SELECT * FROM characters WHERE favorite = 1 ORDER BY level DESC")
    fun getFavorites(): Flow<List<Character>>
    
    @Update
    suspend fun update(character: Character)
    
    @Delete
    suspend fun delete(character: Character)
    
    @Query("UPDATE characters SET favorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: String, favorite: Boolean)

    /** 羁绊等级由聊天消息数驱动，见 BondLevel */
    @Query("UPDATE characters SET level = :level WHERE id = :id")
    suspend fun updateLevel(id: String, level: Int)
}
