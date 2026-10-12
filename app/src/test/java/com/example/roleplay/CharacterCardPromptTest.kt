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
}
