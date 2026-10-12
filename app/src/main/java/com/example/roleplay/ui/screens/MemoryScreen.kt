package com.example.roleplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.Memory
import com.example.roleplay.ui.components.AppConfirmDialog
import com.example.roleplay.ui.components.AppTextDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长期记忆管理页（建议 #3）。
 *
 * 之前记忆只有"全局清空"一个入口，用户完全不知道模型记住了什么。
 * 现在按角色进入：可以查看每条记忆（事件/偏好/事实），改内容，删单条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    character: Character,
    memories: List<Memory>,
    onUpdate: (Memory, String) -> Unit,
    onDelete: (Memory) -> Unit,
    onBack: () -> Unit
) {
    var editing by remember { mutableStateOf<Memory?>(null) }
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${character.name} · 长期记忆",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF16161E))
            )
        }
    ) { paddingValues ->
        if (memories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color(0xFF0D0D1A)),
                contentAlignment = Alignment.Center
            ) {
                // G3：空状态优化 —— 原来那个"长方形中间一个 ×"占位换成柔和的图标容器 + 两层文案
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF7B1FA2).copy(alpha = 0.32f),
                                        Color(0xFFE91E63).copy(alpha = 0.14f)
                                    )
                                )
                            )
                            .border(1.dp, Color(0xFFCE93D8).copy(alpha = 0.22f), RoundedCornerShape(28.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        // 用 emoji 而不是 material 图标：项目 material-icons 版本较旧，
                        // 没有 automirrored 包里的 Psychology（脑）图标。
                        // 反馈：原来的 🧠 换个更好看的 —— 用 🗂️「记忆卡片盒」，
                        // 语义上更贴"攒下来的往事"，在深紫粉圆底上也比脑子清爽。
                        Text("🗂️", fontSize = 40.sp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "还没有关于「${character.name}」的长期记忆",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "聊得越多，TA 记得越多",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color(0xFF0D0D1A)),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(memories, key = { it.id }) { m ->
                    MemoryCard(
                        memory = m,
                        onEdit = { editing = m },
                        onDelete = { pendingDelete = m }
                    )
                }
            }
        }
    }

    editing?.let { m ->
        AppTextDialog(
            title = "编辑记忆",
            initial = m.content,
            onConfirm = { newContent ->
                if (newContent.isNotBlank()) onUpdate(m, newContent)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    pendingDelete?.let { m ->
        AppConfirmDialog(
            title = "删除这条记忆？",
            text = "「${m.content.take(40)}${if (m.content.length > 40) "…" else ""}」将被删除，角色会忘记这件事。",
            confirmText = "删除",
            confirmColor = Color(0xFFE53935),
            onConfirm = {
                onDelete(m)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val typeLabel = when (memory.type) {
        Memory.TYPE_EVENT -> "事件"
        Memory.TYPE_PREFERENCE -> "偏好"
        Memory.TYPE_FACT -> "事实"
        else -> memory.type
    }
    val typeColor = when (memory.type) {
        Memory.TYPE_EVENT -> Color(0xFF2196F3)
        Memory.TYPE_PREFERENCE -> Color(0xFFE91E63)
        Memory.TYPE_FACT -> Color(0xFF4CAF50)
        else -> Color(0xFF9E9E9E)
    }
    val dateText = remember(memory.timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(memory.timestamp))
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 类型徽章
                Surface(shape = RoundedCornerShape(6.dp), color = typeColor.copy(alpha = 0.18f)) {
                    Text(
                        typeLabel,
                        fontSize = 10.sp,
                        color = typeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(dateText, fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑记忆",
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除记忆",
                        tint = Color(0xFFF44336).copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                memory.content,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                lineHeight = 19.sp
            )
        }
    }
}
