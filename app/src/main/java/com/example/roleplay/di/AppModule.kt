package com.example.roleplay.di

import android.content.Context
import androidx.room.Room
import com.example.roleplay.data.database.RoleplayDatabase
import com.example.roleplay.data.dao.*
import com.example.roleplay.data.repository.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoleplayDatabase {
        return Room.databaseBuilder(
            context,
            RoleplayDatabase::class.java,
            "roleplay.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }
    
    @Provides
    @Singleton
    fun provideCharacterDao(database: RoleplayDatabase): CharacterDao {
        return database.characterDao()
    }
    
    @Provides
    @Singleton
    fun provideConversationDao(database: RoleplayDatabase): ConversationDao {
        return database.conversationDao()
    }
    
    @Provides
    @Singleton
    fun provideMessageDao(database: RoleplayDatabase): MessageDao {
        return database.messageDao()
    }
    
    @Provides
    @Singleton
    fun provideMemoryDao(database: RoleplayDatabase): MemoryDao {
        return database.memoryDao()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    
    @Provides
    @Singleton
    fun provideCharacterRepository(dao: CharacterDao): CharacterRepository {
        return CharacterRepository(dao)
    }
    
    @Provides
    @Singleton
    fun provideConversationRepository(dao: ConversationDao): ConversationRepository {
        return ConversationRepository(dao)
    }
    
    @Provides
    @Singleton
    fun provideMessageRepository(dao: MessageDao): MessageRepository {
        return MessageRepository(dao)
    }
    
    @Provides
    @Singleton
    fun provideMemoryRepository(dao: MemoryDao): MemoryRepository {
        return MemoryRepository(dao)
    }
}

