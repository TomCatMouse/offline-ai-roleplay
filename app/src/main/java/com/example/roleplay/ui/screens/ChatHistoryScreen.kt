package com.example.roleplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.data.model.Character
import com.example.roleplay.ui.components.AppConfirmDialog
import com.example.roleplay.ui.components.CharacterAvatar
import com.example.roleplay.ui.components.TopHintHost
import com.example.roleplay.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话历史。
 *
 * 改造前：`ChatHistoryScreen(conversations = emptyList())` 是**硬编码空列表**，
 * 所以这个页面永远显示"还没有对话记录"，永远打不开任何一段对话。
 * 现在数据来自 ChatViewModel.allConversations（Room Flow 驱动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    conversations: List<ChatViewModel.ConversationSummary>,
    characters: List<Character>,
    onSelectChat: (ChatViewModel.ConversationSummary) -> Unit,
    onDeleteChat: (ChatViewModel.ConversationSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    // 角色查表：会话只存 characterId，头像/名字要现查
    val characterById = remember(characters) { characters.associateBy { it.id } }

    var pendingDelete by remember { mutableStateOf<ChatViewModel.ConversationSummary?>(null) }
    // G5：复制成功的小提示走和对话页**完全一样**的顶部药丸（TopHintHost），
    // 不再走底部 Snackbar / 系统 Toast
    var topHint by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对话历史", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF16161E))
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF12121A))
        ) {
            if (conversations.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "💬", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("还没有对话记录", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                    Text("选择一个角色开始对话吧！", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversations, key = { it.id }) { summary ->
                        val character = characterById[summary.characterId]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChat(summary) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (character != null) {
                                    CharacterAvatar(
                                        character = character,
                                        modifier = Modifier.size(48.dp)
                                    )
                                } else {
                                    // 角色被删掉但会话还在：给个占位，不要崩
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(
                                                Color(0xFF2A2A3E),
                                                RoundedCornerShape(24.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) { Text("❓", fontSize = 20.sp) }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = character?.name ?: "已删除的角色",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = summary.title,
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = formatTime(summary.updatedAt),
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.35f)
                                    )
                                }

                                // 复制这条剧情线（角色名 + 标题），需求 #6
                                // G5：成功提示改走顶部药丸（TopHintHost，与对话内复制完全一致）
                                IconButton(
                                    onClick = {
                                        val charName = character?.name ?: "已删除的角色"
                                        clipboard.setText(AnnotatedString("$charName · ${summary.title}"))
                                        topHint = "已复制到剪贴板"
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        "复制",
                                        tint = Color.White.copy(alpha = 0.3f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { pendingDelete = summary },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        "删除",
                                        tint = Color.White.copy(alpha = 0.3f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.3f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            // G5：复制成功提示走顶部药丸（TopHintHost，与对话内复制同一套样式）
            TopHintHost(
                hint = topHint,
                onDismissed = { topHint = null },
                Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
            )
        }
    }

    pendingDelete?.let { target ->
        AppConfirmDialog(
            title = "删除这段对话？",
            text = "「${target.title}」的全部消息会被永久删除，无法恢复。",
            confirmText = "删除",
            cancelText = "取消",
            confirmColor = Color(0xFFE53935),
            onConfirm = {
                onDeleteChat(target)
                // M3：删完的提示和复制弹窗同一套样式——顶部药丸（TopHintHost）
                topHint = "已删除这段对话"
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** 时间戳 → "刚刚 / 12:30 / 3天前 / 11-05" */
private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}
