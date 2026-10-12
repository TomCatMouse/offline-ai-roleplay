package com.example.roleplay.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Segment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.ai.tts.TtsPlayState
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.ChatMessage
import com.example.roleplay.ui.components.ACTION_ICON_SIZE
import com.example.roleplay.ui.components.GLYPH_SCALE_DELETE
import com.example.roleplay.ui.components.GLYPH_SCALE_REGEN
import com.example.roleplay.ui.components.HintPill
import com.example.roleplay.ui.components.MessageBubble
import com.example.roleplay.ui.components.StreamingBubble
import com.example.roleplay.ui.components.TopHintHost
import com.example.roleplay.ui.components.TypingIndicator
import com.example.roleplay.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ============================================================================
//  反馈 #9 / #10：下拉框统一尺寸与配色
//
//  M3 的 DropdownMenuItem 默认高 48dp、字号 14sp、内边距 12/8dp，
//  在 720×1280 的小屏上显得又高又笨；这里统一压到 38dp + 13sp。
//  本轮 item 5：34dp/12sp/14dp → 38dp/13sp/16dp，
//  跟调大后的「剧情线」按钮和气泡字号对齐。
// ============================================================================
private val DROPDOWN_ITEM_HEIGHT = 38.dp
private val DROPDOWN_TEXT_SIZE = 13.sp
private val DROPDOWN_ICON_SIZE = 16.dp
private val DROPDOWN_CONTENT_PADDING = PaddingValues(horizontal = 12.dp)

/** 反馈 #10：浅紫强调色（图标与文字都提亮，避免在深色菜单上糊成一团看不清） */
private val STORYLINE_ACCENT = Color(0xFFD8A5E8)

/** M7：剧情线菜单里列表最多一次露出多少条，超出内部滚动 */
private const val STORYLINE_LIST_MAX_ITEMS = 8

/**
 * 反馈 #9：剧情线名字超过 7 个字就截断成「前 7 字 + …」。
 *
 * 注意按**字符**数（中文 1 字算 1）而不是像素宽度算，
 * 这样 720p 和 1080p 手机上的截断位置一致，菜单宽度也稳定。
 */
private fun compactStorylineTitle(raw: String): String =
    if (raw.length > 7) raw.take(7) + "…" else raw

// ============================================================================
//  「一键跳到底部」按钮的出现门槛（用户反馈：出现时机有点早）
//
//  旧条件 = !listState.canScrollForward，是**零容差**判断：
//    · 内容刚长高 1px（新气泡入库那一帧布局还没落定）；
//    · 流式吐字的那一帧里"按行跟卷"还没来得及补上滚动；
//  按钮就会冒出来 —— 手指轻碰一下、甚至只是测量抖动一下都会闪。
//
//  新条件 = "离底部到底翻走了多远"（像素）+ **两个阈值做滞回**：
//    · 翻走 ≥ 视口高 30% → 显示（明确向上翻走一段才给按钮）
//    · 回到 ≤ 视口高 15% → 隐藏（贴底附近绝对不显示）
//    · 15%~30% 之间 = 滞回区，维持上一次的显示状态 → 阈值线上不来回横跳
//
//  为什么不会误触发 / 不会忽隐忽现：
//    · 流式期间 autoFollow 一直把"最后一条"贴住视口下沿，
//      离底距离恒在 0 附近，离 30% 的显示门槛差着上百像素；
//    · "按行跟卷"每帧最多补一行（≈40px ≈ 20dp），远小于 30% 视口（≈150~180dp，随屏幕而定）；
//    · 手抖位移是几 dp 级，跨不过 30% 视口这道坎；
//    · 真显示出来以后，要往回滚到 15% 以内才消失 → 边界处不会反复闪。
//  点按后的跳转逻辑（scrollToBottom）一个字没动。
// ============================================================================
private const val JUMP_TO_BOTTOM_SHOW_RATIO = 0.30f
private const val JUMP_TO_BOTTOM_HIDE_RATIO = 0.15f

/** 最后一条已经不在视口里（早已翻走一屏以上）时的代值，只需保证大于任何显示阈值 */
private const val JUMP_TO_BOTTOM_FAR_PX = 1 shl 20

// ============================================================================
//  需求 #10：跨角色发送时的提示
//
//  场景：甲正在输出内容，用户切到乙的聊天界面，在乙界面输入并点发送。
//  后端行为（ChatViewModel.sendMessage）：消息**照常入库、一句都不丢**，
//  但绝不给乙启动第二路推理（底层推理服务只有 1 个 slot，硬开就是再一次状态串扰）。
//  这里负责把"这一按为什么没有回复"讲清楚：在**输入框正上方**用 App 通用药丸
//  （ChatComponents.HintPill —— 和顶部提示药丸同一套样式，不新引入任何组件/依赖）。
// ============================================================================

/** 提示文案（需求给定原文） */
private const val SYNC_UNSUPPORTED_HINT = "暂不支持同步会话"

/**
 * 药丸停留时长。
 *
 * 比顶部提示的 1600ms 长一档：顶部那颗是"操作已完成"的确认（一眼扫过就够），
 * 而这句话是"你刚发的那条暂时不会有人回"，需要看清、也需要一点反应时间。
 */
private const val SYNC_HINT_VISIBLE_MS = 3200L

/**
 * 离列表底部还有多远（px）= 视口下沿之下还剩多少内容没露出来。
 *
 * 最后一条在视口里 → 它的下沿到视口下沿的距离，就是"还没滚完的那一段"；
 * 最后一条已经不在视口里 → 早就翻走一屏以上，返回一个必然超过显示阈值的值。
 * （算法与上面"按行跟卷"里补滚 overflow 的算法同源，避免两处口径打架。）
 */
private fun LazyListState.scrolledAwayFromBottomPx(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < info.totalItemsCount - 1) return JUMP_TO_BOTTOM_FAR_PX
    return (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0)
}

// ============================================================================
//  推理失败提示（用户反馈：提示语格式优化）
//
//  失败在 ViewModel 里是**一条普通助手消息**（ChatViewModel.runGeneration 的 catch）：
//      content = "⚠️ 推理失败：<原始异常>\n请检查推理服务是否可用。"
//  本轮**不改**它的产生 / 清除语义（仍在 ViewModel、仍是同一条消息），
//  只改这里的**渲染与文案组织**：拆成"醒目标题 / 一句人话 / 更小更淡的技术细节"。
//  轻量做法：只识别那套固定前缀 —— 前缀万一以后改了，会退回普通气泡渲染，不会崩。
// ============================================================================
private const val INFERENCE_FAILURE_PREFIX = "⚠️ 推理失败："

/** ViewModel 在原始异常后面固定追加的那句收尾（人话层已经替代它，解析时剔掉） */
private const val INFERENCE_FAILURE_TAIL = "请检查推理服务是否可用。"

/** 这条消息是不是"推理失败"提示（只看固定前缀，不碰错误的产生/清除语义） */
private fun isInferenceFailureText(content: String): Boolean =
    content.startsWith(INFERENCE_FAILURE_PREFIX)

/** 原始串里的 HTTP 状态码："Server error 500: …" / "HTTP 503" / "状态码 500" */
private val INFERENCE_STATUS_REGEX = Regex("""(?:Server error|HTTP|http|状态码)\s*(\d{3})""")

/**
 * 原始异常 → 一句人话（本轮价值最大的一步）。
 *
 * 原始串往往是嵌套过的 Java 异常（"推理失败: 推理失败: Connection refused"），
 * 或者带着整段 JSON body 的 "Server error 500: {...}"，直接齐平铺在界面上又长又吓人。
 * 这里只按关键字归类，**不动**任何错误产生 / 上报逻辑。
 */
private fun humanizeInferenceError(raw: String): String {
    val lower = raw.lowercase()
    val status = INFERENCE_STATUS_REGEX.find(raw)?.groupValues?.get(1)
    return when {
        // 连不上服务：Java ConnectException / ECONNREFUSED / 服务未连接 / 不可达
        listOf(
            "connection refused", "econnrefused", "failed to connect", "connectexception",
            "connection reset", "不可达", "未连接"
        ).any { lower.contains(it) } ->
            "没连上推理服务，请到设置页检查服务器地址"
        // 超时：SocketTimeoutException / Read timed out / 连接超时
        listOf("timeout", "timed out", "超时").any { lower.contains(it) } ->
            "推理服务响应超时，模型可能在忙或思考太久"
        // 引擎退出：真机包本地引擎「引擎退出了（code=-1）」/ 被系统杀掉
        listOf("引擎退出", "引擎被系统杀掉", "code=-1", "exited", "exit code").any { lower.contains(it) } ->
            "推理引擎已退出，请到设置页重新启动引擎"
        // 非 200：llama-server 返回 4xx/5xx（body 通常是一大段 JSON，不往界面上铺）
        status != null ->
            "推理服务返回错误（状态码 $status）"
        // 空回复 / 被截断：HTTP 通了但没有内容
        listOf("没有返回", "空回复", "返回为空", "empty").any { lower.contains(it) } ->
            "模型这次没有返回内容，可以点重新生成再试"
        else ->
            "这次生成没有完成，可以点重新生成再试"
    }
}

/**
 * 把 ViewModel 拼好的失败消息拆成（一句人话, 技术细节）。
 *
 * 输入形如："⚠️ 推理失败：Server error 500: {...}\n请检查推理服务是否可用。"
 * 技术细节保留原始异常原文（只做展示层的小字弱化），排障信息一点不丢。
 */
private fun humanizeInferenceFailure(content: String): Pair<String, String> {
    val raw = content
        .removePrefix(INFERENCE_FAILURE_PREFIX)
        .substringBefore("\n" + INFERENCE_FAILURE_TAIL)
        .trim()
        .ifBlank { "（没有更多信息）" }
    return humanizeInferenceError(raw) to raw
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    character: Character,
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    streamingContent: String,
    /** 本轮生成开始的时间戳（epoch ms；0 = 无）。思考气泡的「已等待 Xs」以它为起点，
     *  中途退出聊天页再进来计时不重算（起点存在 ChatViewModel 里） */
    generatingSinceMs: Long = 0,
    /**
     * 第 23 项：输入框草稿由外部（ChatViewModel）持有 ——
     * 返回首页再进来、切剧情线、旋屏都能接着写，不再随页面销毁丢掉。
     */
    inputText: String = "",
    onInputTextChange: (String) -> Unit = {},
    onBack: () -> Unit,
    /**
     * 发送一条消息。
     *
     * 需求 #10：返回值告诉本页"这一按到底算不算数" ——
     * false = 另一条会话（别的角色）正在推理，消息**已入库**但本轮不会有回复，
     * 本页据此在输入框上方弹一颗「暂不支持同步会话」药丸。
     * （默认 true：老调用方 / 预览场景不受影响。）
     */
    onSend: (String) -> Boolean = { true },
    onStop: () -> Unit,
    /** 反馈 #3：重新生成某条回复 —— 重发它前面那句用户输入（返回值语义同 [onSend]） */
    onResendRegenerate: (String) -> Boolean = { true },
    /**
     * 反馈 #3：朗读一段内容。返回值 = 是否真的开始朗读了：
     * true 就弹一条顶部小提示，false 由外部弹"没有 TTS 引擎"的说明弹窗（反馈 #12）。
     */
    onSpeak: (String) -> Boolean = { false },
    /**
     * 第 21 项：朗读三态（IDLE / SPEAKING / PAUSED）+ 正在读的那条原文。
     * 只有"状态非 IDLE 且原文一致"的那条气泡，朗读图标才变成暂停/继续并动态变色。
     */
    speakState: TtsPlayState = TtsPlayState.IDLE,
    speakActiveText: String = "",
    /** 反馈 #4：编辑后重答 —— 改这条用户消息并重跑生成（返回值语义同 [onSend]） */
    onEditResend: (String, String) -> Boolean = { _, _ -> true },
    onDeleteMessage: (String) -> Unit,
    onEditCharacter: () -> Unit,
    onOpenMemories: () -> Unit = {},
    onNewConversation: () -> Unit,
    onClearHistory: () -> Unit,
    // ---- 剧情线（多会话）切换 ----
    storylines: List<ChatViewModel.ConversationSummary>,
    currentStorylineId: String?,
    onSwitchStoryline: (String) -> Unit,
    /** M8：剧情线改名（空串 = 恢复默认名称） */
    onRenameStoryline: (String, String) -> Unit = { _, _ -> },
    // ---- 本轮需求 #5：用户自己的昵称与头像（设置页里改，聊天页显示）----
    userName: String = "",
    userAvatarPath: String? = null
) {
    // 第 23 项：inputText 改由 ViewModel 传入/回写（草稿跨页面保留），不再是页面本地 remember
    // 反馈 #4：点了"编辑后重答"后记住来源消息，下次发送就是"替换并重答"而不是追加
    var editSourceMessageId by remember { mutableStateOf<String?>(null) }
    // 反馈 #1：当前展开了操作按钮的那条消息（null = 全部收起），长按气泡切换
    var actionsMessageId by remember { mutableStateOf<String?>(null) }
    // 反馈 #12：气泡操作的小提示显示在顶部（而不是底部 Snackbar）
    var topHint by remember { mutableStateOf<String?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // M8：剧情线改名弹窗（null = 没在改哪条）
    var renamingStoryline by remember { mutableStateOf<ChatViewModel.ConversationSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 推理失败提示卡片上的「复制」用（和气泡里的复制同一个入口）
    val clipboard = LocalClipboardManager.current

    // ===== 需求 #10：跨角色发送时的药丸提示 =====
    // 甲正在输出、用户切到乙界面点了发送 → OnSend 返回 false → 亮起这颗药丸。
    // 按 character.id 重新起一份：切到别的角色时不会把上一个角色的提示带过去。
    var crossChatHint by remember(character.id) { mutableStateOf(false) }
    LaunchedEffect(crossChatHint) {
        if (crossChatHint) {
            kotlinx.coroutines.delay(SYNC_HINT_VISIBLE_MS)
            crossChatHint = false
        }
    }

    /**
     * 第 20 项：发送/停止的**唯一**入口 —— 右侧按钮点击与软键盘回车（ImeAction.Send）
     * 共用这一份逻辑，不会出现"按钮能发、回车行为不一致"的分叉。
     * 生成中则回车 = 停止（和按钮同一个语义）。
     *
     * 需求 #10（真机反馈优化）：两条发送路径被跨会话互斥挡下时都**什么都不做** ——
     * 消息不入库、草稿原样留在输入框里，只在页面**上方**弹一颗药丸说明原因，
     * 等对方跑完再点一次发送即可。用户明确要求「点发送就弹提示，消息还留在输入框」。
     */
    fun submitInput() {
        if (isGenerating) {
            onStop()
            return
        }
        if (inputText.isBlank()) return
        // 反馈 #4：来自"编辑后重答"的输入 → 改原消息并重跑，不新增一条
        val srcId = editSourceMessageId
        if (srcId != null) {
            if (onEditResend(srcId, inputText)) {
                editSourceMessageId = null
                // 发出去就清空草稿（ViewModel 里那份一起清，返回重进不会又把旧字填回来）
                onInputTextChange("")
            } else {
                crossChatHint = true
            }
        } else {
            // 需求 #10：false = 另一条会话正在推理 → 本次什么都没发生，草稿必须留着
            if (onSend(inputText)) {
                onInputTextChange("")
            } else {
                crossChatHint = true
            }
        }
    }

    // 列表项总数 = 已落库消息 + 流式气泡/等待指示器
    // 注意：流式期间 messages.size 不变，只靠它做 key 会导致长会话不再自动下滚
    val itemCount = messages.size + if (isGenerating) 1 else 0

    // ========== 自动跟随开关（反馈 #1 / #2）==========
    // autoFollow = "内容变长时要不要把视图跟着往上推"。
    //   用户往上翻历史 → 立刻关掉，模型继续吐字也不再把他拽回底部（#2）
    //   用户滚回底部（或自己发消息、点按钮）→ 重新打开（#1）
    // 用 canScrollForward 判断"是否在底部"最准：底部 = 没有可继续向下滚的内容。
    var autoFollow by remember { mutableStateOf(true) }
    // 注意：atBottom 现在**只服务于自动跟随**（用户真滚回底部才恢复跟卷），
    // 不再拿来控制"一键跳到底部"按钮 —— 那个按钮改用带滞回的翻走距离判断，
    // 阈值与理由见文件头 JUMP_TO_BOTTOM_* 注释。
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }

    // 只有"手指拖动"才算用户干预；程序化 scrollBy/scrollToItem 不会发 DragInteraction，
    // 所以自动跟随本身不会把自己关掉。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) autoFollow = false
        }
    }
    LaunchedEffect(atBottom) {
        // 用户把列表拖回了底部 → 恢复跟随
        if (atBottom) autoFollow = true
    }

    // ========== 「一键跳到底部」按钮的显示状态（带滞回）==========
    // 本轮反馈：按钮出现时机太早。只看"离底部翻走了多远"并留出滞回余量，
    // 门限值 / 为什么流式期间不会忽隐忽现 → 见文件头 JUMP_TO_BOTTOM_* 说明。
    var showJumpToBottom by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            // 视口高一起进快照：转屏 / 键盘弹出导致视口变化时，阈值跟着重算
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
            listState.scrolledAwayFromBottomPx() to viewport
        }
            .distinctUntilChanged()
            .collect { (away, viewport) ->
                showJumpToBottom = when {
                    away >= viewport * JUMP_TO_BOTTOM_SHOW_RATIO -> true
                    away <= viewport * JUMP_TO_BOTTOM_HIDE_RATIO -> false
                    // 滞回区：维持现状，绝不在阈值线附近反复闪
                    else -> showJumpToBottom
                }
            }
    }

    // 新消息入库（发送/删除/切换剧情线）时，回到跟随状态并滚到底。
    // G19：多行历史里发完消息"不跳到底"的根因 —— 这个 effect 触发时新消息
    // 往往**还没完成布局**，旧的 layoutInfo 读出来的 lastIndex 是过期值，
    // 补滚那一轮找不到目标项就直接 return 了。现在把 itemCount 传进
    // scrollToBottom，让它按"数据里的真实条数"定位、等几帧布局落定再补滚。
    // 第 4 项：点角色卡进来后**直接落在最底部**，不要"从上往下"的过程。两道保险：
    //   ① 贴底本身是瞬时定位（scrollToBottom 全程 scrollToItem，没有动画）；
    //   ② 首次贴底完成前列表先透明（见 LazyColumn 上的 alpha）——数据晚一两帧才到、
    //      或流式气泡刚插入时，用户都不会先看到顶部再被"拉"到底部。
    // 真的没有历史（新角色/空对话）时最多等 220ms 就放行，避免空对话一直白屏。
    var initialPinDone by remember(character.id) { mutableStateOf(false) }
    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            autoFollow = true
            listState.scrollToBottom(expectedItems = itemCount)
            initialPinDone = true
        } else {
            kotlinx.coroutines.delay(220)
            initialPinDone = true
        }
    }

    // ========== 按行跟卷（修 #2「头像一跳一跳」）==========
    // 旧版：每个 token 触发一次"跳到列表底部" —— token 的高度增量不是整行，
    // 每次落点都不一样，整屏（连同头像）就跟着抖，观感就是"头像一跳一跳"。
    // 新版：监听最后一条气泡（流式气泡/等待指示器）的**实际高度**，
    // 只有它真长高了才向上滚\"恰好长出来的那一段\"。
    // 头像顶部对齐（见 ChatComponents），所以效果是：头像钉在原地，
    // 每吐出一行新字，整体才平稳地上移一行 —— "头像固定，只向上吐字"。
    var lastItemHeightPx by remember { mutableIntStateOf(0) }
    var lastFollowedHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(lastItemHeightPx, autoFollow) {
        if (isGenerating) {
            val growth = lastItemHeightPx - lastFollowedHeightPx
            // 关键：先同步基线，再决定滚不滚。
            // 这样用户上翻期间积累的高度差不会被"攒着"，等他一回到
            // 底部时一次性弹过去（那时只补新的增量）。
            lastFollowedHeightPx = lastItemHeightPx
            // 第 3 项：不再要求 growth > 0 —— 反馈的"最后一行贴不到底"往往发生在一帧里
            // 高度没变（或增长还没上报）但上一帧的滚动已被布局时机吃掉的时候。
            // 只要用户还在跟卷（autoFollow），就按位置把最后一条重新贴到视口下沿；
            // 没有缺口时 overflow <= 0，不会产生任何位移，流式期间也不会抖。
            if (autoFollow) {
                // 本轮 item 4：不只补 growth —— 只要"最后一条"在布局里，
                // 就把它重新贴到视口下沿。之前万一有一段没滚到位（动画被掐断），
                // 只补增量的话这段缺口会一直留着；按位置重贴能自愈，
                // 而且增量本来就等于这里的 overflow，流式期间不会多抖。
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
                if (last != null) {
                    val overflow = last.offset + last.size - info.viewportEndOffset
                    if (overflow > 0) listState.scrollBy(overflow.toFloat())
                } else {
                    listState.scrollBy(growth.toFloat())
                }
            }
        } else {
            lastFollowedHeightPx = 0
        }
    }

    // 生成**开始**和**结束**都贴一次底：
    //   开始 —— "思考中"气泡就是这一刻插进来的，它下面还有流式回答的位置，
    //           不跳过去就只看到自己发的那条（本轮 item 4 反馈的现象）；
    //   结束 —— 流式气泡换成正式气泡时高度会变，不补这一下底部会差一截。
    // 用户正在翻历史（autoFollow = false）时不打扰他（反馈 #2）。
    LaunchedEffect(isGenerating) {
        if (autoFollow && itemCount > 0) listState.scrollToBottom(expectedItems = itemCount)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 角色名居中：TopAppBar 默认左对齐，用 fillMaxWidth + 居中压回来
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            character.name,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                actions = {
                    // 剧情线切换：点击展开该角色的全部会话，可来回切换或新建
                    val showStorylineMenu = remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF2A2A3E),
                            modifier = Modifier.clickable { showStorylineMenu.value = true }
                        ) {
                            // 本轮 item 5：整颗「剧情线 ▾」按钮调大（图标 18dp、文字 14sp、
                            // 内边距 8dp），和调大后的下拉菜单项、气泡字号同一档
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Segment,
                                    "剧情线",
                                    tint = Color(0xFFCE93D8),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("剧情线", fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showStorylineMenu.value,
                            onDismissRequest = { showStorylineMenu.value = false }
                        ) {
                            DropdownMenuItem(
                                // 反馈 #10：文案里的\"＋\"去掉（保留左侧 Add 图标），
                                // 图标用亮紫、文字用白色 —— 两者各自都看得清，也不会互相糊住
                                text = { Text("新建剧情线", fontSize = DROPDOWN_TEXT_SIZE, color = Color.White) },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, null, tint = STORYLINE_ACCENT, modifier = Modifier.size(DROPDOWN_ICON_SIZE))
                                },
                                onClick = {
                                    showStorylineMenu.value = false
                                    onNewConversation()
                                },
                                modifier = Modifier.height(DROPDOWN_ITEM_HEIGHT),
                                contentPadding = DROPDOWN_CONTENT_PADDING
                            )
                            if (storylines.isNotEmpty()) Divider(color = Color.White.copy(alpha = 0.08f))
                            // M7：剧情线多了以后，M3 的 DropdownMenu 不会自己滚动、
                            // 整列直接伸出屏幕外（样式异常）。现在列表限高（最多 8 项），
                            // 超出部分在菜单内部滚动；「新建剧情线」固定在最上，永远点得到。
                            Column(
                                modifier = Modifier
                                    .heightIn(max = DROPDOWN_ITEM_HEIGHT * STORYLINE_LIST_MAX_ITEMS + 1.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                storylines.forEach { s ->
                                    val isCurrent = s.id == currentStorylineId
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                // 反馈 #9：名字超过 7 个字就截断成"7 字 + …"
                                                text = compactStorylineTitle(
                                                    if (s.title == "新的对话") "剧情线 ${s.id.take(4)}" else s.title
                                                ),
                                                fontSize = DROPDOWN_TEXT_SIZE,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isCurrent) Icons.Default.Check else Icons.Default.Chat,
                                                contentDescription = if (isCurrent) "当前剧情线" else null,
                                                tint = if (isCurrent) Color(0xFF7CE57C) else Color.White.copy(alpha = 0.5f),
                                                modifier = Modifier.size(DROPDOWN_ICON_SIZE)
                                            )
                                        },
                                        // M8：尾部小铅笔 = 给这条剧情线改名
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Edit,
                                                "重命名剧情线",
                                                tint = Color.White.copy(alpha = 0.35f),
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clickable {
                                                        showStorylineMenu.value = false
                                                        renameDraft = if (s.title == "新的对话") "" else s.title
                                                        renamingStoryline = s
                                                    }
                                            )
                                        },
                                        onClick = {
                                            showStorylineMenu.value = false
                                            if (!isCurrent) onSwitchStoryline(s.id)
                                        },
                                        modifier = Modifier.height(DROPDOWN_ITEM_HEIGHT),
                                        contentPadding = DROPDOWN_CONTENT_PADDING
                                    )
                                }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, "更多", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            // 反馈 #9：下拉框统一压矮 + 缩小字号
                            DropdownMenuItem(
                                text = { Text("编辑角色", fontSize = DROPDOWN_TEXT_SIZE) },
                                onClick = { showMoreMenu = false; onEditCharacter() },
                                modifier = Modifier.height(DROPDOWN_ITEM_HEIGHT),
                                contentPadding = DROPDOWN_CONTENT_PADDING
                            )
                            DropdownMenuItem(
                                text = { Text("长期记忆", fontSize = DROPDOWN_TEXT_SIZE) },
                                onClick = { showMoreMenu = false; onOpenMemories() },
                                modifier = Modifier.height(DROPDOWN_ITEM_HEIGHT),
                                contentPadding = DROPDOWN_CONTENT_PADDING
                            )
                            // 本轮 item 1：这里原本还有一项「新建剧情线」，
                            // 和顶栏「剧情线 ▾」菜单里的那项完全重复，已按需求删除。
                            Divider(color = Color.White.copy(alpha = 0.08f))
                            DropdownMenuItem(
                                text = { Text("清空当前对话", fontSize = DROPDOWN_TEXT_SIZE) },
                                onClick = { showMoreMenu = false; onClearHistory() },
                                modifier = Modifier.height(DROPDOWN_ITEM_HEIGHT),
                                contentPadding = DROPDOWN_CONTENT_PADDING
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D1A))) {
                // 代码审查（反馈 #14）：这里原本还留着一整段被注释掉的"模型状态条"
                // （引用已删除的 activeModelName 参数），属于死代码，直接清掉。
                // 需要看当前模型请去 设置 → 推理服务状态。

                LazyColumn(
                    state = listState,
                    // 第 4 项：首次贴底完成前先把列表透明化，避免"先看到顶部、再被拉到底部"。
                    // 数据已在内存时（itemCount 首帧就 > 0）几乎没有可感延迟；
                    // 数据还在读库时最多空 220ms（见 initialPinDone），不会长期白屏。
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .alpha(if (initialPinDone) 1f else 0f),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // key = 消息 id：新消息插入/删除时按身份复用气泡，
                    // 避免整列气泡（含头像）被重新绑定而闪一下
                    items(messages, key = { it.id }) { message ->
                        // 本轮反馈：「推理失败」不再和角色正文一样大、一样白地平铺在气泡里，
                        // 改走分层的小卡片。判定只认 ViewModel 那套固定前缀，
                        // 错误的产生 / 清除语义一个字没动（见文件头 INFERENCE_FAILURE_* 说明）。
                        if (message.isAssistant && isInferenceFailureText(message.content)) {
                            val (human, detail) = humanizeInferenceFailure(message.content)
                            InferenceErrorNotice(
                                humanMessage = human,
                                rawDetail = detail,
                                // 沿用气泡那套：长按才展开操作图标，点一下收起
                                showActions = actionsMessageId == message.id,
                                onLongPress = {
                                    actionsMessageId =
                                        if (actionsMessageId == message.id) null else message.id
                                },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(message.getFullText()))
                                    topHint = "已复制到剪贴板"
                                },
                                // 重试入口 = 这条消息**原来就有**的交互（原先要长按气泡才看到），
                                // 现在和提示放在一起；没有新增任何功能
                                onRegenerate = {
                                    actionsMessageId = null
                                    // 需求 #10：被"另一条会话正在推理"挡下时补一颗药丸说明，
                                    // 不再是点了完全没反应
                                    if (!onResendRegenerate(message.id)) crossChatHint = true
                                },
                                onDelete = {
                                    actionsMessageId = null
                                    onDeleteMessage(message.id)
                                    topHint = "已删除这条消息"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            MessageBubble(
                                message = message,
                                character = character,
                                // 反馈 #1：按钮只在长按这条气泡时出现
                                showActions = actionsMessageId == message.id,
                                onLongPress = {
                                    actionsMessageId =
                                        if (actionsMessageId == message.id) null else message.id
                                },
                                onDismissActions = { actionsMessageId = null },
                                onRegenerate = {
                                    // 反馈 #3：重新生成 = 复制这句回复对应的用户输入，
                                    // 模拟重新发送，再重答这一段（由 ViewModel 统一处理）
                                    // 需求 #10：另一条会话正在推理 → 没真跑，补一颗药丸说明
                                    if (!onResendRegenerate(message.id)) crossChatHint = true
                                },
                                onDelete = {
                                    onDeleteMessage(message.id)
                                    // 反馈 #12：删除也给一条顶部小提示（原来什么都不提示）
                                    topHint = "已删除这条消息"
                                },
                                // 反馈 #3：朗读。真的读起来了才弹顶部小提示；
                                // 没有 TTS 引擎时由 MainActivity 弹说明弹窗。
                                // 第 21 项：同一个入口现在负责 朗读 → 暂停 → 继续（MainActivity 里 toggle）
                                onSpeak = {
                                    if (onSpeak(message.getFullText())) topHint = "正在朗读…"
                                },
                                // 第 21 项：只有正在读/被暂停的那条才显示暂停或继续图标
                                speakState = if (speakActiveText == message.getFullText())
                                    speakState else TtsPlayState.IDLE,
                                // 反馈 #4：编辑后重答 —— 写回输入框，并在发送时替换原消息重答
                                // 反馈 #7：不再弹任何提示，直接写入输入框即可
                                onEditAndResend = { content ->
                                    onInputTextChange(content)
                                    editSourceMessageId = message.id
                                    actionsMessageId = null
                                },
                                // 反馈 #12：小提示统一走顶部提示条
                                onToast = { topHint = it },
                                // 本轮需求 #5：我自己的昵称 + 头像
                                userName = userName,
                                userAvatarPath = userAvatarPath,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (isGenerating && streamingContent.isNotEmpty()) {
                        item(key = "streaming") {
                            StreamingBubble(
                                content = streamingContent,
                                character = character,
                                // 记录实际高度，驱动上面的"按行跟卷"逻辑
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { lastItemHeightPx = it.size.height }
                            )
                        }
                    }
                    if (isGenerating && streamingContent.isEmpty()) {
                        item(key = "typing") {
                            TypingIndicator(
                                character = character,
                                sinceMs = generatingSinceMs,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { lastItemHeightPx = it.size.height }
                            )
                        }
                    }
                }

                Surface(color = Color(0xFF151525)) {
                    // 反馈：输入框和发送/停止按钮挨得太紧。左右内边距 8→12dp、
                    // 输入框自己再收 2dp、和按钮之间的间隔 6→10dp ——
                    // 输入框窄一点、中间留出呼吸感，按钮位置和尺寸一点没动。
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { onInputTextChange(it) },
                            modifier = Modifier.weight(1f).padding(end = 2.dp),
                            placeholder = {
                                Text(
                                    when {
                                        isGenerating -> "生成中…可点右侧停止"
                                        editSourceMessageId != null -> "修改后发送，将替换原消息并重答"
                                        else -> "输入消息..."
                                    },
                                    color = Color.White.copy(alpha = 0.35f)
                                )
                            },
                            maxLines = 4,
                            enabled = !isGenerating,
                            // 第 20 项：软键盘回车键 = 发送（生成中 = 停止），与右侧按钮同一份逻辑
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitInput() }),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color(0xFF1E1E32),
                                unfocusedContainerColor = Color(0xFF1E1E32),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = Color.White.copy(alpha = 0.6f),
                                disabledContainerColor = Color(0xFF1E1E32),
                                disabledPlaceholderColor = Color.White.copy(alpha = 0.35f)
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))

                        // G15：发送 / 停止用**同一个按钮槽位**只换图标和颜色，
                        // 切换时不销毁重建 —— 两个按钮永远在同一位置、同一尺寸，
                        // 不存在「换按钮跳一下 / 对不齐」的问题。
                        IconButton(
                            // 第 20 项：与软键盘回车共用 submitInput()，行为完全一致
                            onClick = { submitInput() },
                            enabled = isGenerating || inputText.isNotBlank(),
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .size(44.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = when {
                                    isGenerating -> Color(0xFFE53935)
                                    inputText.isNotBlank() -> Color(0xFF9C27B0)
                                    else -> Color(0xFF3A3A4E)
                                },
                                disabledContainerColor = Color(0xFF3A3A4E)
                            )
                        ) {
                            Icon(
                                if (isGenerating) Icons.Default.Stop else Icons.Default.Send,
                                contentDescription = if (isGenerating) "停止生成" else "发送",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // 反馈 #12：复制 / 朗读 / 删除的小提示——顶部一颗小药丸，1.6 秒自动淡出
            // 需求 #10（真机反馈优化）：跨角色发送被挡下的「暂不支持同步会话」也挪到这块
            // **页面顶部**区域，和上面那颗提示同一个容器，不再贴着输入框。
            // 两者共用一个 Column 就不会互相盖住；平时都不可见，一个像素都不占。
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = crossChatHint,
                    enter = fadeIn(tween(140)) + expandVertically(tween(160)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    Box(modifier = Modifier.padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                        HintPill(text = SYNC_UNSUPPORTED_HINT)
                    }
                }
                TopHintHost(
                    hint = topHint,
                    onDismissed = { topHint = null }
                )
            }

            // 往上翻历史后出现的"回到底部"按钮。
            // 显示条件 = 带滞回的"翻走够远"（见文件头 JUMP_TO_BOTTOM_* 说明），
            // 不再是零容差的 !canScrollForward —— 后者在流式吐字 / 内容刚变高时就会闪出来。
            AnimatedVisibility(
                visible = showJumpToBottom && itemCount > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        // 主动回到底部 = 重新开始跟随（反馈 #2 的"退出免打扰"）
                        autoFollow = true
                        scope.launch { listState.scrollToBottom() }
                    },
                    containerColor = Color(0xFF2A2A3E),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部")
                }
            }

            // M8：剧情线改名弹窗。
            // 反馈「所有弹窗整体统一」：原来这里用的是 M3 的 AlertDialog（浅色系统样式，
            // 和全 App 深色弹窗完全不是一个长相），现在统一走 AppTextDialog ——
            // 同一套放大后的外壳、同一套按钮，尺寸也随 DIALOG_* token 一起变小/变大。
            renamingStoryline?.let { target ->
                com.example.roleplay.ui.components.AppTextDialog(
                    title = "重命名剧情线",
                    initial = renameDraft,
                    confirmText = "保存",
                    placeholder = "留空 = 用首条消息当名称",
                    hint = "最多 16 个字，留空会恢复默认名称",
                    maxLength = 16,
                    onConfirm = { name ->
                        onRenameStoryline(target.id, name)
                        renamingStoryline = null
                    },
                    onDismiss = { renamingStoryline = null }
                )
            }
        }
    }
}

/**
 * 滚动到列表真正的底部。
 *
 * 为什么不能直接用 scrollToItem / animateScrollToItem：
 * 它们只把目标项的**顶部**对齐到视口顶端。当单条消息比一屏还高
 * （模型长篇大论、或角色卡开场白很长）时，顶部对齐后**底部仍在屏幕外**，
 * 于是新生成的文字全部落在看不见的地方 —— 这就是"输出过长就不向下滚动"。
 * 所以对齐之后，还要按实际布局把"超出视口下沿的那部分"补滚掉。
 *
 * G19：[expectedItems] = 调用方知道的"数据里到底有几条"。新消息刚入库时
 * layoutInfo 还是上一轮的旧布局（lastIndex 会差一条），用 expectedItems 兜底。
 *
 * 本轮 item 4：用户反馈"发完消息只跳到自己那条，看不到下面角色思考中的回答"。
 * 根因是**两个滚动驱动互相掐**：这里原来是 animateScrollToItem（几百毫秒的动画），
 * 而"按行跟卷"的 scrollBy 跟它同属 MutatePriority.Default 档位 ——
 * 思考中气泡一插进来、高度一上报，后者就把前者正在跑的动画（连同后面的补滚）
 * 一起取消，滚动停在半路；而跟卷只按"高度增量"推，这段缺口就一直留着。
 * 现在① 改成瞬时定位（用户要的就是"发完直接跳到底"），
 * ② 目标项还没进布局时**一帧一次瞬时重定位**（第 4 项起不再小段 scrollBy —— 那在长会话里
 * 就是一段肉眼可见的"从上往下滑"），直到它的下沿真的进视口。
 */
private suspend fun LazyListState.scrollToBottom(expectedItems: Int = 0) {
    // ① 瞬时定位到"数据里的最后一条"：不做长动画，就不给"按行跟卷"留掐断的时间窗
    val target = maxOf(layoutInfo.totalItemsCount, expectedItems) - 1
    if (target < 0) return
    // 第 3 项：用户反馈「输出内容超过窗口时，输出到最后一行时未完全跳到最底部」。
    // scrollToItem(target) 只把目标项的**顶部**对齐到视口顶端；当最后一条比一屏还高时，
    // 它的下沿仍在屏幕外，也就是"最后一行没贴到底"。这里给一个大 scrollOffset，
    // 让 LazyColumn 自己夹到真正的最底部（越界会被夹住，不会出错）。
    scrollToItem(target, scrollOffset = 100_000)

    // ② 补滚：新条目可能还没完成布局（刚入库时 layoutInfo 读出来是旧值），
    //    等几帧、必要时继续往下推，最后一条的下沿进视口就算到底。
    repeat(12) {
        val info = layoutInfo
        val lastIndex = maxOf(info.totalItemsCount, expectedItems) - 1
        val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        if (last != null) {
            val overflow = last.offset + last.size - info.viewportEndOffset
            if (overflow > 0) scrollBy(overflow.toFloat())
            return
        }
        // 目标项还没进布局：**再瞬时定位一次**，不要一小段一小段 scrollBy。
        // 第 4 项：用户明确不要"聊天记录从上往下滚"的动画 —— 长会话里连续 scrollBy(2000f)
        // 就是一段肉眼可见的滑行（十几帧、每帧两千像素）。改成一帧一次瞬时定位，
        // 落点直接到底，视觉上就只有"打开即在最底部"。
        kotlinx.coroutines.delay(16)
        scrollToItem(lastIndex.coerceAtLeast(0), scrollOffset = 100_000)
    }
}

// ============================================================================
//  推理失败提示卡片（本轮反馈：提示语格式优化）
// ============================================================================

/** 失败红：与编辑器里的失败红（0xFFEF9A9A）、输入框那颗停止键同一档 */
private val INFERENCE_ERROR_RED = Color(0xFFEF9A9A)

/**
 * 「推理失败」提示。三层：醒目标题 / 一句人话 / 更小更淡的技术细节。
 *
 * 视觉口径全部沿用 App 里已有的写法，没有自创风格：
 * - 卡片底 0xFF232338 + 1dp 细描边 = AppDataLerts 里统一弹窗外壳那一套；
 * - 标题前一条小竖条 = 弹窗外壳标题前那条紫色竖条的同一个写法（这里换成错误红）；
 * - 标题 13sp 半粗、正文 12sp 白 80%、细节 10sp 白 35% = 设置页 hint / 编辑器提示行的字号与透明度口径。
 *
 * 为什么不会引起跳动（前几轮反复修过的问题）：
 * 1. 它永远挂在 LazyColumn 的**最后一条**（失败消息就是最后一条），
 *    出现 / 消失不会顶到上面任何一条消息；
 * 2. 输入框在 LazyColumn 之外（列表是 weight(1f)），列表变高也不会推它；
 * 3. 细节行 minLines = maxLines = 2：原始异常再长再短，这块都恒定两行高，
 *    卡片高度不随内容变 → 不会时高时矮；
 * 4. 不做"展开详情"这种会改高度的交互，详情直接两行淡字（超出省略号）；
 * 5. 长按才出现的操作行沿用气泡那套 AnimatedVisibility（用户自己触发，和以前一致）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InferenceErrorNotice(
    humanMessage: String,
    rawDetail: String,
    showActions: Boolean,
    onLongPress: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF232338),
            border = BorderStroke(1.dp, INFERENCE_ERROR_RED.copy(alpha = 0.25f)),
            modifier = Modifier
                .fillMaxWidth()
                // 和气泡一样的交互：长按展开操作图标，点一下收起
                .combinedClickable(
                    onClick = { if (showActions) onLongPress() },
                    onLongClick = onLongPress
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 3.dp, height = 13.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(INFERENCE_ERROR_RED)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "推理失败",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = INFERENCE_ERROR_RED
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // 第二层：一句人话（连不上 / 超时 / 引擎退出 / 非 200 / 空回复 …）
                Text(
                    text = humanMessage,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 第三层：原始异常原文，小一号 + 更淡 + 恒定两行（高度不随内容变）
                Text(
                    text = "详情：$rawDetail",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = Color.White.copy(alpha = 0.35f),
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AnimatedVisibility(visible = showActions, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 3.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NoticeActionIcon(Icons.Outlined.ContentCopy, "复制", onCopy)
                NoticeActionIcon(Icons.Outlined.Refresh, "重新生成", onRegenerate, glyphScale = GLYPH_SCALE_REGEN)
                NoticeActionIcon(Icons.Outlined.DeleteOutline, "删除", onDelete, glyphScale = GLYPH_SCALE_DELETE)
            }
        }
    }
}

/**
 * 提示卡片下方的小图标：口径与 ChatComponents 里 MessageActionRow 完全一致
 * （同一个 ACTION_ICON_SIZE = 22dp、白色 55%、间距 18dp、圆形点击区）。
 *
 * 这里没有直接复用那个 Row：它是 ChatComponents 的 private 实现；
 * 样式参数照抄，不再另立一套。注意**没有**"朗读"——把报错念出来没有意义。
 */
@Composable
private fun NoticeActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    /** 第 2 项：和 ChatComponents.MessageActionRow 用同一组字形补偿倍数（刷新/删除偏小） */
    glyphScale: Float = 1f
) {
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = Color.White.copy(alpha = 0.55f),
        modifier = Modifier
            // 第 3 项：和 ChatComponents.MessageActionRow 共用同一个尺寸常量，
            // 两排"对话下方的小图标"永远一样大。
            .size(ACTION_ICON_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            // 第 2 项：scale 放链尾，只缩放到绘制、不动 48dp 触摸热区
            .scale(glyphScale)
    )
}
