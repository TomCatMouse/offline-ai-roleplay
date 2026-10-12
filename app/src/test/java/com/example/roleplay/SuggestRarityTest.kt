package com.example.roleplay

import com.example.roleplay.data.model.Rarity
import com.example.roleplay.data.model.suggestRarity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 角色卡完整度 → 稀有度建议。
 * 五维各 1 分：personality≥10 字 / style≥8 字 / backstory≥40 字 /
 * greeting≥15 字 / tags≥2 个。计分：5→传说 4→史诗 3→稀有 其余→普通。
 */
class SuggestRarityTest {

    // 明确长度的素材，避免手数错
    private val pOk = "字".repeat(10)   // 恰好 10 → 计分
    private val pShort = "字".repeat(9) // 9 → 不计分
    private val sOk = "字".repeat(8)    // 恰好 8 → 计分
    private val sShort = "字".repeat(7) // 不计分
    private val bOk = "字".repeat(40)   // 恰好 40 → 计分
    private val bShort = "字".repeat(39)
    private val gOk = "字".repeat(15)   // 恰好 15 → 计分
    private val gShort = "字".repeat(14)

    @Test
    fun emptyCard_isCommon() {
        assertEquals(Rarity.COMMON, suggestRarity("", "", "", "", emptyList()))
    }

    @Test
    fun threeDimensions_areRare() {
        assertEquals(Rarity.RARE, suggestRarity(pOk, sOk, bOk, "", emptyList()))
    }

    @Test
    fun fourDimensions_areEpic() {
        assertEquals(Rarity.EPIC, suggestRarity(pOk, sOk, bOk, gOk, emptyList()))
    }

    @Test
    fun fiveDimensions_areLegendary() {
        assertEquals(Rarity.LEGENDARY, suggestRarity(pOk, sOk, bOk, gOk, listOf("温柔", "猫耳")))
    }

    @Test
    fun singleTagDoesNotCount() {
        // 4 维 + 单标签 → 仍是 4 分（史诗），标签必须 ≥2 才第 5 分
        assertEquals(Rarity.EPIC, suggestRarity(pOk, sOk, bOk, gOk, listOf("只有一个标签")))
    }

    @Test
    fun boundaryLengths_scoreExactly() {
        // 恰好达标：每一维 +1
        assertEquals(Rarity.LEGENDARY, suggestRarity(pOk, sOk, bOk, gOk, listOf("a", "b")))
        // 每一维都差 1 字 → 0 分
        assertEquals(Rarity.COMMON, suggestRarity(pShort, sShort, bShort, gShort, emptyList()))
        // 只有一维达标 → 普通
        assertEquals(Rarity.COMMON, suggestRarity(pOk, "", "", "", emptyList()))
        // 两维达标 → 普通
        assertEquals(Rarity.COMMON, suggestRarity(pOk, sOk, "", "", emptyList()))
    }
}
