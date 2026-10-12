package com.example.roleplay.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.ai.gguf.GGUFRuntime
import com.example.roleplay.R
import com.example.roleplay.ai.gguf.ModelRegistry
import com.example.roleplay.data.AppSettings
import com.example.roleplay.data.model.BondLevel
import com.example.roleplay.local.LocalEngineProvider
import com.example.roleplay.ui.components.AppConfirmDialog
import com.example.roleplay.ui.components.AppRewardDialog
import com.example.roleplay.ui.components.TopHintHost
import com.example.roleplay.ui.components.UserAvatar
import com.example.roleplay.ui.components.clearAvatarPhotoCache
import com.example.roleplay.viewmodel.CharacterViewModel
import com.example.roleplay.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

/** token 数 → "0.5K / 1.0K / 8.0K"，保留一位小数（避免 5.19921875K 这种尾巴） */
private fun kTokens(v: Int): String =
    java.lang.String.format(java.util.Locale.US, "%.1fK", v / 1024.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appSettings: AppSettings,
    ggufRuntime: GGUFRuntime,
    characterViewModel: CharacterViewModel,
    chatViewModel: ChatViewModel,
    isModelLoaded: Boolean,
    activeModelName: String,
    /** 真机包：当前本地推理引擎跑在哪个后端（CPU / Adreno GPU / Hexagon NPU）。
     *  默认包恒为空串，状态显示与以前完全一致。 */
    engineLabel: String = "",
    /**
     * 真机包：本地推理引擎此刻是否正在运行（来自
     * `LocalEngineProvider.engine?.state.value.running`，在 MainActivity 里采集后传进来）。
     *
     * 为什么需要它：真机包里 `contextSize` 只在**启动引擎时读一次**
     * （PhysLocalEngine.start() 读 AppSettings.current.contextSize 拼 `-c`），
     * 引擎跑着的时候拖「上下文长度」滑块不会有任何效果，必须重启引擎。
     * 拿这个标记给滑块加一行提示，免得用户以为改了没用。
     *
     * 默认值 false —— 默认包（PC 包）里 MainActivity 的 engineState 是空快照流，
     * running 恒为 false，所以默认包不会出现这行提示，行为与以前完全一致。
     */
    isEngineRunning: Boolean = false,
    /** 正在执行「重试连接」（按钮显示「连接中...」，防止连点） */
    isReconnecting: Boolean = false,
    onRetryConnect: () -> Unit,
    /**
     * 静默重连：真机卡在引擎启动后自动重连时用它 —— 只重连、不铺全屏加载遮罩。
     * 默认值 = onRetryConnect，默认包没有真机卡，行为完全不变。
     */
    onSilentReconnect: () -> Unit = onRetryConnect,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by appSettings.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 真机包判定：LocalEngineProvider.isPhysBuild == BuildConfig.LOCAL_ENGINE（编译期常量），
    // 默认包里恒为 false —— 所有用到它的分支在默认包里都走"老样子"那一支。
    val isPhysBuild = com.example.roleplay.local.LocalEngineProvider.isPhysBuild

    // 反馈 #6：语音朗读整块设置（朗读状态 / 去安装检查引擎 / 语速 / 音调 / 测试朗读）
    // 已按用户要求删除 —— 朗读只保留"消息气泡上的朗读图标"这一条入口，
    // 不可用时由 MainActivity 弹出与 TtsManager.UNAVAILABLE_HINT 一致的提示。

    // 建议 #5：备份 / 恢复的 SAF 选择器（无需存储权限）
    val backupContext = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = characterViewModel.exportBackup()
            val ok = try {
                backupContext.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                true
            } catch (_: Exception) { false }
            android.widget.Toast.makeText(
                backupContext,
                if (ok) "备份已导出" else "导出失败",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = try {
                backupContext.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().decodeToString() } ?: ""
            } catch (_: Exception) { "" }
            val result = characterViewModel.importBackup(json)
            android.widget.Toast.makeText(
                backupContext,
                if (result.ok) "已恢复数据" else result.message,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // 本地编辑状态（提交时才写入）
    var editHost by remember(settings) { mutableStateOf(settings.serverHost) }
    var editPort by remember(settings) { mutableStateOf(settings.serverPort.toString()) }
    var editSwitchPort by remember(settings) { mutableStateOf(settings.switchPort.toString()) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // ---- 我的资料（本轮）：头像单独一行居中在上，昵称默认只读，右上角铅笔进编辑态、
    //      编辑态右上是对勾，点一下即保存；保存提示走顶部紫色药丸（与对话里复制同款） ----
    var editingName by remember { mutableStateOf(false) }
    var draftName by remember(settings.userName) { mutableStateOf(settings.userName) }
    // G9/G13：头像选完 / 昵称保存后的提示 —— 顶部药丸（TopHintHost），与对话内复制一致
    var saveHint by remember { mutableStateOf<String?>(null) }
    /**
     * 选头像：SAF 面板，拷到 filesDir/avatars/user_avatar_<ts>.jpg。
     *
     * M2（真机）：部分 ROM（Flyme 等）的系统相册选择器拿到的 uri 应用读不到
     * （openInputStream 返回 null 直接静默失败 → 用户看到"头像保存失败"）。
     * 现在两条链：
     *   ① PickVisualMedia（相册九宫格，体验好）→ 读不到就自动跳到 ②
     *   ② OpenDocument（SAF 文件选择，任何 ROM 都有，uri 读权限由框架保证）
     * 最终仍读不到才提示失败，不再静默。
     */
    var pendingAvatarFallback by remember { mutableStateOf(false) }

    /** 把选中的图片 uri 拷进 filesDir/avatars 并设为用户头像 */
    fun saveAvatarFromUri(picked: android.net.Uri, canFallback: Boolean) {
        try {
            val dir = java.io.File(backupContext.filesDir, "avatars").apply { mkdirs() }
            // 文件名带时间戳：路径一变，聊天页/设置页的头像才会立刻刷新
            // （同名覆盖时 Settings 的 state 值没变，UI 不会重组 —— 真机测试踩到过）
            val target = java.io.File(dir, "user_avatar_${System.currentTimeMillis()}.jpg")

            var input = backupContext.contentResolver.openInputStream(picked)
            if (input == null) {
                // ROM 相册选择器没给读权限：先试着取一次持久权限再读
                try {
                    backupContext.contentResolver.takePersistableUriPermission(
                        picked,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    input = backupContext.contentResolver.openInputStream(picked)
                } catch (_: Exception) {
                }
            }
            if (input == null) {
                if (canFallback) {
                    saveHint = "这张图片读不到，换一种方式选择"
                    pendingAvatarFallback = true
                } else {
                    saveHint = "头像更新失败"
                }
                return
            }
            input.use { ins ->
                java.io.FileOutputStream(target).use { out -> ins.copyTo(out) }
            }
            // 真机反馈「保存后头像变形」的两个原因，这里一次治掉：
            //   ① 相册原图多是 4000×3000 的竖/横构图，原样存下来再被整张拉进圆形头像 → 人被压扁；
            //   ② 手机竖拍的照片像素其实是横的，方向只写在 EXIF 里，不摆正贴图就躺倒（看着也像变形）。
            // 处理成：按 EXIF 摆正 → 居中裁成正方形（圆形头像正好取画面正中）→ 缩到 512 存 JPEG。
            // 头像永远是圆的，方形源图最稳，占地也从好几 MB 降到几十 KB。
            normalizeAvatarFile(target)
            if (target.length() > 0) {
                // 选完即保存（默认保存，无二次确认）；清掉旧头像文件，别在 files/avatars 里堆孤儿
                settings.userAvatarPath
                    ?.takeIf { it != target.absolutePath && java.io.File(it).name.startsWith("user_avatar_") }
                    ?.let { old -> java.io.File(old).delete() }
                clearAvatarPhotoCache()
                appSettings.update { s -> s.copy(userAvatarPath = target.absolutePath) }
                saveHint = "头像已更新"
            } else {
                saveHint = "头像更新失败（图片为空）"
            }
        } catch (_: Exception) {
            saveHint = if (canFallback) "这张图片读不到，换一种方式选择" else "头像更新失败"
            if (canFallback) pendingAvatarFallback = true
        }
    }

    val userDocumentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) saveAvatarFromUri(uri, canFallback = false)
    }
    val userPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) saveAvatarFromUri(uri, canFallback = true)
    }

    // M2：相册选择器读不到时，自动开 SAF 文件选择器兜底
    LaunchedEffect(pendingAvatarFallback) {
        if (pendingAvatarFallback) {
            pendingAvatarFallback = false
            userDocumentPicker.launch(arrayOf("image/*"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF16161E))
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF12121A))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ========== 我的资料 ==========
            // item6：整块改成**左对齐**的资料行 ——
            //   [头像]  昵称 ✏️
            //           Lv.N · 距下一级还差 M 条
            //           [▓▓▓▓░░░░░░] 经验条
            // 头像贴左，昵称/等级/经验条都在头像右侧那一列里左对齐，
            // 等级文字正好压在经验条左上角。
            // 等级口径与角色羁绊**完全一致**（BondLevel：每 12 条消息一级，
            // 跨角色、跨剧情线累计），所以「我的等级」只随聊天量走，删消息也会如实回落。
            val totalMessages by chatViewModel.totalMessageCount.collectAsState()
            val myLevel = BondLevel.levelFor(totalMessages)
            val myProgress = BondLevel.progress(totalMessages)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("我的资料", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "头像和昵称会显示在你发送的气泡旁边",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 头像：靠左对齐，点一下直接换图（选完即保存）
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clickable {
                                    userPhotoPicker.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                                .testTag("user_avatar_picker"),
                            contentAlignment = Alignment.Center
                        ) {
                            UserAvatar(modifier = Modifier.size(72.dp), photoPath = settings.userAvatarPath)
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // 头像右侧一列：昵称 → 等级 → 经验条（三者左对齐）
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 昵称：默认只读展示；点右侧铅笔进编辑态，编辑态变对勾，点对勾即保存
                                if (editingName) {
                                    OutlinedTextField(
                                        value = draftName,
                                        onValueChange = { if (it.length <= 12) draftName = it },
                                        label = { Text("昵称") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors()
                                    )
                                } else {
                                    Text(
                                        if (settings.userName.isBlank()) "还没设置昵称" else settings.userName,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (settings.userName.isBlank()) Color.White.copy(alpha = 0.45f) else Color.White,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = {
                                        if (editingName) {
                                            // 保存：写入设置 + 退出编辑态 + 顶部药丸提示（不带头像弹窗，G9）
                                            val picked = draftName.trim()
                                            appSettings.update { s -> s.copy(userName = picked) }
                                            editingName = false
                                            saveHint = if (picked.isEmpty()) "昵称已清空" else "昵称已保存"
                                        } else {
                                            draftName = settings.userName
                                            editingName = true
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        if (editingName) Icons.Default.Check else Icons.Default.Edit,
                                        contentDescription = if (editingName) "保存昵称" else "编辑昵称",
                                        tint = Color(0xFFCE93D8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // 等级：压在经验条左上角
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Lv.$myLevel",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFCE93D8)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    // 只留"还差几条升级"，不写满级数字（按用户要求去掉「满级 Lv.100」提示）
                                    if (myLevel >= BondLevel.MAX_LEVEL) "已满级"
                                    else "距下一级还差 ${BondLevel.messagesToNext(totalMessages)} 条",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            // 经验条（当前等级内的进度）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2A2A3E))
                            ) {
                                if (myProgress > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(myProgress)
                                            .fillMaxHeight()
                                            .background(Color(0xFF9C27B0))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 真机反馈：打赏横幅从「关于」区挪到「我的资料」（头像栏）正下方看效果。
            // 设置页两个包共用同一份代码，模拟器（MuMu 版）自动同步。
            // 入口样式不变：**暖色渐变横幅**（🧋 图标 + 会「呼吸」的支持胶囊），
            // 点开仍是统一外壳 AppRewardDialog（微信 + 支付宝两张码，完全离线）。
            var showRewardDialog by remember { mutableStateOf(false) }
            RewardBanner(onClick = { showRewardDialog = true })
            if (showRewardDialog) {
                AppRewardDialog(onDismiss = { showRewardDialog = false })
            }

            // ========== 推理服务 ==========
            SettingCard(
                title = "推理服务状态",
                // G16 + 本轮：真机包只显示「已连接 - 推理设备」（如 已连接 - CPU /
                // GPU · Adreno / NPU · Hexagon），不再跟模型名（模型名在下方
                // 「真机本地推理」卡的标题行里已经能看到）；
                // 默认包 engineLabel 为空串，仍显示「已连接 - 模型名」，与以前一致。
                subtitle = if (isModelLoaded) {
                    buildString {
                        append("已连接 - ")
                        if (engineLabel.isNotBlank()) {
                            append(engineLabel)
                        } else {
                            append(activeModelName)
                        }
                    }
                } else "未连接",
                // P5：未连接时卡内加一行小字，说明「重试连接」是干什么的。
                // 真机包换一种说法：它连的是手机自己的本地引擎，引擎起来后
                // 下面那张「真机本地推理」卡会自动重连，不需要手动重试。
                hint = if (isModelLoaded) null
                else if (isPhysBuild) "手机里安装的是本地引擎：到下面「真机本地推理」点「启动引擎」，起来后会自动连上"
                else "断线或服务重启后，点「重试连接」可手动把 App 重新连回推理服务",
                trailing = {
                    if (isModelLoaded) {
                        Icon(Icons.Default.CheckCircle, "已连接", tint = Color(0xFF4CAF50))
                    } else if (!isPhysBuild) {
                        // P3：按钮加圆角 + 半粗白字，和整体卡片风格统一；
                        // P6：点下后变「连接中...」（转圈），失败自动恢复、成功翻成已连接
                        Button(
                            onClick = onRetryConnect,
                            enabled = !isReconnecting && !isTesting,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF9C27B0),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            if (isReconnecting) {
                                CircularProgressIndicator(
                                    Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("连接中...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("重试连接", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    // 真机包未连接时这里刻意留空：本地引擎的启动/重连由下面那张卡负责，
                    // 摆一颗"重试连接"只会让人误以为是连电脑（引擎没起来时点它必然失败）。
                }
            )

            // ========== 真机本地推理（只有真机包会出现）==========
            // 默认包里 LocalEngineProvider.engine 恒为 null → 这一行什么都不渲染，
            // 默认包的设置页和以前一模一样。
            LocalEngineProvider.engine?.let { engine ->
                engine.SettingsSection(
                    // 引擎起来后的自动重连走静默通道：不铺全屏遮罩，屏幕就不会闪一下
                    onRetryConnect = onSilentReconnect,
                    isConnected = isModelLoaded,
                    // 真机反馈：卡片内复制成功的提示走和本页其它提示同一颗顶部药丸
                    // （saveHint + TopHintHost，与对话内复制同一套样式），不用系统 Toast
                    onShowHint = { saveHint = it }
                )
            }

            // ========== 模型切换 ==========
            // 反馈 #11：清单来自 PC 端（配置项 + models/gguf 自动扫到的），
            // 往文件夹丢个 gguf 就会出现在这里，不用改 App 代码。
            // 真机包里模型交给上面的本地引擎管，这一段会整体隐身（hidesRemoteSwitcher）。
            if (LocalEngineProvider.engine?.hidesRemoteSwitcher != true) {
                SettingSection(title = "模型切换")
                // activeId 原来只喂给 ModelSwitcherRow 的 active 形参（已删）→ 这里一并去掉，
                // 否则会换来一条 "Variable 'activeId' is never used"；activeModelId 不再需要被观察。
                val modelList by ggufRuntime.modelInfos.collectAsState()
                val displayModels = if (modelList.isNotEmpty()) {
                    modelList
                } else {
                    ModelRegistry.models.map {
                        com.example.roleplay.ai.gguf.GGUFRuntime.ModelInfo(
                            id = it.id, name = it.name, description = it.description, active = false
                        )
                    }
                }
                displayModels.forEach { model ->
                    ModelSwitcherRow(
                        model = model,
                        selected = model.id == settings.modelId,
                        // 这里原来的 active = (model.id == activeId) 已随形参一起删除
                        //（"运行中"标签不再显示，见 ModelSwitcherRow 的注释）
                        onClick = {
                            scope.launch {
                                val result = ggufRuntime.switchModel(model.id)
                                if (result.isSuccess) {
                                    appSettings.update { s -> s.copy(modelId = model.id) }
                                }
                            }
                        }
                    )
                }
            }

            // 用户反馈（本轮 #2）：「模型与磁盘」区块先隐藏，只保留模型切换。
            // PC 端 /disk 端点与 GGUFRuntime.diskInfo() 仍保留，需要时随时恢复 UI。

            // ========== 服务器配置（仅默认包 / MuMu 版）==========
            // item3：真机包（phys）是内置本地引擎、直连 127.0.0.1，这一栏填了也没用
            //（地址端口由 PhysLocalEngine 自己决定），所以整个区块在真机包直接隐身。
            // 判定用 LocalEngineProvider.isPhysBuild（= BuildConfig.LOCAL_ENGINE 编译期常量），
            // 默认包里恒为 false，设置页与以前一模一样。
            if (!isPhysBuild) {
                SettingSection(title = "服务器配置")
                OutlinedTextField(
                    value = editHost,
                    onValueChange = { editHost = it },
                    label = { Text("服务地址 (IP)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors()
                )
                Row {
                    OutlinedTextField(
                        value = editPort,
                        onValueChange = { editPort = it },
                        label = { Text("推理端口") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = editSwitchPort,
                        onValueChange = { editSwitchPort = it },
                        label = { Text("切换端口") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Button(
                    onClick = {
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val hostPort = editHost.trim()
                            val port = editPort.toIntOrNull() ?: 8080
                            val success = ggufRuntime.testConnection(hostPort, port)
                            testResult = if (success) "连接成功 ✓" else "连接失败 ✗"
                            isTesting = false
                            if (success) {
                                appSettings.update { s ->
                                    s.copy(serverHost = hostPort, serverPort = port, switchPort = editSwitchPort.toIntOrNull() ?: 8090)
                                }
                            }
                        }
                    },
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                ) {
                    Text(if (isTesting) "测试连接中..." else "保存并测试连接", color = Color.White)
                }
                testResult?.let {
                    Text(
                        text = it,
                        color = if (it.contains("✓")) Color(0xFF4CAF50) else Color(0xFFE53935),
                        fontSize = 12.sp
                    )
                }
            }

            // ========== 生成参数 ==========
            SettingSection(title = "生成参数")

            // 温度（步进 0.01，需求 #13）
            SettingSlider(
                title = "温度",
                subtitle = "控制随机性（${fmt2(settings.temperature)}）",
                value = settings.temperature,
                valueRange = 0.1f..2.0f,
                step = 0.01f,
                onValueChange = { appSettings.update { s -> s.copy(temperature = it) } }
            )

            // Top P（步进 0.1）
            SettingSlider(
                title = "Top P",
                subtitle = "控制词表范围（${fmt2(settings.topP)}）",
                value = settings.topP,
                valueRange = 0.1f..1.0f,
                step = 0.1f,
                onValueChange = { appSettings.update { s -> s.copy(topP = it) } }
            )

            // Max Tokens（token 是整数，"0.1" 档位没有意义 —— 按 0.5K 一档，
            // 显示用 K 值，和"步长 0.1"的手感一致）
            IntSliderRow(
                title = "最大回复长度",
                subtitle = "${kTokens(settings.maxTokens)} tokens",
                value = settings.maxTokens,
                valueRange = 512..8192,
                step = 512,
                onValueChange = { newValue -> appSettings.update { s -> s.copy(maxTokens = newValue) } }
            )

            // Context Size（按 1K 一档）
            // 真机包：这个值只在引擎**启动时**读一次（PhysLocalEngine.start() 拿它拼 `-c`），
            // 引擎正在跑的时候改它不生效 → 给一行小字提示，别让用户以为滑块坏了。
            IntSliderRow(
                title = "上下文长度",
                subtitle = "${kTokens(settings.contextSize)} tokens（历史预算 ≈ ${kTokens(settings.historyTokenBudget)}）",
                value = settings.contextSize,
                valueRange = 2048..32768,
                step = 1024,
                // isPhysBuild 是编译期常量、isEngineRunning 在默认包恒为 false，
                // 所以默认包永远拿到 null，卡片渲染与以前一模一样。
                hint = if (isPhysBuild && isEngineRunning) "改完需要重启推理引擎才生效" else null,
                onValueChange = { newValue -> appSettings.update { s -> s.copy(contextSize = newValue) } }
            )

            // Repeat Penalty（步进 0.1）
            SettingSlider(
                title = "重复惩罚",
                subtitle = "倍率 ${fmt2(settings.repeatPenalty)}",
                value = settings.repeatPenalty,
                valueRange = 1.0f..2.0f,
                step = 0.1f,
                onValueChange = { appSettings.update { s -> s.copy(repeatPenalty = it) } }
            )

            // ========== 内容设置 ==========
            SettingSection(title = "内容设置")
            SettingSwitchRow(
                title = "甜蜜升级模式",
                subtitle = "允许更亲密的剧情走向（需模型支持）",
                checked = settings.adultMode,
                onCheckedChange = { appSettings.update { s -> s.copy(adultMode = it) } }
            )
            SettingSwitchRow(
                title = "自动保存对话",
                subtitle = "每次对话结束后自动保存到本地",
                checked = settings.autoSaveHistory,
                onCheckedChange = { appSettings.update { s -> s.copy(autoSaveHistory = it) } }
            )

            // ========== 数据管理 ==========
            // 需求 #12：按钮改为**全局**范围，且不可逆 → 必须先确认再动手。
            var pendingGlobalClear by remember { mutableStateOf<String?>(null) }
            SettingSection(title = "数据管理")
            // 反馈 #5：按用户指定顺序排列
            //   导入数据备份 → 导出数据备份 → 清空所有对话 → 清空所有长期记忆 → 重置所有设置
            // 建议 #5：数据备份 / 恢复（SAF 文件选择器，无需存储权限）
            SettingButtonRow(
                title = "导入数据备份",
                subtitle = "用 JSON 备份覆盖当前数据（不可撤销）"
            ) {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
            }
            SettingButtonRow(
                title = "导出数据备份",
                subtitle = "角色 / 会话 / 消息 / 长期记忆 → JSON 文件"
            ) {
                val ts = System.currentTimeMillis() / 1000L
                exportLauncher.launch("roleplay-backup-$ts.json")
            }
            SettingButtonRow(
                title = "清除所有对话",
                subtitle = "删除全部角色的所有会话与消息（不可恢复）"
            ) {
                pendingGlobalClear = "conversations"
            }
            SettingButtonRow(
                title = "清空所有长期记忆",
                subtitle = "删除全部角色的所有记忆条目（不可恢复）"
            ) {
                pendingGlobalClear = "memories"
            }
            SettingButtonRow(
                title = "重置所有设置",
                subtitle = "恢复为默认配置"
            ) {
                appSettings.reset()
            }
            pendingGlobalClear?.let { key ->
                AppConfirmDialog(
                    title = if (key == "conversations") "清除所有对话？" else "清空所有长期记忆？",
                    text = if (key == "conversations") {
                        "将删除所有角色的全部会话与消息，操作不可恢复。"
                    } else {
                        "将删除所有角色的全部记忆条目，角色将不再记得任何往事，操作不可恢复。"
                    },
                    confirmText = "确定清除",
                    cancelText = "取消",
                    confirmColor = Color(0xFFE53935),
                    onConfirm = {
                        if (key == "conversations") chatViewModel.clearAllConversations()
                        else chatViewModel.clearAllMemories()
                        pendingGlobalClear = null
                    },
                    onDismiss = { pendingGlobalClear = null }
                )
            }

            // ========== 关于 ==========
            SettingSection(title = "关于")
            // 需求 #11 打赏入口已挪到「我的资料」头像卡正下方（真机反馈：放上面看效果）。
            // 入口样式不变：**暖色渐变横幅**（🧋 图标 + 会「呼吸」的支持胶囊），
            // 点开仍是统一外壳 AppRewardDialog（微信 + 支付宝两张码，完全离线）。
            SettingCard(
                title = "AI 角色扮演",
                // 版本号不再写死；真机包写「内置本地推理」，默认包原「PC 端推理，手机互联」
                // 按用户要求改为作者署名；两个包都带这行署名，口径统一。
                subtitle = "版本 ${com.example.roleplay.BuildConfig.VERSION_NAME}\n基于 GGUF + llama.cpp\n" +
                        (if (com.example.roleplay.local.LocalEngineProvider.isPhysBuild)
                            "内置本地推理（仅手机、完全离线）\n"
                        else
                            "") +
                        "by 作者 范一铭"
            )
            SettingCard(
                title = "功能说明",
                subtitle = "• 支持多角色、多剧情线\n• 长期记忆让角色记住往事\n• 羁绊等级随对话增长\n• 角色卡完整度决定稀有度"
            )
            }
            // G9/G13：保存/头像更新的提示 —— 顶部紫色药丸（与对话内复制同一组件）。
            // 真机反馈（复制日志的提示看不见）：top=6dp 会把药丸贴在窗口最顶上，
            // 被不透明的顶栏整个盖住 —— 和 MainActivity 里修过的同一颗坑：
            // 顶栏（M3 TopAppBar）54dp，底边在内容区 63dp，+6dp 呼吸 = 69dp，
            // 落点和对话页/主页那两颗完全同一条线。
            TopHintHost(
                hint = saveHint,
                onDismissed = { saveHint = null },
                Modifier.align(Alignment.TopCenter).padding(top = 69.dp)
            )
        }
    }
}

// ========== 子组件 ==========

@Composable
private fun SettingSection(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        // G12：标题紫调亮一档，和深色底的对比更好
        color = Color(0xFFCE93D8),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    hint: String? = null,
    trailing: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                }
                trailing()
            }
            // 可选的一行辅助说明（比副标题更弱），比如「重试连接」的作用
            // （第 5 点：恢复原样 —— 上一轮加的定高槽把「未连接」下方的提示文字压没了，真机上看不到）
            if (hint != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(hint, fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
            }
            Spacer(modifier = Modifier.width(16.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        modifier = if (enabled) Modifier else Modifier.alpha(0.4f),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF9C27B0))
            )
        }
    )
}

@Composable
private fun SettingButtonRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.3f))
        }
    }
}

/**
 * 需求 #11（第二轮）：打赏入口升级 —— 从普通「打赏」行变成**暖色渐变横幅**。
 *
 * 设计意图：一瞥就有吸引力但不显得「商业」——
 * - 底色：主题紫系的深紫 → 暖莓渐变（跟全 App 深色主题同族，不跳色）；
 * - 左侧 🧋 图标瓦片（白 12% 半透明底），右侧**会呼吸的「支持 ♥」胶囊**
 *   （琥珀 → 珊瑚渐变，1.0↔1.05 缓慢缩放）—— 轻微「活」的感觉，引导手指点下去；
 * - 金色细描边，跟其他 1E1E2E 灰卡形成明确区分；整块可点。
 */
@Composable
private fun RewardBanner(onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "reward_banner_pulse")
    val pulse = transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reward_banner_pulse_float"
    ).value
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF432A5E), Color(0xFF5E3152))))
            .border(1.dp, Color(0x55FFC266), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("reward_banner")
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                // 🧋 emoji 在 MuMu 模拟器的系统字库里缺字形（显示成空框），换成随包矢量图标
                Icon(
                    painter = painterResource(R.drawable.ic_milk_tea),
                    contentDescription = "奶茶",
                    modifier = Modifier.size(24.dp),
                    tint = Color.Unspecified
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "如果这个 App 帮到了你",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "请作者喝杯奶茶 · 微信 / 支付宝",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.65f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .scale(pulse)
                    .clip(RoundedCornerShape(50.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFC266), Color(0xFFFF8A65))))
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Text(
                    text = "支持 ♥",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3A2010)
                )
            }
        }
    }
}

/**
 * 第 6 项：Slider 的 value 是 Float，直接 `toString()` 会把离散步进产生的浮点误差全打出来 ——
 * 真机上出现过「温度 = 0.9199999996」「Top P = 0.9600001」这种异常长的数字。
 * 统一按 2 位小数四舍五入，再去掉没意义的小数尾巴（0.92 / 1.1 / 2），
 * 右侧大号数字与副标题共用这一个格式化入口，保证同一页里显示口径一致。
 */
private fun fmt2(v: Float): String {
    val r = Math.round(v * 100f) / 100f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}

@Composable
private fun SettingSlider(
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    step: Float = 0f
) {
    // step > 0 → 启用离散步进：Slider 会把落点量化到 step 的倍数上。
    // 温度 0.01、top-p / 重复惩罚 / 语速 / 音调 0.1（需求 #13）。
    // 用 Math.round 消掉浮点误差（1.9f/0.01f 这类会差一毛）；
    // steps 是「内部档位数」，档位值数 = steps + 1。
    // 注意：ClosedFloatingPointRange（FloatRange）只有 start / endInclusive，
    // 没有 IntRange 那样的 first / last。
    val span = valueRange.endInclusive - valueRange.start
    val steps = if (step > 0f) Math.round(span / step) - 1 else 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                }
                Text(fmt2(value), fontSize = 14.sp, color = Color(0xFFCE93D8), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SliderInputRow(
    title: String,
    subtitle: String,
    value: Int,
    valueRange: IntRange = 0..10000,
    step: Int = 1,
    onValueChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value.toString(),
                    onValueChange = { newValue ->
                        newValue.toIntOrNull()?.takeIf { it in valueRange }
                            ?.let { onValueChange(it) }
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors()
                )
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onValueChange(it.toInt()) },
                    valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    steps = (valueRange.last - valueRange.first) / step - 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun IntSliderRow(
    title: String,
    subtitle: String,
    value: Int,
    valueRange: IntRange = 0..10000,
    step: Int = 1,
    /**
     * 可选的一行弱化提示（比副标题更次要）。
     * 样式与 [SettingCard] 的 hint 保持一致：10sp + 白字 35% 透明度。
     * 默认 null = 不渲染，现有调用点行为不变。
     */
    hint: String? = null,
    onValueChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                }
                Text(value.toString(), fontSize = 14.sp, color = Color(0xFFCE93D8), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = value.toFloat().coerceIn(valueRange.first.toFloat()..valueRange.last.toFloat()),
                // 修复：旧版 `onValueChange((it * step).toInt()...)` 把「当前值 × 步长」
                // 当新值传回去，越拖越大/越小还越界。Slider 的 steps 参数已经负责
                // 把落点量化到 step 的倍数，回调只需要原样回传刻度值即可。
                onValueChange = { v -> onValueChange(v.toInt().coerceIn(valueRange)) },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                steps = (valueRange.last - valueRange.first) / step - 1,
                modifier = Modifier.fillMaxWidth()
            )
            // 可选的弱化提示（本轮：真机包引擎运行时的「改完需要重启推理引擎才生效」）
            if (hint != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(hint, fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
private fun ModelSwitcherRow(
    // 反馈 #11：改成 GGUFRuntime.ModelInfo —— 列表可能包含 PC 端自动扫描到的模型，
    // 它们没有内置的 ModelProfile
    model: com.example.roleplay.ai.gguf.GGUFRuntime.ModelInfo,
    selected: Boolean,
    // 原来这里还有一个 active: Boolean（用来在行尾挂"运行中"标签），
    // 该标签在下面 ModelSwitcherRow 的注释里已经确认去掉（"radio 已经表示当前选中"），
    // 于是这个形参再没有任何地方使用、只剩一条 "Parameter 'active' is never used" 编译警告。
    // 本轮直接删掉形参 + 调用点那一个实参（以及只为它存在的 activeId 状态），行为不变。
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF9C27B0).copy(alpha = 0.2f)
                           else Color(0xFF1E1E2E)
        )
    ) {
        Row(
            // 需求 #4：右端固定 12dp 留白，"运行中"标签贴右
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF9C27B0))
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 需求 #14：长模型名**中间**截断（头看家族、尾看量化档），
            // 单行省略，绝不换行。
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = com.example.roleplay.util.truncateMiddle(model.name, 30),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    model.description,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            // 反馈：模型名后面不用再挂「运行中」—— radio 已经表示当前选中的模型，
            // 名字本身也够清楚，右端再塞一块绿标签在真机上显得挤。
        }
    }
}

/**
 * 把用户选的头像文件规范成「按 EXIF 摆正 + 居中裁成方形 + 最长边 512 的 JPEG」。
 *
 * 为什么必须做（真机反馈「保存后头像变形」）：
 * - 相册原图常是 3000×4000 这种非正方形，头像却是圆的：直接贴图要么被拉伸压扁，
 *   要么永远只能看到中间一条；裁成正方形后由圆形裁剪取正中，构图和比例都不会坏。
 * - 手机竖拍时像素往往是横的，方向只记录在 EXIF 里，BitmapFactory 不认这个标记，
 *   不摆正就会躺倒 —— 用户看到的也是「变形」。
 * - 顺带把动辄几 MB 的原图压到几十 KB，头像解码也更省内存。
 *
 * 任何一步失败都保留原文件（runCatching 包住），绝不把头像弄丢。
 */
private fun normalizeAvatarFile(file: java.io.File) {
    runCatching {
        // 先量尺寸，按最长边 2048 采样解码，避免 4000×3000 原图一次性吃几十 MB
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val src = android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@runCatching
        if (src.width <= 0 || src.height <= 0) return@runCatching

        // ① EXIF 方向
        val orientation = runCatching {
            android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val upright = if (matrix.isIdentity) src else android.graphics.Bitmap.createBitmap(
            src, 0, 0, src.width, src.height, matrix, true
        )

        // ② 居中裁正方形（圆形头像取的就是这块，比例绝不变形）
        val side = minOf(upright.width, upright.height)
        val square = android.graphics.Bitmap.createBitmap(
            upright,
            (upright.width - side) / 2,
            (upright.height - side) / 2,
            side,
            side
        )

        // ③ 最长边 512 足够（头像最大 72dp@3x ≈ 216px），统一存 JPEG
        val out = if (side > 512) android.graphics.Bitmap.createScaledBitmap(square, 512, 512, true)
        else square
        java.io.FileOutputStream(file).use { fos ->
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
        }
    }
}
