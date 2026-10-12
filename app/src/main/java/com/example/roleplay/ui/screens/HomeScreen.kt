package com.example.roleplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.data.model.Character
import com.example.roleplay.ui.components.CharacterCard
import com.example.roleplay.ui.components.CharacterGridItem
import com.example.roleplay.ui.components.ViewMode
import com.example.roleplay.ui.components.warmAvatarCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AppGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF7B1FA2), Color(0xFFE91E63))
)

/**
 * 搜索框高度。
 * 需求 #4（上一轮）：从默认 56dp 压到 32dp。
 * N3（本轮）：32dp 太扁，调高到 40dp——和 40dp 的视图切换按钮、
 * 放大后的统计卡同一节奏，依旧比默认 56dp 紧凑。
 */
private val SEARCH_HEIGHT = 46.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    characters: List<Character>,
    favoritesOnly: Boolean,
    onToggleFavoritesOnly: () -> Unit,
    onCharacterClick: (Character) -> Unit,
    onEditCharacter: (Character) -> Unit,
    onDeleteCharacter: (Character) -> Unit,
    /** 反馈 #11：查看卡片详情 */
    onDetailCharacter: (Character) -> Unit = {},
    onHistoryClick: () -> Unit,
    onFavoriteToggle: (Character) -> Unit,
    bondCounts: Map<String, Int> = emptyMap(),
    totalCharacterCount: Int = characters.size,
    conversationCount: Int = 0,
    favoriteCount: Int = 0,
    /** P8：正在生成回复的角色 id（null = 没有）；只有匹配的卡片亮「思考中」 */
    generatingCharacterId: String? = null,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredCharacters = remember(characters, searchQuery) {
        if (searchQuery.isBlank()) characters
        else characters.filter { char ->
            char.name.contains(searchQuery, ignoreCase = true) ||
                    char.tags.contains(searchQuery, ignoreCase = true) ||
                    char.personality.contains(searchQuery, ignoreCase = true)
        }
    }

    // 提前把头像光栅化好（放后台线程，不阻塞首帧）
    LaunchedEffect(characters) {
        withContext(Dispatchers.Default) { warmAvatarCache(characters) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "✨ AI 角色扮演",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E)),
                actions = {
                    // 「拍照/识别」入口已按需求移除。
                    // 原先它旁边那个 VisionScreen 是纯 mock：两个按钮都只是
                    // 生成一张纯色位图，还用了 runBlocking{delay} 阻塞主线程，
                    // 与其留个假功能，不如把入口先撤掉。
                    IconButton(onClick = onHistoryClick) {
                        Icon(
                            Icons.Default.History,
                            "历史",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        },
                // 注意："＋ 新建角色" FAB 不在这里 —— 挂在**外层** MainActivity 的 Scaffold 上。
        // 内层 Scaffold 的 floatingActionButton 槽实测不渲染（不占像素、不进 a11y 树），
        // 点右下角会直接穿透到卡片和底部 dock。
    ) { paddingValues ->
        Column(
            modifier = modifier.fillMaxSize().padding(paddingValues).background(Color(0xFF0D0D1A))
        ) {
            // 统计卡片（紧凑版）。
            // 两个历史坑都踩过：
            // ① 这里**不能**用 M3 的 Divider —— 它是横向控件（fillMaxWidth），
            //    塞进 Row 里会吃掉整行宽度，把"对话""收藏"两项挤出屏幕被遮住；
            //    竖分隔线改用 Box + 1dp 手画。
            // ② 三项 StatItem 用 SpaceEvenly + 自然宽度，窄屏/小字号下会溢出，
            //    现在每项 weight(1f) 三等分整行，任意屏宽都不会再被遮。
            // 本轮 item 3：搜索栏上下间距要一致 ——
            //   上方 = 统计卡 Box 下边距 4dp + 搜索行上边距 6dp = 10dp
            //   下方 = 搜索行下边距 6dp + 网格 top contentPadding 4dp = 10dp
            // （原来 2+4=6dp vs 4+14=18dp，上挤下松。）
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E32)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatItem(
                            icon = "🎭", label = "角色", value = totalCharacterCount,
                            modifier = Modifier.weight(1f)
                        )
                        StatDivider()
                        StatItem(
                            icon = "💬", label = "对话", value = conversationCount,
                            modifier = Modifier.weight(1f)
                        )
                        StatDivider()
                        StatItem(
                            icon = "❤️",
                            label = if (favoritesOnly) "收藏中" else "收藏",
                            value = favoriteCount,
                            highlighted = favoritesOnly,
                            onClick = onToggleFavoritesOnly,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 搜索栏（宽度自适应）+ 视图切换 + 收藏筛选。
            // 搜索框用 weight(1f) 吃掉剩余宽度，不再写死 210dp；
            // 视图按钮固定 40dp，两者在任意屏宽下都不留大空白。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(SEARCH_HEIGHT / 2),
                    color = Color(0xFF1E1E32),
                    modifier = Modifier.weight(1f).height(SEARCH_HEIGHT)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            "搜索",
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color(0xFF9C27B0)),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "搜索角色...",
                                            color = Color.White.copy(alpha = 0.35f),
                                            fontSize = 14.sp
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    "清除",
                                    tint = Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 视图切换。
                // 图标语义修正：显示「当前模式将要切成什么」还是「当前是什么」
                // 很容易搞反 —— 这里统一为**显示当前模式**（网格时是网格图标），
                // 和 CharacterListScreen 保持一致。
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF1E1E32)) {
                    IconButton(
                        onClick = {
                            viewMode = if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                        },
                        modifier = Modifier.size(SEARCH_HEIGHT)
                    ) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.GRID) Icons.Default.GridView
                            else Icons.Default.List,
                            contentDescription = if (viewMode == ViewMode.GRID) "当前网格视图，点击切换列表" else "当前列表视图，点击切换网格",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (favoritesOnly) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFE91E63).copy(alpha = 0.18f),
                        modifier = Modifier.clickable { onToggleFavoritesOnly() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                "只看收藏",
                                tint = Color(0xFFE91E63),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("只看收藏", fontSize = 11.sp, color = Color(0xFFE91E63))
                        }
                    }
                }
            }

            if (filteredCharacters.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = if (favoritesOnly) "💔" else "😿", fontSize = 56.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when {
                                favoritesOnly -> "还没有收藏的角色\n点亮卡片上的 ♡ 就能收藏啦~"
                                searchQuery.isNotEmpty() -> "没有找到匹配的角色"
                                else -> "还没有角色，创建一个吧~"
                            },
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 15.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                // ⚠️ 这里以前不管 viewMode 是什么都渲染网格 ——
                // 切换按钮只是把图标换了个样子，列表根本没变。
                // 现在两种模式各自走真正的分支。
                when (viewMode) {
                    ViewMode.GRID -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        // bottom 留足 40dp：底部 dock 会遮住内容末端，
                        // 14dp 会让最后一行卡片被裁掉大半（实测列表最后一行的"更多"按钮点不到）
                        contentPadding = PaddingValues(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredCharacters, key = { it.id }) { char ->
                            CharacterGridItem(
                                character = char,
                                onClick = { onCharacterClick(char) },
                                onEditClick = { onEditCharacter(char) },
                                onFavoriteClick = { onFavoriteToggle(char) },
                                onDeleteClick = { onDeleteCharacter(char) },
                                onDetailClick = { onDetailCharacter(char) },
                                messageCount = bondCounts[char.id] ?: 0,
                                isThinking = generatingCharacterId == char.id
                            )
                        }
                    }

                    ViewMode.LIST -> LazyColumn(
                        contentPadding = PaddingValues(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredCharacters, key = { it.id }) { char ->
                            CharacterCard(
                                character = char,
                                onClick = { onCharacterClick(char) },
                                onEditClick = { onEditCharacter(char) },
                                onFavoriteClick = { onFavoriteToggle(char) },
                                onDeleteClick = { onDeleteCharacter(char) },
                                onDetailClick = { onDetailCharacter(char) },
                                messageCount = bondCounts[char.id] ?: 0,
                                isThinking = generatingCharacterId == char.id
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: String,
    label: String,
    value: Int,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier
) {
    // M4：在 N2 基础上再大一档（图标 16→18 / 数字 15→16 / 标签 11→12sp，
    // 内边距 6→8dp），与角色卡、40dp 搜索框保持同一视觉节奏。
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Text(text = icon, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value.toString(),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) Color(0xFFE91E63) else Color.White,
            maxLines = 1
        )
        Text(
            text = label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (highlighted) Color(0xFFE91E63) else Color.White.copy(alpha = 0.45f)
        )
    }
}

/** 统计卡内部的竖分隔线（M3 Divider 是横向的，Row 里会挤爆布局） */
@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(36.dp)
            .background(Color.White.copy(alpha = 0.1f))
    )
}
