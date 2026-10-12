package com.example.roleplay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.ai.tts.TtsPlayState
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.ChatMessage
import kotlinx.coroutines.delay

// ============================================================================
//  气泡布局常量
//
//  ⚠️ 这些常量**必须**被 MessageBubble 和 StreamingBubble 共用。
//
//  曾经的 bug：两处各写各的样式，StreamingBubble 用了 topStart=16/topEnd=4
//  （那是**用户**气泡的镜像形状），而助手气泡是 topStart=4/topEnd=16。
//  于是流式结束、气泡从一种换成另一种时，圆角会当场翻个面；
//  再加上 StreamingBubble 没有 280dp 宽度约束，文字会重新折行、气泡高度突变 ——
//  表现为"输出到最后的时候聊天样式会变化"，并且因为高度变了而滚不到底。
//
//  现在统一在这里定义，两处引用同一份，从结构上杜绝再次分叉。
// ============================================================================

private val BUBBLE_MAX_WIDTH = 300.dp
private val ASSISTANT_BUBBLE_SHAPE = RoundedCornerShape(
    topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp
)
private val USER_BUBBLE_SHAPE = RoundedCornerShape(
    topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp
)
private val ASSISTANT_BUBBLE_COLOR = Color(0xFF2A2A3E)
private val USER_BUBBLE_COLOR = Color(0xFF1976D2)

// 本轮 item 5：气泡 / 字号 / 气泡下方那排操作按钮统一调大。
// 三条铁律保持不变：① MessageBubble 与 StreamingBubble 引用同一组常量，
// ② 头像仍是 36dp（改尺寸会牵动"头像钉住不跳"的布局，收益不值），
// ③ 气泡外侧留白缩到 4dp，把省下的宽度让给正文，字大了也不会更挤。
private val BUBBLE_TEXT_SIZE = 16.sp
private val BUBBLE_PADDING = 14.dp
private val BUBBLE_SIDE_MARGIN = 4.dp
private val BUBBLE_NAME_SIZE = 11.sp        // 气泡上方：角色名 / 用户昵称
private val BUBBLE_META_SIZE = 11.sp        // 次要信息：动作描写 / 等待时长 / 生成进度
/**
 * 气泡下方那排操作图标（复制/编辑/朗读/重新生成/删除）的统一尺寸。
 *
 * 第 3 项「对话下方的符号按钮需保持同等大小」：这些按钮以前会随状态换字形
 * （朗读中换成 Pause/PlayArrow —— 两短横的 Pause 视觉上比其它图标小一圈），
 * 现在①三态都用同一个喇叭字形、②这个尺寸常量对外公开，
 * ChatScreen 里提示卡下方那排（NoticeActionIcon）也用同一个值，
 * 两排按钮、所有状态共用一套尺寸，不会再有"有的看着大有的看着小"。
 */
val ACTION_ICON_SIZE = 22.dp        // 气泡下方那排操作图标

/**
 * 第 2 项（本轮）：四个按钮声明尺寸一样（都是 22dp），但**字形墨迹**大小并不一样 ——
 * 真机逐个像素量过（3.0 px/dp，白色 55% 阈值 60 取墨迹包围盒）：
 *   复制 52×59px、朗读 49×47px、重新生成 43×43px、删除 39×49px。
 * 复制最大，所以其余三个看上去"小一圈"。这里以复制为基准给每个字形一个绘制补偿倍数：
 *   Modifier.scale 只放大绘制、**不改变布局与点击热区**，所以按钮间距、48dp 触摸区、
 *   行高全部保持原样，只是三个小字形画到和复制一样大。
 */
val GLYPH_SCALE_SPEAK = 1.20f       // 朗读 49×47px → 对齐复制的 59px
val GLYPH_SCALE_REGEN = 1.37f       // 重新生成 43×43px → 对齐复制的 59px
val GLYPH_SCALE_DELETE = 1.20f      // 删除 39×49px → 对齐复制的 59px
private val AVATAR_SIZE = 36.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    character: Character,
    /** 反馈 #1：操作按钮默认隐藏，长按这条气泡才显示（同一时刻只显示一条） */
    showActions: Boolean = false,
    onLongPress: () -> Unit = {},
    onDismissActions: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSpeak: () -> Unit = {},
    /** 第 21 项：这条消息当前的朗读状态（调用方比对"正在读哪条"后传入） */
    speakState: TtsPlayState = TtsPlayState.IDLE,
    onEditAndResend: (String) -> Unit = {},
    onToast: (String) -> Unit = {},
    /** 本轮需求 #5：用户自己的昵称 + 自定义头像（设置页可改） */
    userName: String = "",
    userAvatarPath: String? = null,
    modifier: Modifier = Modifier
) {
    val isUser = !message.isAssistant
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        // 头像对齐气泡**顶部**：多行长气泡往上吐字时头像钉在原地不动。
        // 若用 Bottom，气泡每长一行头像就被顶高一格，表现为\"一跳一跳\"。
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            CharacterAvatar(character = character, modifier = Modifier.size(AVATAR_SIZE))
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH)
        ) {
            if (!message.action.isNullOrBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Text(
                        text = message.action!!,
                        fontSize = BUBBLE_META_SIZE,
                        fontStyle = FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            // G10：双方都要有昵称 —— 角色气泡上方显示角色名，用户气泡上方显示用户昵称
            if (!isUser) {
                Text(
                    text = character.name,
                    fontSize = BUBBLE_NAME_SIZE,
                    color = Color(0xFFD8A5E8).copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)
                )
            } else if (userName.isNotBlank()) {
                Text(
                    text = userName,
                    fontSize = BUBBLE_NAME_SIZE,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(end = 12.dp, bottom = 3.dp)
                )
            }

            Surface(
                shape = if (isUser) USER_BUBBLE_SHAPE else ASSISTANT_BUBBLE_SHAPE,
                color = if (isUser) USER_BUBBLE_COLOR else ASSISTANT_BUBBLE_COLOR,
                modifier = Modifier
                    .padding(horizontal = BUBBLE_SIDE_MARGIN)
                    .combinedClickable(
                        // 再点一下气泡本身 = 收起按钮
                        onClick = { if (showActions) onDismissActions() },
                        onLongClick = onLongPress
                    )
            ) {
                Text(
                    text = message.content,
                    fontSize = BUBBLE_TEXT_SIZE,
                    color = if (isUser) Color.White else Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(BUBBLE_PADDING),
                    textAlign = TextAlign.Justify
                )
            }

            // 反馈 #1：操作按钮默认隐藏，长按这条气泡才展开（同一时刻只展开一条），
            // 旧的长按下拉菜单（编辑/复制/重新生成/删除这条/删除这条及之后）已整体删除。
            AnimatedVisibility(
                visible = showActions,
                enter = fadeIn(tween(120)) + expandVertically(tween(140), expandFrom = Alignment.Top),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(120), shrinkTowards = Alignment.Top)
            ) {
                MessageActionRow(
                    isUser = isUser,
                    speakState = speakState,
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.getFullText()))
                        onToast("已复制到剪贴板")
                    },
                    onSpeak = onSpeak,
                    onRegenerate = onRegenerate,
                    onEditAndResend = { onEditAndResend(message.content) },
                    onDelete = onDelete
                )
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            UserAvatar(modifier = Modifier.size(AVATAR_SIZE), photoPath = userAvatarPath)
        }
    }
}

/**
 * 气泡下方的一排简笔操作图标（反馈 #3 / #4）。
 *
 * - 助手气泡：复制 / 朗读 / 重新生成 / 删除
 * - 用户气泡：复制 / 编辑后重答 / 删除
 *
 * 图标做得低调（22dp、55% 白），不抢内容的视觉重心，
 * 但常驻显示 —— 长按菜单在小屏上不好发现。
 *
 * 本轮 item 5：17dp → 22dp、45% → 55% 白，间距 16 → 18dp，
 * 和调大后的气泡 / 字号同一档，手指也更好点。
 */
@Composable
private fun MessageActionRow(
    isUser: Boolean,
    /** 第 21 项：这条消息的朗读状态（不是"正在读的那条"时由调用方传 IDLE） */
    speakState: TtsPlayState,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onRegenerate: () -> Unit,
    onEditAndResend: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionIcon(Icons.Outlined.ContentCopy, "复制", onCopy)
        if (isUser) {
            ActionIcon(Icons.Outlined.Edit, "编辑后重答", onEditAndResend, glyphScale = GLYPH_SCALE_REGEN)
        } else {
            // 第 4 项：朗读按钮改成「喇叭 关/开」两态 ——
            //   没在读 = 线框喇叭（Icons.Outlined.VolumeUp，和这一排其它图标同一套线框风格）；
            //   正在读 = **实心喇叭**（Icons.Filled.VolumeUp，"有声音特性"的那个：实心比线框重，
            //            一眼看出正在出声），保留第 21 项的紫色呼吸变色；
            //   已暂停 = 实心喇叭 + 常亮紫色（不呼吸，避免误以为还在读）。
            // 第 3 项：三态都是同一个喇叭字形（不再在 Pause / PlayArrow 之间跳），
            // 加上所有 ActionIcon 都走同一个 ACTION_ICON_SIZE，所以这一排按钮尺寸恒定一致。
            when (speakState) {
                // 正在读：实心喇叭 + 呼吸变色（一眼看出"这条在播"）；再点一下 = 停止
                TtsPlayState.SPEAKING ->
                    ActionIcon(Icons.Filled.VolumeUp, "停止朗读", onSpeak, active = true, pulse = true, glyphScale = GLYPH_SCALE_SPEAK)

                // 已暂停（系统自动暂停，如来电/切后台）：实心喇叭 + 常亮紫色，点了同样回到停止
                TtsPlayState.PAUSED ->
                    ActionIcon(Icons.Filled.VolumeUp, "停止朗读", onSpeak, active = true, glyphScale = GLYPH_SCALE_SPEAK)

                TtsPlayState.IDLE ->
                    ActionIcon(Icons.Outlined.VolumeUp, "朗读", onSpeak, glyphScale = GLYPH_SCALE_SPEAK)
            }
            ActionIcon(Icons.Outlined.Refresh, "重新生成", onRegenerate, glyphScale = GLYPH_SCALE_REGEN)
        }
        ActionIcon(Icons.Outlined.DeleteOutline, "删除", onDelete, glyphScale = GLYPH_SCALE_DELETE)
    }
}

/** 第 21 项：朗读中的呼吸变色范围（紫色系，和 App 主色一致） */
private val SPEAK_TINT_DIM = Color(0xFF7E57C2)
private val SPEAK_TINT_BRIGHT = Color(0xFFD8A5E8)

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    /** 第 21 项：true = 这条正在朗读/暂停中，图标高亮（紫色） */
    active: Boolean = false,
    /** 第 21 项：true = 在"呼吸"变色（只有真正在朗读时开；暂停时用常亮色） */
    pulse: Boolean = false,
    /**
     * 第 2 项（本轮）：字形绘制补偿倍数 —— 四个按钮声明尺寸都是 22dp，
     * 但各字形的墨迹大小不同（复制最大），用小倍数把偏小的字形画到和复制一样大。
     * 只影响绘制，不影响布局/间距/点击热区。
     */
    glyphScale: Float = 1f
) {
    val tint = when {
        pulse -> {
            // 0→1→0 无限来回：颜色在暗紫与亮紫之间呼吸，一眼能看出"这条在播"
            val transition = rememberInfiniteTransition(label = "speakTint")
            val breath by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "speakPulse"
            )
            lerp(SPEAK_TINT_DIM, SPEAK_TINT_BRIGHT, breath)
        }

        active -> SPEAK_TINT_BRIGHT

        else -> Color.White.copy(alpha = 0.55f)
    }
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = tint,
        modifier = Modifier
            .size(ACTION_ICON_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            // 第 2 项：scale 必须放在链尾 —— Compose 的 graphicsLayer 也会参与命中测试，
            // 放在 clickable 之前会把 48dp 触摸热区一起放大（实测重新生成涨到 195px 并压到
            // 相邻按钮上）。放最后 = 只缩放绘制，热区仍是标准 48dp。
            .scale(glyphScale)
    )
}

@Composable
fun TypingIndicator(
    character: Character,
    modifier: Modifier = Modifier,
    /**
     * 本轮生成开始的时间戳（epoch ms，0 = 未提供）。
     * 计时以它为起点而不是进页面才起算：用户中途退出再进聊天页，
     * 「已等待」秒数接着跑、不重头计算（计时状态放在 ChatViewModel，跨页面存活）。
     */
    sinceMs: Long = 0
) {
    // 建议 #1：首字要等模型生成完才到（CPU 推理首包可能 1~2 分钟），
    // 给用户一个"在动"的实时计时，避免以为卡死了
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            tick++
        }
    }
    // sinceMs 有效时按真实起点算已等待秒数；tick 每秒变化负责触发重组，
    // 「+ tick - tick」恒等于 0，但读了一次 tick 就完成每秒的重组订阅
    val seconds = if (sinceMs > 0) {
        ((System.currentTimeMillis() - sinceMs) / 1000L).toInt().coerceAtLeast(0) + tick - tick
    } else tick
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start
    ) {
        CharacterAvatar(character = character, modifier = Modifier.size(AVATAR_SIZE))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH)) {
            // G10：流式气泡同样带角色名（与 MessageBubble 一致）
            Text(
                text = character.name,
                fontSize = BUBBLE_NAME_SIZE,
                color = Color(0xFFD8A5E8).copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)
            )
            Surface(shape = RoundedCornerShape(16.dp), color = ASSISTANT_BUBBLE_COLOR) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = BUBBLE_PADDING)) {
                    // 反馈 #7：去掉三个跳动圆点，改成"正在思考中"文字本身
                    // 由左向右的浅蓝色扫光（循环），一眼就知道还在跑
                    // 反馈 #2：文案后面补上省略号；本轮：「模型思考中」→「正在思考中」
                    ShimmerText(text = "正在思考中...")
                }
            }
            // 已等待时间（CPU 推理首包较慢，明示"还在动"）
            Text(
                text = "已等待 ${seconds}s，可点右侧停止随时放弃",
                fontSize = BUBBLE_META_SIZE,
                color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        }
    }
}

/**
 * 浅蓝扫光文字（反馈 #7）。
 *
 * 用 [Brush.linearGradient] 画文字颜色，起止点随时间从屏幕左侧扫到右侧，
 * 循环往复：暗蓝 → 亮蓝 → 暗蓝，扫过时像一道光从字上掠过。
 * 宽度用固定的虚拟像素（文字只有几个字，180f 足够覆盖），不依赖测量。
 */
@Composable
private fun ShimmerText(text: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking_shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shift"
    )
    val dim = Color(0xFF4FA3E3)      // 浅蓝（暗部）
    val bright = Color(0xFFD6F0FF)   // 扫光高亮
    val span = 220f
    val brush = Brush.linearGradient(
        colors = listOf(dim, bright, dim),
        start = Offset(x = shift * 2f * span - span, y = 0f),
        end = Offset(x = shift * 2f * span, y = 0f)
    )
    Text(
        text = text,
        fontSize = 14.sp,
        style = TextStyle(brush = brush),
        modifier = modifier
    )
}

/**
 * 流式气泡。
 *
 * **布局必须与 [MessageBubble] 的助手分支逐项一致** —— 圆角、宽度上限、
 * 内边距、字号、头像尺寸全部引用同一组常量。
 * 这是"输出到最后样式会跳变"那个 bug 的根治办法：不再各写各的。
 */
@Composable
fun StreamingBubble(
    content: String,
    character: Character,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        // 与 MessageBubble 保持一致：头像顶部对齐，流式吐字时只长气泡、头像不跳
        verticalAlignment = Alignment.Top
    ) {
        CharacterAvatar(character = character, modifier = Modifier.size(AVATAR_SIZE))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH)) {
            // 本轮：流式吐字全程显示角色名（与 MessageBubble / TypingIndicator 同款）——
            // 以前只有吐完、切成 MessageBubble 后名字才出现，中间流式阶段是「无名气泡」
            Text(
                text = character.name,
                fontSize = BUBBLE_NAME_SIZE,
                color = Color(0xFFD8A5E8).copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)
            )
            Surface(
                shape = ASSISTANT_BUBBLE_SHAPE,
                color = ASSISTANT_BUBBLE_COLOR,
                modifier = Modifier.padding(horizontal = BUBBLE_SIDE_MARGIN)
            ) {
                Text(
                    text = content,
                    fontSize = BUBBLE_TEXT_SIZE,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(BUBBLE_PADDING),
                    textAlign = TextAlign.Justify
                )
            }
            // 建议 #1：生成中的实时进度提示
            Text(
                text = "生成中 · 已收到 ${content.length} 字",
                fontSize = BUBBLE_META_SIZE,
                color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.padding(start = 12.dp, top = 2.dp)
            )
        }
    }
}

/**
 * 通用药丸（pill）—— **全 App 的提示药丸只有这一处样式定义**。
 *
 * [TopHintHost]（屏幕顶部的浮层药丸）和聊天页输入框上方的内联提示
 * （需求 #10「暂不支持同步会话」）都调它，所以"药丸长什么样"永远只有一份：
 * 主色渐变底（紫 #A855F7 → 玫紫 #7C3AED）+ 1dp 亮边 + 左侧白色小圆点
 * + 13sp 加粗纯白文字。配色 / 尺寸的来由见下方 [TopHintHost] 的注释。
 *
 * 调用方拿到的是一颗**跟内容一样大的**药丸，自己决定放哪儿、什么时候显示。
 */
@Composable
fun HintPill(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .shadow(elevation = 10.dp, shape = RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFA855F7), Color(0xFF7C3AED))
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 17.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.9f))
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}

/**
 * 反馈 #12：气泡操作（复制 / 朗读 / 删除…）的小提示统一显示在**屏幕顶部**。
 *
 * 本轮需求 #3：原来是一颗 `0xF02B2B3D` 的灰蓝药丸，跟聊天页深色底 + 深色气泡
 * 几乎同一个明度，飘在顶上像是"界面上的脏点"。现在改成：
 * - 主色渐变底（紫 #A855F7 → 玫紫 #7C3AED）+ 1dp 亮边，暗背景上一眼就能看到
 * - 左侧一颗白色小圆点做视觉锚点，文字加粗、纯白，投影加深
 * 第 5 项：整颗药丸放大一档 —— 字号 11sp → 13sp、内边距 13/7 → 17/10dp、
 * 左侧圆点 5 → 6dp、圆点与文字间距 7 → 9dp。**全 App 的提示都走这一个组件**
 * （对话内复制/朗读/删除、历史页复制/删除、设置页保存、全局 toast），
 * 所以"调大"一次就是所有药丸一起变，统一性由结构保证，不会出现大小不一。
 * 放大的同时仍然是"看得见但不抢对话"的量级：13sp 只比正文小一档。
 *
 * 本轮（需求 #3 串扰修复）：样式本体抽成 [HintPill]，
 * 聊天页输入框上方那颗内联药丸也复用它 —— 两处不可能再各写各的。
 *
 * 传 null 即隐藏；[hint] 变化会重新计时，连续操作不会互相打断。
 */
@Composable
fun TopHintHost(
    hint: String?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn(tween(140)) + slideInVertically(tween(160)) { -it / 2 },
        exit = fadeOut(tween(240)) + slideOutVertically(tween(240)) { -it / 2 },
        modifier = modifier
    ) {
        val current = hint
        if (current != null) {
            LaunchedEffect(current) {
                delay(1600)
                onDismissed()
            }
            HintPill(text = current)
        }
    }
}
