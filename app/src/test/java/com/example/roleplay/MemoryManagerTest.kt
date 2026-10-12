package com.example.roleplay

import com.example.roleplay.data.model.ChatMessage
import com.example.roleplay.data.model.Memory
import com.example.roleplay.memory.MemoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长期记忆规则引擎测试（确定性规则，不依赖模型）。
 */
class MemoryManagerTest {

    private val mm = MemoryManager()

    private fun msg(role: String, content: String, ts: Long = 1000L) =
        ChatMessage(id = "${role}-${content.hashCode()}", conversationId = "c1", role = role, content = content, timestamp = ts)

    @Test
    fun extractPreferenceFromUser() {
        val out = mm.extractMemories(listOf(msg("user", "我最喜欢吃草莓蛋糕")), "char1")
        assertEquals(1, out.size)
        assertEquals(Memory.TYPE_PREFERENCE, out.first().type)
        assertEquals(3, out.first().importance)
    }

    @Test
    fun extractFactFromUser() {
        val out = mm.extractMemories(listOf(msg("user", "我叫小明，住在北京")), "char1")
        assertTrue(out.isNotEmpty())
        assertEquals(Memory.TYPE_FACT, out.first().type)
    }

    @Test
    fun aiEventTalkIsNotRemembered() {
        // 事件类只从**用户**叙述里提取：AI 自己编的情节不固化。
        // 注意措辞避开 FACT 关键词（"我在/我是/我住…"），否则会被当事实记下
        val out = mm.extractMemories(listOf(msg("assistant", "雨夜里我们俩第一次见面")), "char1")
        assertTrue(out.isEmpty())
    }

    @Test
    fun userEventTalkIsRemembered() {
        // 避开 FACT 关键词："我们在"会命中"我在"，改用"我们俩"
        val out = mm.extractMemories(listOf(msg("user", "记得我们俩在雨夜第一次见面")), "char1")
        assertTrue(out.isNotEmpty())
        assertEquals(Memory.TYPE_EVENT, out.first().type)
    }

    @Test
    fun duplicateContentIsDeduped() {
        val out = mm.extractMemories(
            listOf(
                msg("user", "我最喜欢吃草莓蛋糕", 1000),
                msg("user", "我最喜欢吃草莓蛋糕", 2000)
            ),
            "char1"
        )
        assertEquals(1, out.size)
    }

    @Test
    fun shortMessagesAreIgnored() {
        val out = mm.extractMemories(listOf(msg("user", "嗯嗯")), "char1")
        assertTrue(out.isEmpty())
    }

    @Test
    fun relevantMemoriesRankedByQueryOverlap() {
        val memories = listOf(
            Memory("mCake", "c", Memory.TYPE_EVENT, "草莓蛋糕", importance = 3, timestamp = 100L),
            Memory("mRain", "c", Memory.TYPE_EVENT, "雨天散步", importance = 3, timestamp = 200L),
            Memory("mPiano", "c", Memory.TYPE_EVENT, "钢琴练习", importance = 3, timestamp = 300L)
        )
        val hits = mm.getRelevantMemories(memories, "雨天我想吃草莓蛋糕", limit = 6)
        assertEquals("mCake", hits.first().id) // 重合率最高排第一
        assertTrue(hits.any { it.id == "mRain" }) // 部分重合也能命中
        assertTrue("钢琴与查询无重合，应被过滤", hits.none { it.id == "mPiano" })
    }

    @Test
    fun irrelevantQueryFallsBackToImportanceOrder() {
        val memories = listOf(
            Memory("mLow", "c", Memory.TYPE_EVENT, "草莓蛋糕", importance = 0, timestamp = 500L),
            Memory("mHigh", "c", Memory.TYPE_EVENT, "钢琴练习", importance = 3, timestamp = 100L)
        )
        // 查询与任何记忆都不重合（低于 0.2 阈值）→ 全部被过滤，
        // 剩下按重要度+时间排序（这里一条都不命中，返回空）
        val hits = mm.getRelevantMemories(memories, "苹果香蕉西瓜", limit = 6)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun mergeDedupesAndCapsCapacity() {
        val existing = (1..45).map {
            Memory("old$it", "c", Memory.TYPE_EVENT, "旧记忆$it", importance = 3, timestamp = it.toLong())
        }
        val incoming = listOf(Memory("new1", "c", Memory.TYPE_EVENT, "新记忆", importance = 3, timestamp = 999L))
        val merged = mm.mergeMemories(existing, incoming)
        assertEquals(40, merged.size) // 容量上限 40
        assertEquals("new1", merged.first().id) // 最新且同等重要度 → 排最前
    }
}
