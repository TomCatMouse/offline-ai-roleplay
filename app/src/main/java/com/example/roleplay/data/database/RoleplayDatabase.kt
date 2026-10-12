package com.example.roleplay.data.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.roleplay.data.dao.*
import com.example.roleplay.data.model.*

/**
 * 角色扮演应用主数据库
 */
@Database(
    entities = [
        Character::class,
        Conversation::class,
        ChatMessage::class,
        Memory::class
    ],
    version = 2,
    exportSchema = false
)
abstract class RoleplayDatabase : RoomDatabase() {
    
    abstract fun characterDao(): CharacterDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    
    companion object {
        private const val DATABASE_NAME = "roleplay.db"
        
        /**
         * M8：加 conversations.title 列（剧情线自定义名称）。
         * 显式 migration 保数据：老用户的角色/对话/记忆全保留，只是多一列空值。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN title TEXT")
            }
        }
        
        @Volatile
        private var INSTANCE: RoleplayDatabase? = null
        
        fun getInstance(context: Context): RoleplayDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RoleplayDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
