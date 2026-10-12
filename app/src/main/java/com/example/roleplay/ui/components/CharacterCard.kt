package com.example.roleplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.data.model.BondLevel
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.Rarity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCard(
    character: Character,
    onClick: () -> Unit,
    onEditClick: (() -> Unit)? = null,
    onFavoriteClick: ((Boolean) -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    /** 反馈 #11：查看卡片详情（人物设定 / 背景 / 开场白…） */
    onDetailClick: (() -> Unit)? = null,
    messageCount: Int = 0,
    /** P8：只有"正在生成回复"的那个角色卡片显示思考中（按角色区分，不再全场亮） */
    isThinking: Boolean = false,
    modifier: Modifier = Modifier
) {
    val rarityColor = getRarityColor(character.rarity)
    val level = if (messageCount > 0) BondLevel.levelFor(messageCount) else character.level
    var isFavorite by remember { mutableStateOf(character.favorite) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        // G14：卡片整体调大（边距 14→16、头像 60→70），与设置页卡片的手感接近
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 头像带光晕
            Box(
                modifier = Modifier.size(70.dp),
                contentAlignment = Alignment.Center
            ) {
                // 外发光
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(rarityColor.copy(alpha = 0.25f))
                )
                CharacterAvatar(
                    character = character,
                    modifier = Modifier.size(60.dp),
                    showLevel = true,
                    level = level
                )
            }

            Column(
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // P8：只有该角色在思考时才显示（数据源是 MainActivity 里按 id 匹配的标志）
                if (isThinking) {
                    Text(
                        "🌀 思考中...",
                        fontSize = 11.sp,
                        color = Color(0xFFD8A5E8),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 需求 #1：名称 + 稀有度作为一组**整体居中**
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = character.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 稀有度徽章
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = rarityColor.copy(alpha = 0.15f),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = when (character.rarity) { 0 -> "普通"; 1 -> "稀有"; 2 -> "史诗"; 3 -> "传说"; 4 -> "神话"; else -> "普通" },
                            fontSize = 11.sp,
                            color = rarityColor,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 7.dp)
                        )
                    }
                }

                // 标签
                val tagList = character.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (tagList.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tagList.take(2).mapIndexed { i, tag ->
                            if (i > 0) Spacer(modifier = Modifier.width(4.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text(tag, fontSize = 11.sp, color = Color.White.copy(alpha = 0.65f)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF252540)),
                                modifier = Modifier.height(22.dp),
                                border = null
                            )
                        }
                    }
                }

                // 开场白预览
                Text(
                    text = character.greeting.take(28) + if (character.greeting.length > 28) "..." else "",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // 右侧按钮
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { isFavorite = !isFavorite; onFavoriteClick?.invoke(isFavorite) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFavorite) Color(0xFFE91E63) else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(19.dp)
                    )
                }
                // 反馈 #11：编辑旁边跟一个"详情"简笔符号
                if (onDetailClick != null) {
                    IconButton(onClick = { onDetailClick() }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Outlined.Info,
                            "查看详情",
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                if (onEditClick != null) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            "更多",
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    if (showMenu) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Default.Edit, "编辑", tint = Color(0xFF9C27B0), modifier = Modifier.size(16.dp)) },
                                onClick = { onEditClick(); showMenu = false }
                            )
                            // item4：单行（列表）展示时，菜单里**不再重复**「查看详情」——
                            // 卡片右侧已经有一个 ℹ️ 详情按钮，菜单里再来一条是冗余，
                            // 还会把三项菜单撑高。网格视图仍然走 CharacterGridItem 自己的菜单。
                            DropdownMenuItem(
                                text = { Text("删除") },
                                leadingIcon = { Icon(Icons.Default.Delete, "删除", tint = Color(0xFFF44336), modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    showMenu = false
                                    onDeleteClick?.invoke()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CharacterGridItem(
    character: Character,
    onClick: () -> Unit,
    onEditClick: (() -> Unit)? = null,
    onFavoriteClick: ((Boolean) -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    /** 反馈 #11：查看卡片详情 */
    onDetailClick: (() -> Unit)? = null,
    messageCount: Int = 0,
    /** P8：只有"正在生成回复"的角色卡片显示思考中 */
    isThinking: Boolean = false,
    modifier: Modifier = Modifier
) {
    val rarityColor = getRarityColor(character.rarity)
    // 等级以消息数为事实来源实时算，不依赖库里那个可能过期的 level 字段
    val level = if (messageCount > 0) BondLevel.levelFor(messageCount) else character.level
    val bondProgress = BondLevel.progress(messageCount)

    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
    ) {
        // G14：网格卡片整体调大（边距 12→14、头像 64→72、名字 13→15）
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 头像带光晕
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(rarityColor.copy(alpha = 0.2f))
                )
                CharacterAvatar(
                    character = character,
                    modifier = Modifier.size(62.dp),
                    // level>1 才画角标，但 Lv.1 现在也有含义（初识），
                    // 所以即使 Lv.1 也把羁绊文案显示在下面
                    showLevel = level > 1,
                    level = level
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = character.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // P8：思考中标记（只有本角色在生成时才出现）
            if (isThinking) {
                Text(
                    "🌀 思考中...",
                    fontSize = 10.sp,
                    color = Color(0xFFD8A5E8),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // 稀有度 + 羁绊等级（需求 #1：整体居中）
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = rarityColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = when (character.rarity) { 0 -> "普通"; 1 -> "稀有"; 2 -> "史诗"; 3 -> "传说"; 4 -> "神话"; else -> "普通" },
                        fontSize = 10.sp,
                        color = rarityColor,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Lv.$level",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF4CAF50)
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            // 羁绊进度条。
            // 手写两条 Box 而不是用 LinearProgressIndicator：后者在不同
            // Material3 版本里参数名变过（stop indicator 相关），手写零版本风险。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(bondProgress.coerceIn(0f, 1f))
                        .background(Color(0xFF4CAF50))
                )
            }

            // 操作行：收藏 + 编辑 + 详情 + 删除。
            // ⚠️ 收藏按钮以前只存在于列表样式 CharacterCard 里，
            // 而首页默认就是网格样式 —— 所以"收藏"功能在首页其实是点不到的。
            if (onFavoriteClick != null || onEditClick != null || onDetailClick != null || onDeleteClick != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onFavoriteClick != null) {
                        IconButton(
                            onClick = { onFavoriteClick(!character.favorite) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (character.favorite) Icons.Default.Favorite
                                else Icons.Default.FavoriteBorder,
                                contentDescription = if (character.favorite) "取消收藏" else "收藏",
                                tint = if (character.favorite) Color(0xFFE91E63)
                                else Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    if (onEditClick != null) {
                        IconButton(
                            onClick = { onEditClick() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                "编辑",
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    // 反馈 #11：编辑后面跟一个"查看详情"简笔符号
                    if (onDetailClick != null) {
                        IconButton(
                            onClick = { onDetailClick() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                "查看详情",
                                tint = Color(0xFF64B5F6).copy(alpha = 0.75f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    if (onDeleteClick != null) {
                        IconButton(
                            onClick = { onDeleteClick() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                "删除",
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


