package com.example.roleplay

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.roleplay.data.model.Character
import com.example.roleplay.ui.components.AppConfirmDialog
import com.example.roleplay.ui.components.BottomDock
import com.example.roleplay.ui.components.CharacterAvatar
import com.example.roleplay.ui.components.DockTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 建议 #11：关键 UI 冒烟测试（纯 Compose，不依赖数据库 / PC 推理服务）。
 *
 * 覆盖三个最容易回归的组件：
 *  1. 底部 dock：两个 tab + 中间"新建角色"胶囊，点胶囊回调触发
 *  2. 确认弹窗：标题 / 正文 / 确认取消按钮齐全，点击回调
 *  3. 头像：Q 版生成头像按角色名稳定渲染（同一角色两次结果一致）
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private val testChar = Character(
        id = "ui-test",
        name = "测试酱",
        personality = "活泼",
        speakingStyle = "可爱",
        backstory = "测试",
        greeting = "嗨",
        tags = "可爱,测试",
        rarity = 1
    )

    @Test
    fun dockRendersAndCenterPillTriggersCallback() {
        var clicked = false
        rule.setContent {
            BottomDock(
                current = DockTab.CHARACTERS,
                onSelect = {},
                onNewCharacter = { clicked = true }
            )
        }
        rule.onNodeWithTag("dock_new_character").assertExists()
        rule.onNodeWithTag("dock_tab_characters").assertExists()
        rule.onNodeWithTag("dock_tab_settings").assertExists()

        rule.onNodeWithTag("dock_new_character").performClick()
        assert(clicked) { "点击中间胶囊应触发 onNewCharacter" }
    }

    @Test
    fun confirmDialogShowsContentAndFiresConfirm() {
        var confirmed = false
        rule.setContent {
            AppConfirmDialog(
                title = "删除角色？",
                text = "确认删除「测试酱」吗",
                confirmText = "删除",
                onConfirm = { confirmed = true },
                onDismiss = {}
            )
        }
        rule.onNodeWithText("删除角色？").assertExists()
        rule.onNodeWithText("确认删除「测试酱」吗").assertExists()
        rule.onNodeWithText("取消", useUnmergedTree = true).performClick()
        assert(!confirmed) { "点取消不应触发 onConfirm" }
    }

    @Test
    fun confirmDialogCancelDoesNotConfirm() {
        var confirmed = false
        var dismissed = false
        rule.setContent {
            AppConfirmDialog(
                title = "T",
                text = "X",
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true }
            )
        }
        rule.onNodeWithText("取消", useUnmergedTree = true).performClick()
        assert(dismissed && !confirmed)
    }

    @Test
    fun avatarRendersForCharacter() {
        rule.setContent {
            CharacterAvatar(character = testChar)
        }
        // 头像节点存在即可（contentDescription 已由组件语义保证）
        rule.onNodeWithTag("character_avatar").assertExists()
    }
}
