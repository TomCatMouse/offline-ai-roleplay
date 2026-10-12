package com.example.roleplay

import com.example.roleplay.data.model.ChatMessage
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.Rarity
import com.example.roleplay.data.model.fromValue
import com.example.roleplay.data.model.getTagList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色数据模型的纯函数行为。
 * - getTagList：逗号分隔字符串 → 标签列表
 * - Rarity.fromValue：数值 ↔ 枚举
 * - ChatMessage.getFullText：动作 + 正文的拼接（喂给记忆引擎/模型的完整文本）
 */
class CharacterModelTest {

    @Test
    fun tagListParsesCommaSeparated() {
        val c = Character("id", "n", personality = "", speakingStyle = "", backstory = "", greeting = "", tags = "温柔, 猫耳 ,,,毒舌")
        assertEquals(listOf("温柔", "猫耳", "毒舌"), c.getTagList())
    }

    @Test
    fun tagListEmptyWhenBlank() {
        val c = Character("id", "n", personality = "", speakingStyle = "", backstory = "", greeting = "", tags = "   ,  ,")
        assertTrue(c.getTagList().isEmpty())
        val c2 = Character("id", "n", personality = "", speakingStyle = "", backstory = "", greeting = "", tags = "")
        assertTrue(c2.getTagList().isEmpty())
    }

    @Test
    fun rarityFromValueCoversAll() {
        // fromValue 是顶层扩展函数，需要显式实例接收者（函数本身与接收者无关）
        val r = Rarity.COMMON
        assertEquals(Rarity.COMMON, r.fromValue(0))
        assertEquals(Rarity.RARE, r.fromValue(1))
        assertEquals(Rarity.EPIC, r.fromValue(2))
        assertEquals(Rarity.LEGENDARY, r.fromValue(3))
        assertEquals(Rarity.MYTHIC, r.fromValue(4))
    }

    @Test
    fun rarityFromValueFallsBackToCommon() {
        val r = Rarity.COMMON
        assertEquals(Rarity.COMMON, r.fromValue(-1))
        assertEquals(Rarity.COMMON, r.fromValue(99))
    }

    @Test
    fun fullTextIncludesActionWhenPresent() {
        val m = ChatMessage("id", "c", "assistant", "你终于来了", action = "（轻轻拉住你的衣袖）")
        assertEquals("（轻轻拉住你的衣袖） 你终于来了", m.getFullText())
    }

    @Test
    fun fullTextIsPlainContentWithoutAction() {
        val m = ChatMessage("id", "c", "user", "我只是路过", action = null)
        assertEquals("我只是路过", m.getFullText())
        assertEquals("user", m.role)
        assertEquals(false, m.hasAction)
    }
}
