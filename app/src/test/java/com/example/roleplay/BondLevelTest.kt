package com.example.roleplay

import com.example.roleplay.data.model.BondLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 羁绊等级纯函数测试。
 * 规则：每 [BondLevel.MESSAGES_PER_LEVEL] 条消息升一级，等级 = 1 + count/12（封顶 100）。
 */
class BondLevelTest {

    @Test
    fun noMessages_isLevel1() {
        assertEquals(1, BondLevel.levelFor(0))
        assertEquals(1, BondLevel.levelFor(-3)) // 负数防御
    }

    @Test
    fun justBelowOneLevelThreshold_staysLevel1() {
        assertEquals(1, BondLevel.levelFor(BondLevel.MESSAGES_PER_LEVEL - 1))
    }

    @Test
    fun thresholdCrossing_increasesLevel() {
        assertEquals(2, BondLevel.levelFor(BondLevel.MESSAGES_PER_LEVEL))
        assertEquals(3, BondLevel.levelFor(BondLevel.MESSAGES_PER_LEVEL * 2 + 5))
    }

    @Test
    fun levelIsCappedAtMax() {
        assertEquals(BondLevel.MAX_LEVEL, BondLevel.levelFor(10_000_000))
    }

    @Test
    fun maxLevelIs100_andNeeds1188Messages() {
        assertEquals(100, BondLevel.MAX_LEVEL)
        assertEquals(1188, BondLevel.MESSAGES_TO_MAX)
        // 1187 条还差一级，1188 条刚好满级
        assertEquals(BondLevel.MAX_LEVEL - 1, BondLevel.levelFor(BondLevel.MESSAGES_TO_MAX - 1))
        assertEquals(BondLevel.MAX_LEVEL, BondLevel.levelFor(BondLevel.MESSAGES_TO_MAX))
        // 满级后经验条是满格，不是 1188 % 12 = 0 的空条
        assertEquals(1f, BondLevel.progress(BondLevel.MESSAGES_TO_MAX), 0f)
        assertEquals(1f, BondLevel.progress(BondLevel.MESSAGES_TO_MAX + 5), 0f)
    }

    @Test
    fun progress_isFractionWithinCurrentLevel() {
        assertEquals(0f, BondLevel.progress(0), 0f)
        assertEquals(
            6f / BondLevel.MESSAGES_PER_LEVEL,
            BondLevel.progress(6),
            1e-5f
        )
        // 恰好整级：进度 0，差值 = 整级
        assertEquals(0f, BondLevel.progress(BondLevel.MESSAGES_PER_LEVEL), 0f)
        assertEquals(
            BondLevel.MESSAGES_PER_LEVEL,
            BondLevel.messagesToNext(BondLevel.MESSAGES_PER_LEVEL)
        )
    }

    @Test
    fun messagesToNext_countsUpToThreshold() {
        assertEquals(BondLevel.MESSAGES_PER_LEVEL, BondLevel.messagesToNext(0))
        assertEquals(BondLevel.MESSAGES_PER_LEVEL - 5, BondLevel.messagesToNext(5))
    }

    @Test
    fun titles_areMonotonic() {
        assertTrue(BondLevel.titleFor(99).isNotEmpty())
        assertEquals("初识", BondLevel.titleFor(1))
        assertEquals("灵魂羁绊", BondLevel.titleFor(BondLevel.MAX_LEVEL))
        // 低等级称号一定比高等级"更浅"（顺序固定）
        assertTrue(BondLevel.titleFor(2).let { it != BondLevel.titleFor(30) })
    }
}
