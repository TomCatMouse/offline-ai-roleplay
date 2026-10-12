package com.example.roleplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 底部 dock 的页签 */
enum class DockTab(val label: String) {
    CHARACTERS("角色"),
    SETTINGS("设置")
}

// ============================================================================
//  本轮 item 6：dock 里三处文字的颜色统一
//
//  原来：选中页签的文字是浅紫 #CE93D8、没选中的是 45% 白、「新建角色」常驻浅紫，
//  同一条 dock 上出现了"两种色系"，看着散。
//  现在收成一条规矩：**文字只用一族白**，强调靠"亮度 + 紫色图标/指示块"表达 ——
//    · 强调态（选中的页签 / 新建角色这个动作）→ 亮白 0.92
//    · 普通态 → 暗白 0.5（图标同值，保证图标和文字一个色）
//  紫色只留在图标与选中指示块上，和 App 其他地方"文字白 + 紫色做强调"的口径一致。
// ============================================================================
private val DOCK_LABEL_ACTIVE = Color.White.copy(alpha = 0.92f)
private val DOCK_LABEL_IDLE = Color.White.copy(alpha = 0.5f)
private val DOCK_ACCENT = Color(0xFFCE93D8)

/**
 * 底部 dock 栏。
 *
 * 布局：「角色」……「新建角色」（正中间，风格与两侧页签一致）……「设置」。
 * 两侧页签 weight(1f) 对称，保证中间入口在任意屏宽下都严格居中。
 */

@Composable
fun BottomDock(
    current: DockTab,
    onSelect: (DockTab) -> Unit,
    onNewCharacter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(Color(0xFF16161E)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockTabButton(
            tab = DockTab.CHARACTERS,
            selected = current == DockTab.CHARACTERS,
            onClick = { onSelect(DockTab.CHARACTERS) },
            modifier = Modifier.weight(1f)
        )
        CenterNewCharacterPill(onClick = onNewCharacter)
        DockTabButton(
            tab = DockTab.SETTINGS,
            selected = current == DockTab.SETTINGS,
            onClick = { onSelect(DockTab.SETTINGS) },
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * dock 正中间的「新建角色」。
 * M5：图标 22→24dp、文字 11→13sp，与两侧页签同节奏。
 * 本 item 6：文字并入 dock 统一的白族（亮白），紫色只留给图标 ——
 * 既保住"这是个动作入口"的强调，又不再和页签文字串色。
 */
@Composable
private fun CenterNewCharacterPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("dock_new_character"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PersonAdd,
            contentDescription = "新建角色",
            modifier = Modifier.size(24.dp),
            tint = DOCK_ACCENT
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text("新建角色", fontSize = 13.sp, color = DOCK_LABEL_ACTIVE, fontWeight = FontWeight.Medium)
    }
}

/** 单个 dock 页签（手画版 NavigationBarItem：选中时图标背后浮出紫色指示块） */
@Composable
private fun DockTabButton(
    tab: DockTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable { if (!selected) onClick() }
            .testTag(if (tab == DockTab.CHARACTERS) "dock_tab_characters" else "dock_tab_settings"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 选中指示块（M3 风格：圆角胶囊衬在图标背后）
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(58.dp)
                        .height(30.dp)
                        .background(Color(0xFF9C27B0).copy(alpha = 0.28f), shape = RoundedCornerShape(15.dp))
                )
            }
            Icon(
                imageVector = when (tab) {
                    DockTab.CHARACTERS -> Icons.Default.People
                    DockTab.SETTINGS -> Icons.Default.Settings
                },
                contentDescription = tab.label,
                modifier = Modifier.size(24.dp),
                // item 6：图标与文字同一个色值（选中亮白 / 未选中暗白）
                tint = if (selected) Color.White else DOCK_LABEL_IDLE
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = tab.label,
            fontSize = 13.sp,
            color = if (selected) DOCK_LABEL_ACTIVE else DOCK_LABEL_IDLE
        )
    }
}
