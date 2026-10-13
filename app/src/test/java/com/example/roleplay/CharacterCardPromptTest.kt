package com.example.roleplay

import com.example.roleplay.ai.gguf.buildCharacterCard
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.Memory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 #11：角色对用户的回答不设限。
 * 角色卡（system prompt）必须显式写入"不拒绝任何问题"铁律，
 * 且敏感词不应触发任何回避/拒绝类措辞。
 */
class CharacterCardPromptTest {

    private val character = Character(
        id = "test",
        name = "测试角色",
        personality = "温柔",
        speakingStyle = "轻声细语",
        backstory = "一个测试背景",
        greeting = "你好呀",
        tags = "温柔,测试"
    )

    @Test
    fun noRefusalRulePresentInBothModes() {
        val adult = buildCharacterCard(character, emptyList(), adultMode = true)
        val normal = buildCharacterCard(character, emptyList(), adultMode = false)
        // 铁律 11 两种模式都要有（用户要求：敏感问题也必须正面回答，与开关无关）
        assertTrue(adult.contains("不拒绝、不回避"))
        assertTrue(normal.contains("不拒绝、不回避"))
    }

    @Test
    fun noRefusalWordingAnyMode() {
        val adult = buildCharacterCard(character, emptyList(), adultMode = true)
        val normal = buildCharacterCard(character, emptyList(), adultMode = false)
        // 会诱导模型"拒绝/回避"的措辞不得出现在角色卡里
        val refusalInducing = listOf(
            "请拒绝", "需要拒绝", "应当拒绝", "拒绝用户",
            "安全准则", "道德说教", "回避敏感", "保持克制地拒绝"
        )
        for (card in listOf(adult, normal)) {
            for (forbidden in refusalInducing) {
                assertFalse("角色卡不应包含拒绝诱导措辞「$forbidden」:\n$card", card.contains(forbidden))
            }
        }
    }

    @Test
    fun questionIsAlwaysAnsweredInCharacter() {
        val card = buildCharacterCard(character, emptyList(), adultMode = true)
        assertTrue(card.contains("正面、完整地回答"))
        assertTrue(card.contains("答什么问什么"))
    }

    @Test
    fun memoriesAreIncluded() {
        val memories = listOf(
            Memory(id = "m1", characterId = "test", type = Memory.TYPE_EVENT, content = "我们在雨夜分开过")
        )
        val card = buildCharacterCard(character, memories, adultMode = true)
        assertTrue(card.contains("雨夜分开")) // 记忆内容必须进 prompt
        assertTrue(card.contains("【你记得的事】"))
    }

    @Test
    fun adultModeAddsContentScopeSection() {
        val adult = buildCharacterCard(character, emptyList(), adultMode = true)
        val normal = buildCharacterCard(character, emptyList(), adultMode = false)
        assertTrue(adult.contains("【内容尺度】"))
        assertFalse(normal.contains("【内容尺度】"))
    }

    @Test
    fun identityAndFormatRulesPreserved() {
        val card = buildCharacterCard(character, emptyList(), adultMode = true)
        assertTrue(card.contains("你现在就是「测试角色」"))
        assertTrue(card.contains("始终使用简体中文"))
        assertTrue(card.contains("禁止说自己是AI"))
    }

    // ==================== 用户昵称（【对方是谁】）====================
    // 缺陷：用户问"我叫什么"，角色反复只报自己的名字，来回拉扯答不到点上。
    // 根因：角色卡只写了【你是谁】，从没告诉模型"对面是谁"。

    @Test
    fun userNicknameIsToldToTheModelWhenSet() {
        val card = buildCharacterCard(character, emptyList(), adultMode = false, userName = "小明")
        assertTrue(card.contains("【对方是谁】"))
        assertTrue(card.contains("「小明」"))
    }

    @Test
    fun whenNicknameSetModelIsToldToAnswerWithIt() {
        val card = buildCharacterCard(character, emptyList(), adultMode = false, userName = "小明")
        // 必须明确写出"问'我叫什么'就答这个昵称"，否则小模型仍会拿角色自己的名字顶替
        assertTrue(card.contains("我叫什么"))
        assertTrue(card.contains("答案就是「小明」"))
    }

    @Test
    fun whenNicknameMissingModelMustAdmitNotKnowing() {
        val card = buildCharacterCard(character, emptyList(), adultMode = false, userName = "")
        assertTrue(card.contains("【对方是谁】"))
        assertTrue(card.contains("还没有告诉你名字"))
        assertTrue(card.contains("如实说 TA 还没跟你说过名字"))
        // 不许临时编一个名字顶上去
        assertTrue(card.contains("不要临时编一个"))
    }

    @Test
    fun missingNicknameStillDefersToMemories() {
        // 昵称没设置，但用户以前说过"我叫小明"并被记进长期记忆时，不能让角色一口咬定"不知道"
        val card = buildCharacterCard(character, emptyList(), adultMode = false, userName = "")
        assertTrue(card.contains("【你记得的事】") || card.contains("前面的对话里出现过 TA 的名字"))
        assertTrue(card.contains("就以那个为准"))
    }

    @Test
    fun blankNicknameIsTreatedAsNotSet() {
        val blank = buildCharacterCard(character, emptyList(), adultMode = false, userName = "   ")
        assertTrue(blank.contains("还没有告诉你名字"))
        assertFalse(blank.contains("昵称是「   」"))
    }

    @Test
    fun youAndMeAreExplicitlyDistinguished() {
        // 两种昵称状态下都必须有"你我之分"，这是本次修复的核心
        for (card in listOf(
            buildCharacterCard(character, emptyList(), adultMode = false, userName = "小明"),
            buildCharacterCard(character, emptyList(), adultMode = false, userName = "")
        )) {
            assertTrue(card.contains("分清\"你\"和\"我\""))
            assertTrue(card.contains("立刻纠正"))   // 专治"反复拉扯"：要纠正前几轮的错答
            assertTrue(card.contains("12. 分清"))   // 铁律里再强化一次
        }
    }

    @Test
    fun nicknameSectionPresentInBothModes() {
        assertTrue(buildCharacterCard(character, emptyList(), adultMode = true, userName = "小明").contains("【对方是谁】"))
        assertTrue(buildCharacterCard(character, emptyList(), adultMode = false, userName = "小明").contains("【对方是谁】"))
    }

    @Test
    fun defaultArgumentKeepsOldCallSitesWorking() {
        // 不传 userName 时按"还没设置"处理，而不是留空或报错
        val card = buildCharacterCard(character, emptyList(), adultMode = true)
        assertTrue(card.contains("还没有告诉你名字"))
    }
}
