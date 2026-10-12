package com.example.roleplay

import com.example.roleplay.ai.gguf.GGUFAIService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 预算估算（纯函数，不联网）。
 * 规则：CJK 一字符 ≈ 1 token；其余按 4 字符/token 向上取整。
 */
class EstimateTokensTest {

    @Test
    fun emptyTextIsZero() {
        assertEquals(0, GGUFAIService.estimateTokens(""))
    }

    @Test
    fun cjkCharsCountOnePerChar() {
        assertEquals(5, GGUFAIService.estimateTokens("你好世界啊"))
    }

    @Test
    fun pureLatinApproximatesFourCharsPerToken() {
        // 16 个英文字符 → ceil(16/4) = 4
        assertEquals(4, GGUFAIService.estimateTokens("abcdefghijklmnop"))
        // 1 个字符也要占 1 个 token
        assertEquals(1, GGUFAIService.estimateTokens("a"))
    }

    @Test
    fun mixedTextSumsBothParts() {
        // 3 个 CJK + 12 个字母 = 3 + ceil(12/4)=3 → 6
        val text = "你好" + "abcdefghijklmnopqrstuvwxyz"
        assertTrue(GGUFAIService.estimateTokens(text) >= 6)
    }

    @Test
    fun budgetComputationIsMonotonic() {
        val small = GGUFAIService.estimateTokens("短")
        val long = GGUFAIService.estimateTokens("这是一段相当长的中文文本用于验证估算值随内容增长".repeat(3))
        assertTrue(long > small)
    }
}
