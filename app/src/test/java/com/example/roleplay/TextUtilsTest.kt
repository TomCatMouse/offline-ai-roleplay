package com.example.roleplay

import com.example.roleplay.util.truncateMiddle
import com.example.roleplay.util.unifyThirdPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 #14：模型名中间截断，"Some-Model-7B-...Q4_K_M.gguf" 风格。 */
class TextUtilsTest {

    @Test
    fun shortStringUnchanged() {
        assertEquals("Qwen3.5-4B", truncateMiddle("Qwen3.5-4B", 30))
        assertEquals("abc", truncateMiddle("abc", 3))
    }

    @Test
    fun longFileNameIsMiddleTruncated() {
        val name = "Qwen3.5-4B-Instruct-Q4_K_M.gguf"
        val out = truncateMiddle(name, 30)
        assertTrue("结果应包含省略号: $out", out.contains("..."))
        // 头部保留模型家族
        assertTrue(out.startsWith("Qwen"))
        // 尾部保留量化档与扩展名
        assertTrue(out.endsWith(".gguf"))
        // 总长不超限
        assertTrue("长度 ${out.length} 应 <= 30", out.length <= 30)
    }

    @Test
    fun tailAlwaysKeepsExtension() {
        val out = truncateMiddle("Qwen3.5-4B-Instruct-Q5_K_M.gguf", 30)
        assertTrue(out.endsWith("K_M.gguf"))
        assertFalse(out.endsWith("..."))
    }

    @Test
    fun degenerateMaxLenFallsBackToOriginal() {
        val s = "很长很长的模型名字文件"
        assertEquals(s, truncateMiddle(s, 3)) // maxLen < 7 → 原样返回
    }

    // ==================== 本轮需求 #2：背景故事里的 他/她 → TA ====================

    @Test
    fun thirdPersonPronounsBecomeTa() {
        assertEquals("TA把TA的剑递给TA", unifyThirdPerson("她把他的剑递给他"))
        assertEquals("TA说TA要走了", unifyThirdPerson("她说他要走了"))
    }

    @Test
    fun pluralPronounsBecomeTaMen() {
        assertEquals("TA们在雪地里相遇", unifyThirdPerson("他们在雪地里相遇"))
        assertEquals("TA们都笑了", unifyThirdPerson("她们都笑了"))
    }

    @Test
    fun nonPronounWordsAreKept() {
        // 「其他 / 他人」不是代词，不能被写成「其TA」
        assertEquals("其他人都走了", unifyThirdPerson("其他人都走了"))
        assertEquals("弹着吉他喝着维他命水", unifyThirdPerson("弹着吉他喝着维他命水"))
        assertEquals("他乡遇故知", unifyThirdPerson("他乡遇故知"))
    }

    @Test
    fun mixedSlashPronounBecomesTa() {
        assertEquals("TA站在门口", unifyThirdPerson("他/她站在门口"))
        assertEquals("TA站在门口", unifyThirdPerson("她或他站在门口"))
    }

    @Test
    fun alreadyTaIsUntouchedAndBlankIsSafe() {
        assertEquals("TA已经在这里了", unifyThirdPerson("TA已经在这里了"))
        assertEquals("", unifyThirdPerson(""))
        assertEquals("   ", unifyThirdPerson("   "))
    }
}
