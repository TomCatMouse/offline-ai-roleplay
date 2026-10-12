package com.example.roleplay

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.ai.gguf.GGUFAIService
import com.example.roleplay.ai.gguf.GGUFRuntime
import com.example.roleplay.ai.tts.TtsManager
import com.example.roleplay.data.AppSettings
import com.example.roleplay.data.database.RoleplayDatabase
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.Memory
import com.example.roleplay.data.repository.*
import com.example.roleplay.local.LocalEngineProvider
import com.example.roleplay.local.LocalEngineState
import com.example.roleplay.ui.components.BottomDock
import com.example.roleplay.ui.components.DockTab
import com.example.roleplay.ui.screens.*
import com.example.roleplay.viewmodel.CharacterViewModel
import com.example.roleplay.viewmodel.ChatViewModel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全局 UI 缩放系数。
 *
 * 为什么不去逐个改尺寸：整个 App 的排版都是用 dp / sp 写的，
 * 只要把 [LocalDensity] 的 density 乘上这个系数，**字号、间距、圆角、
 * 图标、头像会一起等比缩小**，而且不同机型上表现一致。
 *
 * 注意只缩放 density、不动 fontScale
 *   dp.toPx()  = dp * density
 *   sp.toPx()  = sp * density * fontScale
 * 所以降 density 会同时缩小控件与文字，而 fontScale 仍保留系统无障碍设置。
 *
 * 1.0 = 原始大小，0.85 = 整体缩小 15%。
 */
private const val UI_SCALE = 0.85f

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 真机包：启动就把 /storage/emulated/0/AI角色扮演/模型 建好并扫描本地 gguf。
        // 默认包里 LocalEngineProvider.engine 恒为 null —— 这一行是彻底的空操作。
        com.example.roleplay.local.LocalEngineProvider.engine?.attach(applicationContext)

        val db = RoleplayDatabase.getInstance(applicationContext)
        val charRepo = CharacterRepository(db.characterDao())
        val convRepo = ConversationRepository(db.conversationDao())
        val msgRepo = MessageRepository(db.messageDao())
        val memRepo = MemoryRepository(db.memoryDao())

        setContent {
            // 全局缩小：套在最外层，让所有界面的 dp/sp 一起生效
            val baseDensity = LocalDensity.current
            val scaledDensity = remember(baseDensity) {
                Density(
                    density = baseDensity.density * UI_SCALE,
                    fontScale = baseDensity.fontScale
                )
            }

            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                // 代码审查（反馈 #14）：这里原来是裸的 MaterialTheme{}，也就是**浅色**配色。
                // 全 App 的界面都自己写死了深色，唯一"漏"出来的是那些用 M3 默认取色的组件 ——
                // DropdownMenu 就是典型：它取 colorScheme.surface，于是下拉菜单变成浅灰底，
                // 配上菜单里的浅色文字就成了"新建剧情线看不清"（反馈 #9 / #10 的根因）。
                // 换成深色配色后，菜单/涟漪/指示器等默认取色全部与深色界面一致。
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val appSettings = remember { AppSettings.get(applicationContext) }
                    val tts = remember { TtsManager(applicationContext) }
                    val ggufRuntime = remember { GGUFRuntime(applicationContext, appSettings) }
                    val aiService = remember(ggufRuntime, appSettings) {
                        GGUFAIService(ggufRuntime, appSettings)
                    }

                    val characterViewModel = remember(charRepo, msgRepo, convRepo, memRepo) {
                        CharacterViewModel(charRepo, msgRepo, convRepo, memRepo)
                    }
                    val chatViewModel = remember(convRepo, msgRepo, memRepo, charRepo, aiService, appSettings) {
                        ChatViewModel(convRepo, msgRepo, memRepo, charRepo, aiService, appSettings)
                    }

                    var isModelLoaded by remember { mutableStateOf(false) }
                    var isLoadingModel by remember { mutableStateOf(true) }
                    var loadingMessage by remember { mutableStateOf("正在连接 AI 模型…") }
                    var modelStatusText by remember { mutableStateOf("") }
                    var retryTick by remember { mutableIntStateOf(0) }
                    var isReconnecting by remember { mutableStateOf(false) }
                    // 静默重连标记：真机卡引擎起来后自动重连时不铺全屏遮罩（消除闪屏）
                    var silentRetry by remember { mutableStateOf(false) }

                    // P7：真机包要观察本地引擎的运行状态，让「推理服务状态」与引擎实时同步。
                    // 默认包 LocalEngineProvider.engine 恒为 null → 用空快照流顶着，行为不变。
                    val engineStateFlow = remember {
                        com.example.roleplay.local.LocalEngineProvider.engine?.state
                            ?: kotlinx.coroutines.flow.MutableStateFlow(LocalEngineState())
                    }
                    val engineState by engineStateFlow.collectAsState()

                    LaunchedEffect(ggufRuntime, retryTick) {
                        // 静默重连（真机卡自动重连）不弹全屏加载页，否则屏幕会闪一下
                        isLoadingModel = !silentRetry
                        isReconnecting = retryTick > 0  // P6：点过「重试连接」才显示「连接中...」
                        loadingMessage = "正在连接 AI 模型…"
                        modelStatusText = ""
                        Log.i(TAG, "连接推理服务…")
                        val result = ggufRuntime.loadModel()
                        if (result.isSuccess) {
                            isModelLoaded = true
                            loadingMessage = "AI 模型连接完成"
                        } else {
                            isModelLoaded = false
                            loadingMessage = "推理服务连接失败"
                            modelStatusText = result.exceptionOrNull()?.message ?: "未知错误"
                        }
                        isLoadingModel = false
                        isReconnecting = false  // 连接结束（成功或失败）按钮都翻回原样
                        silentRetry = false
                    }

                    // 建议 #2：断线自动重连。
                    // 连不上/中途掉线后每 30 秒试一次（PC 重启 llama-server、Wi-Fi 抖动），
                    // 成功后 UI 自动从"失败"翻回"已连接"，用户不用手动点重试。
                    LaunchedEffect(ggufRuntime) {
                        while (isActive) {
                            kotlinx.coroutines.delay(30_000)
                            if (isModelLoaded || isLoadingModel) {
                                // 已连接：顺手同步一次服务端真实模型（PC 端重启/换模型后
                                // 设置页两边显示的模型不会各说各话，见 #3）
                                ggufRuntime.refreshActiveModel()
                                continue
                            }
                            Log.i(TAG, "服务未连接，尝试自动重连…")
                            val r = ggufRuntime.loadModel()
                            if (r.isSuccess) {
                                Log.i(TAG, "自动重连成功")
                                isModelLoaded = true
                                loadingMessage = "AI 模型已自动重连"
                                modelStatusText = ""
                            }
                        }
                    }
                    // P7：推理服务状态与本地引擎实时同步（默认包是空操作）
                    //  引擎停掉 → 状态立刻翻「未连接」（不用等 30 秒的自动重连才发现）
                    //  引擎刚起来 → 马上重连一次，状态翻「已连接 - 模型 · 引擎」
                    var lastEngineRunning by remember { mutableStateOf<Boolean?>(null) }
                    LaunchedEffect(engineState.running) {
                        when {
                            engineState.running && lastEngineRunning == false -> {
                                Log.i(TAG, "本地引擎启动 → 重新连接推理服务")
                                // 第 2 点：引擎起来这条重连必须走静默通道 —— 先置 silentRetry，
                                // 让下面 LaunchedEffect 里 isLoadingModel = !silentRetry = false，
                                // 就不会触发 L360 的全屏「正在连接 AI 模型…」遮罩早退（return）：
                                // 大闪屏没了；设置页也不会被卸载重入 → rememberScrollState 不复位 → 不跳顶。
                                silentRetry = true
                                retryTick++
                            }
                            !engineState.running && lastEngineRunning == true -> {
                                Log.i(TAG, "本地引擎停止 → 状态置为未连接")
                                isModelLoaded = false
                            }
                            else -> Unit
                        }
                        lastEngineRunning = engineState.running
                    }
                    RoleplayApp(
                        characterViewModel = characterViewModel,
                        chatViewModel = chatViewModel,
                        ggufRuntime = ggufRuntime,
                        appSettings = appSettings,
                        tts = tts,
                        isModelLoaded = isModelLoaded,
                        isLoadingModel = isLoadingModel,
                        loadingMessage = loadingMessage,
                        modelStatusText = modelStatusText,
                        // G16：真机包显示本地引擎的后端（CPU / GPU · Adreno / NPU · Hexagon），
                        // 默认包恒为空串，状态显示与以前一致
                        engineLabel = if (com.example.roleplay.local.LocalEngineProvider.isPhysBuild
                            && engineState.running
                        ) engineState.device.label else "",
                        // 本轮：把引擎运行状态原样传给设置页，给「上下文长度」加"需重启引擎"提示。
                        // 默认包 engineState 恒为初始快照（running=false），不显示提示。
                        engineRunning = engineState.running,
                        localModelName = if (com.example.roleplay.local.LocalEngineProvider.isPhysBuild
                            && engineState.running
                        ) engineState.activeModel.takeIf { it.isNotBlank() } else null,
                        isReconnecting = isReconnecting,
                        // P8：正在生成回复的角色 id —— 传 StateFlow 本身而不是 `.value` 快照。
                        // 旧代码 `chatViewModel.generatingCharacterId.collectAsState().value` 只在
                        // MainActivity 重组那一刻取一次值，之后 ChatViewModel 里
                        // `_generatingCharacterId` 从 null → 角色A → null 的变化 MainActivity 根本
                        // 感知不到（它不在 Compose 收集图里）→ 首页角色卡拿到的是过期值，
                        // 于是"和 A 角色聊天时，B/C 角色也亮思考中"（或该亮的不亮）。
                        // 现在 RoleplayApp 内部正常 collectAsState() 收集（和它内部 L249-254 那
                        // 一批 characters/favorites/messages 的写法一致），角色卡永远拿到实时值。
                        generatingCharacterId = chatViewModel.generatingCharacterId,
                        onRetryConnect = {
                            retryTick++
                            isLoadingModel = true
                        },
                        // 反馈「启动推理引擎以后屏幕会闪一下」：
                        // 真机卡在引擎起来后自动重连时走这条**静默**通道 ——
                        // 只重连、不铺全屏「正在连接 AI 模型…」遮罩，状态卡自己翻绿就行。
                        onSilentReconnect = {
                            silentRetry = true
                            retryTick++
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RoleplayApp(
    characterViewModel: CharacterViewModel,
    chatViewModel: ChatViewModel,
    ggufRuntime: GGUFRuntime,
    appSettings: AppSettings,
    tts: TtsManager,
    isModelLoaded: Boolean,
    isLoadingModel: Boolean,
    loadingMessage: String,
    modelStatusText: String,
    /** G16：真机包的本地推理引擎后端（CPU / GPU · Adreno / NPU · Hexagon），默认包为空串 */
    engineLabel: String = "",
    /**
     * 真机包：本地推理引擎此刻是否在运行（默认包的 engineState 是空快照流，恒为 false）。
     * 只用于设置页「上下文长度」那行「改完需要重启推理引擎才生效」的提示。
     */
    engineRunning: Boolean = false,
    /** P1：真机引擎正在跑的本地模型名；非空时状态卡显示它（与本地推理卡保持一致） */
    localModelName: String? = null,
    /** P6：正在执行「重试连接」，状态卡按钮显示「连接中...」 */
    isReconnecting: Boolean = false,
    /**
     * P8：正在生成回复的角色 id 的 **StateFlow**（卡片「思考中」只亮这一个）。
     * 传流本身而不是 `String?` 快照：MainActivity 只重组一次的话，快照值会把
     * ChatViewModel 里后续 `null → 角色A → null` 的变化全部挡在外面，首页角色卡
     * 拿到的永远是过期值（表现 = 和 A 角色聊天时 B/C 也亮思考中）。
     * 在 [RoleplayApp] 体内正常 `collectAsState()` 收集后传给 HomeScreen。
     */
    generatingCharacterId: kotlinx.coroutines.flow.StateFlow<String?>,
    onRetryConnect: () -> Unit,
    /** 静默重连（真机卡引擎启动后自动重连用）：只重连、不弹全屏加载遮罩 */
    onSilentReconnect: () -> Unit = onRetryConnect
) {
    val characters by characterViewModel.allCharacters.collectAsState()
    val favorites by characterViewModel.favorites.collectAsState()
    val currentChar by chatViewModel.character.collectAsState()
    val messages by chatViewModel.messages.collectAsState()
    val isGenerating by chatViewModel.isGenerating.collectAsState()
    val streamingMap by chatViewModel.streamingContent.collectAsState()
    // 本轮：思考计时起点（生成开始那一刻），随生成周期存在 ViewModel 里，
    // 聊天页退出重进后「已等待 Xs」接着算、不重头
    val generatingSinceMs by chatViewModel.generatingSinceMs.collectAsState()
    // 第 23 项：聊天输入草稿 —— 存 ViewModel，返回重进继续保留
    val chatInput by chatViewModel.chatInput.collectAsState()
    // 第 21 项：朗读播放状态 + 正在读的那条原文（气泡图标按它三态切换）
    val ttsPlayState by tts.playState.collectAsState()
    val ttsActiveText by tts.activeText.collectAsState()
    // P8：正在生成回复的角色 id —— 在 composable 体内正常收集（响应式），
    // 传给 HomeScreen 的是**实时值**（不是 MainActivity 重组那一刻的快照），
    // 于是"和 A 角色聊天时 B/C 也亮思考中"的过期值 bug 修掉。
    val generatingCharacterId by generatingCharacterId.collectAsState()
    // item1：对话历史 / 首页「对话」统计只认**有聊天记录**的会话
    //（空剧情线既不出现在历史里，也不让统计数字 +1）
    val conversationSummaries by chatViewModel.allConversationsWithMessages.collectAsState()
    val characterStorylines by chatViewModel.characterConversations.collectAsState()
    val activeModelId by ggufRuntime.activeModelId.collectAsState()
    val settingsState by appSettings.state.collectAsState()

    val convId = chatViewModel.currentConversationId.collectAsState().value
    val streamingContent = streamingMap.getOrDefault(convId ?: "", "")

    var currentScreen by remember { mutableStateOf<Screen>(Screen.HOME) }
    /**
     * 编辑中的角色 **id**。
     *
     * 需求 #5：保存角色卡后必须能看到**最新**已落库的资料。旧版直接持有
     * `Character` 对象（首页那次的快照），保存后再进编辑页拿到的还是旧数据。
     * 现在只存 id，进编辑页时从实时刷新的 [characters] 里取最新行，
     * 保存→返回→再编辑，资料永远是库里最新的。
     */
    var editingCharacterId by remember { mutableStateOf<String?>(null) }
    /**
     * 编辑器该退回哪儿。
     *
     * 反馈 #8：新建角色后点返回必须回到「角色」界面。
     * 旧写法用 `if (currentChar != null) CHAT else HOME` 判断 —— 但 currentChar 是
     * **聊天页当前角色**，只要之前聊过天它就非空，于是"新建角色 → 返回"会莫名其妙
     * 跳进上一个角色的聊天页。现在改成记录进入编辑器时的来源页面。
     */
    var editorReturnScreen by remember { mutableStateOf<Screen>(Screen.HOME) }
    /**
     * item2：聊天页该退回哪儿。
     *
     * 从**对话历史**点进某条剧情线时，按返回要回历史列表，而不是一路弹回首页
     * （用户在哪一层进来就退回哪一层，和 [editorReturnScreen] 同一套思路）。
     */
    var chatReturnScreen by remember { mutableStateOf<Screen>(Screen.HOME) }
    /** 首页是否只看收藏 */
    var favoritesOnly by remember { mutableStateOf(false) }
    /** 待确认删除的角色（卡片「删除」→ 确认弹窗 → 级联清理） */
    var pendingDeleteCharacter by remember { mutableStateOf<Character?>(null) }
    /** 反馈 #11：正在查看详情的角色（null = 不显示弹窗） */
    var detailCharacter by remember { mutableStateOf<Character?>(null) }

    // 建议 #3：长期记忆页 —— 存"当前查看哪个角色的记忆"
    var memoryCharacterId by remember { mutableStateOf<String?>(null) }
    var memoryList by remember { mutableStateOf<List<Memory>>(emptyList()) }
    // 反馈 #3：TTS 不可用时点「朗读」弹出的提示文案（null = 不显示）
    var ttsUnavailableText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(memoryCharacterId, chatViewModel) {
        val id = memoryCharacterId ?: return@LaunchedEffect
        chatViewModel.memoriesFor(id).collect { memoryList = it }
    }

    // M6：Snackbar/SnackbarHostState 已移除，提示统一走 appHint 顶部药丸

    LaunchedEffect(Unit) { characterViewModel.seedDemoCharacters() }

    // M6：提示消息统一走顶部药丸（TopHintHost，和复制/删除剧情线的提示同一套样式），
    // 不再用底部 Snackbar。
    var appHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chatViewModel) {
        chatViewModel.toast.collect { appHint = it }
    }

    // TTS：反馈 #5 起不再"收到回复自动朗读"（设置里的开关已删除），
    // 朗读完全由消息气泡下方的「朗读」图标手动触发 —— 所以这里不再收集 speakRe

    // 当前实际在跑的模型名（以服务端为准，不是设置里选的）
    // 反馈 #11：模型清单可能包含 PC 端 models/gguf 自动扫到的模型，
    // 所以优先用运行时清单，再退回内置注册表。
    val runtimeModelInfos by ggufRuntime.modelInfos.collectAsState()
    val activeModelName = remember(activeModelId, settingsState.modelId, runtimeModelInfos) {
        val wantId = activeModelId.ifBlank { settingsState.modelId }
        runtimeModelInfos.firstOrNull { it.id == wantId }?.name
            ?: com.example.roleplay.ai.gguf.ModelRegistry.models
                .firstOrNull { it.id == wantId }?.name
            ?: com.example.roleplay.ai.gguf.ModelRegistry.defaultModel.name
    }

    if (isLoadingModel) {
        // 注意：这个加载遮罩以前没画背景，透出的是白色窗口底 → 每次「重试连接」
        // 「启动引擎」都会白闪一下。这里显式铺上深色底，闪白屏就没了。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = loadingMessage, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                if (modelStatusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = modelStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    // 反馈 #8：系统返回键（手势/三键导航）逐级回退，别一按就退出 App。
    // 首页再按才交给系统（退出）。
    BackHandler(enabled = currentScreen != Screen.HOME) {
        currentScreen = when (currentScreen) {
            Screen.EDITOR -> editorReturnScreen
            Screen.MEMORY -> Screen.CHAT
            // item2：从历史进来的就回历史，从首页进来的才回首页
            Screen.CHAT -> chatReturnScreen
            Screen.HISTORY, Screen.SETTINGS -> Screen.HOME
            else -> Screen.HOME
        }
    }

    // 需求（本轮反馈）：dock 栏固定在底部 —— 键盘弹出时也不再收起。
    // 旧实现用 screenHeightDp 回落猜键盘、键盘一弹就把 dock 整条藏掉；用户反馈 dock 应
    // 一直钉在底部。现在两个主页面恒渲染 dock：Scaffold 底部即「可视区底部」，
    // adjustResize 下键盘弹出时正好贴在键盘上沿，从不会悬到半空或消失。

    Scaffold(
        containerColor = Color(0xFF0D0D1A),
        // M6：全局提示统一走顶部药丸（TopHintHost），底部 Snackbar 移除
        // dock 只在两个主页面出现；聊天/编辑等次级页面占满全屏。
        // 「＋ 新建角色」胶囊在 dock 正中间（见 BottomDock），不再用悬浮 FAB。
        bottomBar = {
            if (currentScreen == Screen.HOME || currentScreen == Screen.SETTINGS) {
                BottomDock(
                    current = if (currentScreen == Screen.SETTINGS) DockTab.SETTINGS else DockTab.CHARACTERS,
                    onSelect = { tab ->
                        currentScreen = when (tab) {
                            DockTab.CHARACTERS -> Screen.HOME
                            DockTab.SETTINGS -> Screen.SETTINGS
                        }
                    },
                    onNewCharacter = {
                        editingCharacterId = null
                        editorReturnScreen = Screen.HOME
                        currentScreen = Screen.EDITOR
                    }
                )
            }
        }
    ) { paddingValues ->
        // ⚠️ 不能把 paddingValues 扔掉（旧版写的是 `{ _ ->`）：
        // 那样内容区会铺满整个屏幕、底部被 dock 盖住（80dp），
        // 首页列表最后一行的按钮就点不到了。HOME / SETTINGS 各自垫高。
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentScreen) {
            Screen.HOME -> HomeScreen(
                characters = if (favoritesOnly) favorites else characters,
                favoritesOnly = favoritesOnly,
                onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },
                onCharacterClick = { c ->
                    chatViewModel.startChat(c.id)
                    // item2：从首页进的聊天页，返回键回首页
                    chatReturnScreen = Screen.HOME
                    currentScreen = Screen.CHAT
                },
                onEditCharacter = { c ->
                    editingCharacterId = c.id
                    editorReturnScreen = Screen.HOME
                    currentScreen = Screen.EDITOR
                },
                onDeleteCharacter = { pendingDeleteCharacter = it },
                // 反馈 #11：卡片上的"查看详情"简笔符号
                onDetailCharacter = { detailCharacter = it },
                onHistoryClick = { currentScreen = Screen.HISTORY },
                onFavoriteToggle = { c -> characterViewModel.toggleFavorite(c) },
                conversationCount = conversationSummaries.size,
                favoriteCount = favorites.size,
                generatingCharacterId = generatingCharacterId,
                // 垫高 dock：内容区不被底部栏盖住（HomeScreen 内部会把 modifier 套到自己的根 Column）
                modifier = Modifier.padding(paddingValues)
            )

            Screen.CHAT -> {
                val character = currentChar
                if (character == null) {
                    currentScreen = Screen.HOME
                } else {
                    ChatScreen(
                        character = character,
                        messages = messages,
                        isGenerating = isGenerating,
                        streamingContent = streamingContent,
                        generatingSinceMs = generatingSinceMs,
                        inputText = chatInput,
                        onInputTextChange = { chatViewModel.setChatInput(it) },
                        onBack = { currentScreen = chatReturnScreen },
                        onSend = { content -> chatViewModel.sendMessage(content) },
                        onStop = { chatViewModel.stopGeneration() },
                        // 反馈 #3：某条回复的"重新生成" = 重发它对应的那句用户输入
                        onResendRegenerate = { id -> chatViewModel.regenerateReply(id) },
                        // 反馈 #3：朗读。TTS 不可用 → 弹出与设置页一致的提示
                        // 返回值告诉 ChatScreen 要不要弹"正在朗读…"的顶部小提示（反馈 #12）
                        // 第 21 项：同一个入口做三态 toggle —— 朗读 → 暂停 → 继续；
                        // 暂停时返回 false（不弹"正在朗读…"），继续/新读返回 true。
                        onSpeak = { text ->
                            if (tts.ready.value) {
                                tts.toggle(text, settingsState.ttsSpeed, settingsState.ttsPitch)
                                tts.playState.value == com.example.roleplay.ai.tts.TtsPlayState.SPEAKING
                            } else {
                                ttsUnavailableText = TtsManager.UNAVAILABLE_HINT
                                false
                            }
                        },
                        // 第 21 项：朗读状态 + 正在读的原文 → 决定哪条气泡显示暂停/继续图标
                        speakState = ttsPlayState,
                        speakActiveText = ttsActiveText,
                        // 反馈 #4：编辑后重答
                        onEditResend = { id, text -> chatViewModel.editAndResend(id, text) },
                        onDeleteMessage = { id -> chatViewModel.deleteMessage(id) },
                        onEditCharacter = {
                            editingCharacterId = character.id
                            editorReturnScreen = Screen.CHAT
                            currentScreen = Screen.EDITOR
                        },
                        onOpenMemories = {
                            memoryCharacterId = character.id
                            currentScreen = Screen.MEMORY
                        },
                        onNewConversation = { chatViewModel.newConversation() },
                        onClearHistory = { chatViewModel.clearHistory() },
                        storylines = characterStorylines,
                        currentStorylineId = convId,
                        onSwitchStoryline = { id -> chatViewModel.openConversation(id) },
                        // M8：剧情线改名（空串 = 恢复默认名称）
                        onRenameStoryline = { id, title -> chatViewModel.renameConversation(id, title) },
                        // 本轮需求 #5：设置页里改的昵称 / 头像，聊天页实时生效
                        userName = settingsState.userName,
                        userAvatarPath = settingsState.userAvatarPath
                    )
                }
            }

            Screen.EDITOR -> {
                // 从**实时**角色列表取最新资料（需求 #5：编辑时带出已有/已保存的数据）
                val editingChar = characters.firstOrNull { it.id == editingCharacterId }
                if (editingCharacterId != null && editingChar == null) {
                    // 要编辑的角色已被删除，回首页避免打开空编辑器
                    currentScreen = Screen.HOME
                } else {
                    CharacterEditorScreen(
                        character = editingChar,
                        // #8：谁打开的编辑器就退回谁（新建角色 → 回「角色」界面）
                        onBack = { currentScreen = editorReturnScreen },
                        onSave = { c ->
                            characterViewModel.saveCharacter(c)
                            // M6：保存提示走全局顶部药丸（和其它提示统一）
                            appHint = "已保存「${c.name}」"
                            currentScreen = Screen.HOME
                        },
                        // 建议 #7：「✨ 帮我写 / 🔄 重新生成」—— 用当前 PC 上的模型现写文案
                        // 反馈 #7：原来四个字段共用一句泛泛的系统提示，模型经常"串场"
                        //（写性格描述时把背景故事一起写了，或干脆回一句问候）。
                        // 现在系统提示按字段定制 + 明确禁止写别的字段，输出再做一次清洗。
                        aiFiller = { kind, charName, tagStr, regenerate ->
                            val fieldName = aiFieldName(kind)
                            val sys = buildString {
                                append("你是角色扮演卡片撰稿人，本次只负责写「")
                                append(fieldName)
                                append("」这一个字段。硬性要求：")
                                append("① 只输出该字段本身的内容，不要输出字段名或小标题；")
                                append("② 绝不要顺带写其他字段（说话风格/性格描述/背景故事/开场白）的内容；")
                                append("③ 不要解释、不要客套、不要用引号包裹、不要 Markdown 标记；")
                                append("④ 全部使用简体中文；")
                                // 反馈 #4：一律不带人名。角色名可能还没填、也可能和用户心里的
                                // 设定不一致，模型自己编一个名字只会让人对不上号。
                                append("⑤ 全文不得出现任何人名（包括你自己起的名字），")
                                append("要指代这个角色时用「她」「他」或「这个角色」，或者直接省略主语；")
                                append("也不要在开头写「某某：」这样的名字前缀。")
                                if (kind == "greeting") {
                                    append("⑤ 开场白是角色对用户说的第一人称台词，必须含括号动作描写。")
                                } else {
                                    append("⑤ 除开场白外均为第三人称设定描述，不要写成对话。")
                                }
                                if (regenerate) append("⑥ 这次必须换一个完全不同的角度、意象与句式，不要与上一版重复。")
                            }
                            val tagPart = if (tagStr.isBlank()) "（用户还没填标签）" else tagStr
                            val user = when (kind) {
                                "speakingStyle" ->
                                    "标签：$tagPart。用一句话（10-25 字）概括这个角色的说话风格：" +
                                        "语气、语速、口头禅，以及她如何称呼用户。不要出现任何人名。"
                                "personality" ->
                                    "标签：$tagPart。写性格描述（80-150 字）：" +
                                        "具体列出 3-4 个性格侧面、待人接物的方式、明显的优点与缺点。" +
                                        "要具体到可扮演，不要堆砌空泛形容词。不要出现任何人名。"
                                "backstory" ->
                                    "标签：$tagPart。写背景故事（100-200 字）：" +
                                        "出身来历、关键经历、当前身份与处境。只讲经历，不要写性格评价。" +
                                        // 第 11 项：以前这里写死「用『她』指代这个角色」，
                                        // 再被 unifyThirdPerson 统一成 TA —— 现在不刻意限制人称，
                                        // 由模型自行判断（他/她/TA/直接省略主语都行），只保留"不出现人名"。
                                        "指代这个角色时人称自行判断（他/她/TA 或直接省略主语都可以），" +
                                        "不要出现任何人名。"
                                "greeting" ->
                                    "标签：$tagPart。写她与用户初次见面的开场白（30-80 字）：" +
                                        "角色的第一人称台词，带括号动作描写，体现性格与说话风格。" +
                                        "不要在台词里报自己的名字。"
                                else -> "标签：$tagPart。写一段简短的角色设定，不要出现任何人名。"
                            }
                            val maxTokens = when (kind) {
                                "speakingStyle" -> 120
                                "personality" -> 320
                                "backstory" -> 420
                                "greeting" -> 220
                                else -> 300
                            }
                            val raw = ggufRuntime.generateResponse(
                                systemPrompt = sys,
                                userMessage = user,
                                maxTokens = maxTokens,
                                // 重抽时提高温度，让它真的给出不同版本
                                temperature = if (regenerate) 1.0f else 0.75f,
                                topP = 0.9f,
                                repeatPenalty = 1.1f
                            ).getOrDefault("")
                            cleanAiField(kind, raw, charName)
                        },
                        // 新增：「✨ 一键帮我写全部」—— 一次请求写全 4 个字段。
                        //
                        // 为什么不是「4 个并发请求」：
                        //   真机引擎 `-np 1`（PhysLocalEngine.kt:316），llama.cpp 只有 1 个 slot，
                        //   并发只会排队甚至撞 slot 忙；串行点 4 次 = 4 次完整前向（每次 30~90 秒），
                        //   一次请求写 4 段 ≈ 1 次前向，总耗时约 1/4，而且 4 段互相呼应。
                        //
                        // 为什么提示词不要求 JSON：
                        //   4B 量化小模型输出坏 JSON 的概率很高（少引号、多逗号、字符串里带换行），
                        //   一旦 JSON.parse 失败就整批全丢。改成固定中文小标题分段，容错成本极低：
                        //   掉一个标题只丢一个字段，其余照样能用（见 parseAiFieldsAll）。
                        aiFillerAll = { charName, tagStr, regenerateAll ->
                            val sys = buildString {
                                append("你是角色扮演卡片撰稿人。本次要一次性写出一整张角色卡的四个字段。硬性要求：")
                                append("① 必须严格按下面四个小标题分段输出，标题独占一行、顺序固定、四个都要有：\n")
                                append("【说话风格】\n【性格描述】\n【背景故事】\n【开场白】\n")
                                append("② 除了这四个小标题和它们各自的内容，不要输出任何解释、总结、前后缀、编号或 Markdown 标记；")
                                append("③ 四段写的是同一个角色，内容要互相呼应、不要自相矛盾；")
                                append("④ 全部使用简体中文；")
                                // 和单字段路径同一条规矩（反馈 #4）：一律不带人名，避免"生成的名字对不上号"
                                append("⑤ 全文不得出现任何人名（包括你自己起的名字），")
                                append("要指代这个角色时用「她」「他」或直接省略主语，也不要在段首写「某某：」这样的名字前缀；")
                                append("⑥ 除开场白外都是第三人称设定描述；开场白是角色对用户说的第一人称台词，必须含括号动作描写。")
                                if (regenerateAll) append("⑦ 这次必须换一套完全不同的角度、意象与句式，四段都不要与上一版重复。")
                            }
                            val tagPart = if (tagStr.isBlank()) "（用户还没填标签）" else tagStr
                            val user = buildString {
                                append("标签：")
                                append(tagPart)
                                append("。请一次写完这张角色卡的四个字段，每段的具体要求：\n")
                                // 注意：这里的"要求行"故意**不写成【标题】的样子** ——
                                // 否则小模型很容易把这些要求原文当成正文抄进字段里。
                                // 统一用"字段名：要求"的写法，就算模型抄了，cleanAiField
                                // 也会把行首的"说话风格：/性格描述："这类前缀剥掉。
                                append("说话风格：一句话（10-25 字）概括语气、语速、口头禅，以及她如何称呼用户。\n")
                                append("性格描述：80-150 字，具体列出 3-4 个性格侧面、待人接物的方式、明显的优点与缺点，要具体到可扮演，不要堆砌空泛形容词。\n")
                                append("背景故事：100-200 字，出身来历、关键经历、当前身份与处境，只讲经历不写性格评价，人称自行判断（他/她/TA 均可）。\n")
                                append("开场白：30-80 字，她的第一人称台词，带括号动作描写，体现性格与说话风格，不要报自己的名字。\n")
                                if (regenerateAll) append("四个字段都要换新版本，不要与上一次的内容相似。\n")
                                append("严格按【说话风格】【性格描述】【背景故事】【开场白】这四个标题分段输出，标题独占一行；")
                                append("现在直接输出四段内容，不要任何开场白：")
                            }
                            val raw = ggufRuntime.generateResponse(
                                systemPrompt = sys,
                                userMessage = user,
                                // 四段正文加起来约 450~500 字，加上小标题与换行，800 已是一倍半余量。
                                // 不宜再大：真机引擎是 `-c 2048`（PhysLocalEngine.kt:92），
                                // 提示词本身要占几百 token，输出配额给太大会把上下文顶满，
                                // 模型会中途截断（截断也只影响最后一段，解析侧照常容错）。
                                maxTokens = 800,
                                // 重抽时提高温度，让它真的给出不同版本
                                temperature = if (regenerateAll) 1.0f else 0.75f,
                                topP = 0.9f,
                                repeatPenalty = 1.1f
                            ).getOrDefault("")
                            val parsed = parseAiFieldsAll(raw, charName)
                            if (parsed.isEmpty()) {
                                Log.w(LOG_TAG, "一键生成：未解析出任何字段，原文长度=${raw.length}")
                            } else {
                                Log.i(LOG_TAG, "一键生成：解析出 ${parsed.keys}（原文长度=${raw.length}）")
                            }
                            parsed
                        }
                    )
                }
            }

            Screen.MEMORY -> {
                val memChar = characters.firstOrNull { it.id == memoryCharacterId }
                if (memChar == null) {
                    currentScreen = Screen.HOME
                } else {
                    MemoryScreen(
                        character = memChar,
                        memories = memoryList,
                        onUpdate = { m, c -> chatViewModel.updateMemory(m, c) },
                        onDelete = { m -> chatViewModel.deleteMemory(m) },
                        onBack = { currentScreen = Screen.CHAT }
                    )
                }
            }

            Screen.HISTORY -> ChatHistoryScreen(
                conversations = conversationSummaries,
                characters = characters,
                onSelectChat = { summary ->
                    characters.firstOrNull { c -> c.id == summary.characterId }?.let { c ->
                        // 直接钉死"角色 + 用户点选的那条剧情线"，
                        // 避免 startChat 按"最后查看"重新解析把用户的选择顶掉（需求 #8）
                        chatViewModel.openStoryline(c.id, summary.id)
                    }
                    // item2：从历史进来的，返回键要回历史列表（不再一路弹回首页）
                    chatReturnScreen = Screen.HISTORY
                    currentScreen = Screen.CHAT
                },
                // M3：删完的提示走历史页自己的顶部药丸（和复制弹窗一致），
                // 所以这里关掉 VM 的底部 Snackbar，避免双重提示
                onDeleteChat = { summary -> chatViewModel.deleteConversation(summary.id, showToast = false) },
                onBack = { currentScreen = Screen.HOME }
            )

            Screen.SETTINGS -> SettingsScreen(
                appSettings = appSettings,
                ggufRuntime = ggufRuntime,
                characterViewModel = characterViewModel,
                chatViewModel = chatViewModel,
                isModelLoaded = isModelLoaded,
                // P1：真机引擎跑着时，模型名以本地引擎为准（和「真机本地推理」卡显示的一致）
                activeModelName = localModelName ?: activeModelName,
                engineLabel = engineLabel,
                // 引擎跑着时，设置页的「上下文长度」会加一行「需重启引擎才生效」
                isEngineRunning = engineRunning,
                isReconnecting = isReconnecting,
                onRetryConnect = onRetryConnect,
                onSilentReconnect = onSilentReconnect,
                onBack = { currentScreen = Screen.HOME },
                // 垫高 dock，同 HOME
                modifier = Modifier.padding(paddingValues)
            )
            }

            // M6：全局顶部药丸——所有 VM toast / showToast 类提示统一走这里，
            // 和对话内复制、历史页删除/复制的提示是同一套样式
            //
            // 本轮 item 2：药丸要落在**顶栏下面**（原来 top 只有 4dp，贴在窗口最顶上，
            // 把对话页的角色名、各页标题都糊住了）。实测：外层内容区比顶栏顶边高 9dp，
            // 顶栏（M3 TopAppBar）本身 54dp → 顶栏底边在内容区 63dp 处，
            // 再加 6dp 呼吸 = 69dp，正好和对话页自己那颗药丸
            //（它在自己 Scaffold 的 TopAppBar 下 6dp）落在同一条线上。
            com.example.roleplay.ui.components.TopHintHost(
                hint = appHint,
                onDismissed = { appHint = null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 69.dp)
            )
        }

        // 删除角色确认（统一弹窗样式，需求 #5）
        pendingDeleteCharacter?.let { c ->
            com.example.roleplay.ui.components.AppConfirmDialog(
                title = "删除角色",
                text = "删除「${c.name}」及其全部对话与长期记忆？",
                confirmText = "删除",
                confirmColor = Color(0xFFE53935),
                onConfirm = {
                    characterViewModel.deleteCharacterCascade(c)
                    pendingDeleteCharacter = null
                    chatViewModel.showToast("已删除「${c.name}」")
                },
                onDismiss = { pendingDeleteCharacter = null }
            )
        }

        // 反馈 #11：角色卡片「查看详情」
        detailCharacter?.let { c ->
            // 用实时列表里的最新资料（可能刚编辑过），找不到就退回点开时的那份
            val latest = characters.firstOrNull { it.id == c.id } ?: c
            com.example.roleplay.ui.components.CharacterDetailDialog(
                character = latest,
                onDismiss = { detailCharacter = null }
            )
        }

        // 反馈 #3：点了「朗读」但系统没有可用 TTS 引擎 → 给出设置页同款提示
        ttsUnavailableText?.let { hint ->
            com.example.roleplay.ui.components.AppConfirmDialog(
                title = "无法朗读",
                text = hint,
                confirmText = "知道了",
                cancelText = null,
                onConfirm = { ttsUnavailableText = null },
                onDismiss = { ttsUnavailableText = null }
            )
        }
    }
}

enum class Screen { HOME, CHAT, HISTORY, SETTINGS, EDITOR, MEMORY }

/** 反馈 #7：「帮我写」的字段中文名 */
private fun aiFieldName(kind: String): String = when (kind) {
    "speakingStyle" -> "说话风格"
    "personality" -> "性格描述"
    "backstory" -> "背景故事"
    "greeting" -> "开场白"
    else -> "角色设定"
}

/**
 * 反馈 #7：「帮我写」的结果清洗 —— 保证写进字段的内容真的只属于这个字段。
 *
 * 小模型常见的跑偏，这里逐一收拾：
 *  1. 先来一行字段名（"性格描述："、"**背景故事**"）再写正文 → 去掉前缀
 *  2. 用引号把整段包起来 → 去掉引号
 *  3. 说话风格写成一大段（应该是一句话）→ 只取第一行并限长
 *  4. 反馈 #4：冒出人名（"雪村由纪自幼…"、"小明：…"）→ 抹掉
 *
 * @param charName 用户填的角色名。填了的话，正文里出现同名字样会被换成「她」，
 *                 避免"生成的名字和实际角色名对不上"；没填就只做通用清洗。
 */
private fun cleanAiField(kind: String, raw: String, charName: String = ""): String {
    var t = raw.trim()
    val labels = when (kind) {
        "speakingStyle" -> listOf("说话风格", "说话方式", "语言风格")
        "personality" -> listOf("性格描述", "性格特点", "性格")
        "backstory" -> listOf("背景故事", "角色背景", "背景")
        "greeting" -> listOf("开场白", "问候语", "初次见面")
        else -> emptyList()
    }
    for (label in labels) {
        t = t.removePrefix("**$label**").removePrefix("$label").trim()
        // 剥掉紧跟其后的冒号（中英文都算）
        t = t.trimStart('：', ':', '　', ' ', '\n')
    }
    // 去掉整段包裹的引号/书名号
    val pairs = listOf('"' to '"', '“' to '”', '「' to '」', '『' to '』', '\'' to '\'')
    for ((open, close) in pairs) {
        if (t.length > 1 && t.first() == open && t.last() == close) {
            t = t.substring(1, t.length - 1).trim()
        }
    }
    // 反馈 #4：抹掉人名
    //  ① 开头的"某某："（2-8 个中英文字符 + 冒号）——典型如"雪村由纪：自幼…"
    t = t.replace(Regex("^[\\u4e00-\\u9fa5A-Za-z·]{2,8}[：:]\\s*"), "").trim()
    //  ② 用户填过的真实名字出现在正文里 → 换成"她"（保住句子通顺，又不会
    val real = charName.trim()
    if (real.isNotEmpty() && real != "？" && t.contains(real)) {
        t = t.replace(real, "她").trim()
    }
    if (kind == "speakingStyle") {
        // 说话风格只保留第一句/第一行，并限长 60 字
        t = t.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: t
        if (t.length > 60) t = t.take(60)
    }
    return t
}

// ==================== 新建角色「✨ 一键帮我写全部」的解析 ====================
//
// 提示词要求模型输出：
//   【说话风格】…
//   【性格描述】…
//   【背景故事】…
//   【开场白】…
// 这里按小标题把四段切开。为什么不用 JSON：4B 量化小模型经常会写出坏 JSON
// （少引号 / 尾逗号 / 字符串里带裸换行），一次 parse 失败就得整批丢弃；
// 而按小标题切分——掉一个标题只丢一个字段，其余照常可用，也不怕模型多说两句废话。

/**
 * 本文件顶层函数用的日志 tag。
 * 不能直接用 `TAG`：它是 MainActivity.companion 里的 private 常量，
 * 而下面这些函数和 RoleplayApp 都在类外，取不到。
 */
private const val LOG_TAG = "MainActivity"

/** 批量生成的 4 个字段：顺序 = 提示词要求模型输出的顺序 = 回填顺序 */
private val AI_ALL_KINDS = listOf("speakingStyle", "personality", "backstory", "greeting")

/**
 * 每个字段在模型输出里可能出现的小标题。
 *
 * 只收「放在行首不会被误认成正文」的说法。刻意**不收**「性格」「背景」这类两字简称：
 * 性格描述的正文极可能以「性格温柔但…」开头，收进来会把正文当成新标题，把上一段拦腰砍断。
 * 每个字段内部长的排前面（正则的 | 是有序的，先匹配到的优先）。
 */
private val AI_ALL_LABELS: Map<String, List<String>> = mapOf(
    "speakingStyle" to listOf("说话风格", "说话方式", "语言风格", "语气风格"),
    "personality" to listOf("性格描述", "性格特点", "人物性格", "性格设定"),
    "backstory" to listOf("背景故事", "角色背景", "人物背景", "身世背景"),
    "greeting" to listOf("开场白", "开场台词", "问候语", "初次见面")
)

/** 小标题文字 → kind 的反查表（正则只负责抓标题文字，归属在这里查） */
private val AI_ALL_LABEL_TO_KIND: Map<String, String> =
    AI_ALL_LABELS.entries.flatMap { (kind, labels) -> labels.map { it to kind } }.toMap()

/** 行首允许出现的标题装饰（Markdown 粗体/列表符号/「一、」「4.」这类编号前缀/全角空白等） */
private const val AI_HEADING_DECOR = "[ \\t\\u3000>#*\\-•·—一二三四五六七八九十0-9.、)）]{0,8}"

/** 标题文字后面允许出现的收尾记号：Markdown 粗体的 `**`（模型常写「**说话风格**」） */
private const val AI_HEADING_CLOSE = "\\**"

/**
 * 找出所有小标题的位置，返回 (kind, 标题起点, 正文起点)，按出现顺序排列。
 *
 * strict = true（认定标准从严）只认三种写法：
 *   ① 带括号：【说话风格】/ [说话风格] / （说话风格），后面可跟 `**` 和冒号
 *   ② 带冒号：说话风格：/ 说话风格:
 *   ③ 整行只有标题
 * 为什么这么严：正文里顺嘴提到「性格描述」四个字（比如台词里说"你的性格描述写得真好"）
 * 不该被当成新标题，否则一段文案会被砍成两半。
 *
 * strict = false（兜底）只要标题出现在**行首**就认，连「说话风格她说话很温柔」
 * 这种一个标点都没有的写法也吃下来；可靠度低，所以只在严格模式凑不齐 4 个标题时才启用。
 */
private fun findAiAllHeadings(text: String, strict: Boolean): List<Triple<String, Int, Int>> {
    val label = AI_ALL_LABELS.values.flatten().joinToString("|")
    val pattern = if (strict) {
        Regex(
            "^$AI_HEADING_DECOR(?:" +
                "[【\\[（(]\\s*($label)\\s*[】\\]）)]\\s*$AI_HEADING_CLOSE\\s*[：:]?" +  // ① 带括号
                "|($label)\\s*$AI_HEADING_CLOSE\\s*[：:]" +                             // ② 带冒号
                "|($label)\\s*$AI_HEADING_CLOSE\\s*$" +                                 // ③ 整行只有标题
                ")",
            RegexOption.MULTILINE
        )
    } else {
        Regex(
            "^$AI_HEADING_DECOR[【\\[（(]?\\s*($label)\\s*$AI_HEADING_CLOSE\\s*[】\\]）)]?\\s*[：:]?",
            RegexOption.MULTILINE
        )
    }
    val hits = mutableListOf<Triple<String, Int, Int>>()
    for (m in pattern.findAll(text)) {
        val raw = if (strict) {
            m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value
        } else {
            m.groups[1]?.value
        }
        val kind = AI_ALL_LABEL_TO_KIND[raw?.trim()] ?: continue
        hits += Triple(kind, m.range.first, m.range.last + 1)
    }
    return hits
}

/**
 * 丢掉模型爱加的收尾话（"以上就是全部内容""希望你喜欢这份设定"）和结尾的分隔线。
 *
 * 只按**行首的强特征词**丢，不做模糊匹配：像「如果」「希望」开头的台词
 *（"(歪头) 如果我说想你呢？"）必须留着，所以宁可漏丢一点也不误删。
 */
private val AI_META_TAIL_PREFIX = listOf(
    "以上", "希望你喜欢", "希望这个", "希望这份", "希望你能喜欢",
    "注：", "注:", "备注：", "说明：", "小结："
)
private val AI_META_TAIL_LINE = listOf("---", "***", "___", "- - -")

private fun stripAiMetaTail(text: String): String {
    val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
    while (lines.isNotEmpty()) {
        val last = lines.last().trim()
        if (last.isEmpty()) {
            lines.removeAt(lines.size - 1)
            continue
        }
        if (last in AI_META_TAIL_LINE || AI_META_TAIL_PREFIX.any { last.startsWith(it) }) {
            lines.removeAt(lines.size - 1)
            continue
        }
        break
    }
    return lines.joinToString("\n").trim()
}

/**
 * 把批量生成的原文切成 4 个字段。
 *
 * 规矩：
 *  1. 一个标题都认不出来 → **整段丢弃**（返回空表），绝不把整段塞进某一个字段；
 *  2. 缺哪个字段就哪个字段不出现（编辑器那边留空，用户可单独点「✨ 帮我写」）；
 *  3. 每段正文先丢掉模型加的收尾废话，再走 [cleanAiField] 洗一遍（去重出的字段名前缀、
 *     去包裹引号、抹人名、说话风格只留第一句），最后按 [capAiField] 收口；
 *  4. 同一字段被写了两次，只认第一次出现的那段。
 */
private fun parseAiFieldsAll(raw: String, charName: String = ""): Map<String, String> {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (text.isEmpty()) return emptyMap()

    // 严格认标题凑不齐 4 个就用行首兜底再跑一遍，谁认出来的多听谁的
    //（宽松规则是严格规则的超集，所以"更多"一定不会更差）
    var hits = findAiAllHeadings(text, strict = true)
    if (hits.size < AI_ALL_KINDS.size) {
        val loose = findAiAllHeadings(text, strict = false)
        if (loose.size > hits.size) hits = loose
    }
    if (hits.isEmpty()) {
        Log.w(LOG_TAG, "一键生成：没识别到任何小标题，整段丢弃（${text.length} 字）")
        return emptyMap()
    }

    val out = mutableMapOf<String, String>()
    hits.forEachIndexed { index, hit ->
        val kind = hit.first
        val bodyStart = hit.third
        if (out.containsKey(kind)) return@forEachIndexed
        // 本段正文到**下一个标题的起点**为止 —— 用 next.third（下个标题正文起点）
        // 会把下一个标题本身留在本段末尾，之前实测就是这么把「【背景故事】」混进性格描述里的
        val bodyEnd = hits.getOrNull(index + 1)?.second ?: text.length
        if (bodyStart >= bodyEnd) return@forEachIndexed
        val cleaned = cleanAiField(kind, stripAiMetaTail(text.substring(bodyStart, bodyEnd)), charName)
        val capped = capAiField(cleaned, kind)
        // 小模型有时会把 user 提示里的"字数要求"原文抄回来当正文（"80-150 字，具体列出…"）。
        // 这种内容留着比留空更有害（看着像人设其实是要求），命中就丢掉这个字段，
        // 编辑器那边会提示"已生成 N/M 项"，用户点一下重试即可。
        if (capped.isNotBlank() && !AI_SPEC_ECHO.containsMatchIn(capped)) out[kind] = capped
    }
    // 只在拿到内容的字段上做日志，方便真机上核对模型到底写了什么
    Log.i(LOG_TAG, "一键生成解析结果：" + AI_ALL_KINDS.joinToString("、") { k ->
        if (out.containsKey(k)) "$k=${out[k]!!.length}字" else "$k=缺"
    })
    return out
}

/**
 * 提示词里"字数要求"被小模型原文抄回来的特征：`80-150 字` / `10~25 字` / `30 至 80 字`。
 * 正常角色文案里不会出现这种"数字区间 + 字"，所以命中即判定为抄回来的要求。
 */
private val AI_SPEC_ECHO = Regex("\\d+\\s*[-~—至]\\s*\\d+\\s*字")

/**
 * 单字段长度收口。
 *
 * 防的是"模型漏写小标题、把四段全堆在一段里"：那种情况下第一个字段会异常长。
 * 超过上限就退到最后一个句末标点处断开，宁可短一点，也不把整段塞进一个字段。
 */
private fun capAiField(text: String, kind: String): String {
    val max = when (kind) {
        "speakingStyle" -> 80
        "personality" -> 320
        "backstory" -> 500
        "greeting" -> 300
        else -> 300
    }
    if (text.length <= max) return text
    val head = text.take(max)
    val cut = head.indexOfLast { it in "。！？!?；;…\n" }
    return (if (cut >= max / 2) head.take(cut + 1) else head).trim()
}
