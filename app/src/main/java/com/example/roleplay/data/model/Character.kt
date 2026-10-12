package com.example.roleplay.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 角色数据模型
 * 对应数据库中存储的角色信息
 */
@Entity(tableName = "characters")
data class Character(
    @PrimaryKey val id: String,
    val name: String,
    val avatarAsset: String? = null,           // 头像资源路径
    val personality: String,                    // 性格描述
    val speakingStyle: String,                  // 说话风格
    val backstory: String,                      // 背景故事
    val greeting: String,                       // 开场白
    val tags: String,                           // 标签(逗号分隔)
    val rarity: Int = 0,                        // 稀有度 0-4
    val level: Int = 1,                         // 角色等级
    val favorite: Boolean = false,              // 是否收藏
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 角色稀有度枚举
 */
enum class Rarity(val value: Int, val label: String, val color: String) {
    COMMON(0, "普通", "#9E9E9E"),
    RARE(1, "稀有", "#2196F3"),
    // G12：史诗紫原来用的 #9C27B0（= App 主强调紫），作为**文字**时和主色混在一起
    // 不好辨认；调亮一档成 #CE93D8，和普通/稀有的灰蓝也能拉开层次
    EPIC(2, "史诗", "#CE93D8"),
    LEGENDARY(3, "传说", "#FF9800"),
    MYTHIC(4, "神话", "#F44336")
}

/**
 * 从逗号分隔的标签字符串解析为列表
 */
fun Character.getTagList(): List<String> =
    tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 根据稀有度值获取Rarity枚举
 */
fun Rarity.fromValue(value: Int): Rarity =
    Rarity.values().firstOrNull { it.value == value } ?: Rarity.COMMON
