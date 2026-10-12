package com.example.roleplay.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.Rarity
import com.example.roleplay.data.model.suggestRarity
import com.example.roleplay.ui.components.AVATAR_BUILTIN_DEFAULT
import com.example.roleplay.ui.components.AppConfirmDialog
import com.example.roleplay.ui.components.CharacterAvatar
import com.example.roleplay.ui.components.getRarityColor
// 第 11 项：背景故事不再强制统一成 TA，unifyThirdPerson 已不再被本文件使用（工具函数保留备用）
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 批量生成在请求队列里用的哨兵 kind。
 * 四个真字段（speakingStyle/personality/backstory/greeting）之外多出来的这一个值，
 * 让「一键写全部」能复用同一条无界 Channel 队列 —— 天然和单字段请求串行，不会抢 slot。
 */
private const val AI_KIND_ALL = "ALL"

// ============================================================================
//  「✨帮我写 / ✨一键帮我写全部」两处按钮的对齐口径
//  （用户反馈：一键按钮与边框的间距要和「帮我写」一致）
//
//  水平：两处都用 [AI_ACTION_EDGE_PADDING] 这一个值。字段输入框是
//        `fillMaxWidth().padding(horizontal = 16.dp)`，边框正好落在 16dp 处，
//        按钮行也留同样的 16dp → 两条按钮的文字右边缘到边框逐像素一致，
//        而且以后改只改一处，不会再只改一边。实测（720×1280 截图）：
//        一键文字右边缘 x=689~690、帮我写 x=689、输入框右边框 x=691~693，
//        两者本来就相同 —— **差异不在水平方向**。
//
//  垂直（真正的问题在这里）：Material3 的 OutlinedTextField 只要带 label，
//        就会在**节点顶部**再叠一层 8dp 内边距
//        （material3 1.1.x `OutlinedTextFieldTopPadding = 8.dp`；
//         反编译 1.1.2 的 OutlinedTextFieldKt.<clinit> 可确认：bipush 8 → Dp(8f)）。
//        也就是说"输入框上边框"其实在节点顶部往下 8dp 处：
//            Row 底边 ──(8dp 看不见的空白)── 上边框
//        而输入框**下方**那一行「帮我写」没有这 8dp。
//        实测：一键按钮文字下沿 → 上边框 = 21px；帮我写文字上沿 → 下边框 = 7.6px
//        （密度≈1.69，16dp=27px、8dp=13.5px），差 13.4px ≈ **正好 8dp**。
//        所以把一键这一行整体下移同样的 8dp 去抵消：补偿量与成因是同一个 8dp，
//        在任何密度下都会等比缩放，两处到各自边框的距离恒等。
// ============================================================================
private val AI_ACTION_EDGE_PADDING = 16.dp
private val OUTLINED_FIELD_LABEL_TOP_INSET = 8.dp
/**
 * 「帮我写」按钮行（输入框**下方**那几行）统一的 top padding。
 * 抽成常量是为了和「一键帮我写全部」的 offset 补偿量联动（见下面注释）。
 */
private val AI_ROW_TOP_PADDING = 2.dp

/**
 * 第 3 项（本轮）：角色名字符数上限。
 *
 * 10 个字是用户给的口径（首页卡片上名字是居中大字，太长会挤掉稀有度标签/等级）。
 * 两道保险：
 *  1. 输入框 [OutlinedTextField] 的 onValueChange 里直接拒绝超长输入（打不进去，不是报错）；
 *  2. 保存时 [CharacterEditorScreen] 的 doSave 再 take(10)，兜住"编辑老角色本来是超长名字"
 *     和以后可能出现的模板/导入等旁路，保证落库数据一定合规。
 * 输入框下方那行提示会实时显示"已输入 n/10"，到上限时变琥珀色。
 */
private const val NAME_MAX_LEN = 10

/**
 * 角色编辑器 / 创建器。
 *
 * 需求实现：
 * - 9. 角色名称居中显示，允许修改 → 名字段已在顶部居中
 * - 10. 稀有度说明 → 底部有说明卡片，且保存时自动提示建议
 * - Import/Export → 右上角按钮导出为 JSON，从文件导入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditorScreen(
    character: Character?,
    onSave: (Character) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // 建议 #7：AI 帮我写（kind = speakingStyle/personality/backstory/greeting）
    // regenerate = 该字段已有内容，这次是「重新生成」，希望换一版说法
    aiFiller: (suspend (kind: String, name: String, tags: String, regenerate: Boolean) -> String)? = null,
    /**
     * 新增：「✨ 一键帮我写全部」—— 一次请求写全 4 个字段。
     *
     * 为什么不做「4 个字段并发请求」（用户问的"帮我写可以并行吗"）：
     *  1. 真机引擎是 `llama-server -np 1`（PhysLocalEngine.kt:316），llama.cpp 只开
     *     **1 个 slot**。并发发 4 个 HTTP 请求不会更快 —— 服务端照样排队，多的请求
     *     还会撞 slot 忙（503/timeout），反倒更容易失败。
     *  2. 串行点 4 次「✨ 帮我写」= 4 次完整前向过程（本地 4B 量化模型每次 30~90 秒）；
     *     一次请求写 4 段 ≈ 1 次前向 + 更长的输出，总耗时约为 1/4。
     *  3. 4 段在同一个上下文里生成，性格 / 背景 / 说话风格 / 开场白互相呼应，人设更自洽。
     *
     * @param regenerateAll true = 4 个字段都已有内容，「全部重新生成」（全量覆盖）；
     *                      false = 还有空白字段，**只补空白、不覆盖用户手填的内容**
     * @return kind → 文案 的映射，只包含真的解析出来的字段（解析不出的字段直接不出现）
     */
    aiFillerAll: (suspend (name: String, tags: String, regenerateAll: Boolean) -> Map<String, String>)? = null
) {
    val isEdit = character != null
    var name by remember(character) { mutableStateOf(character?.name ?: "") }
    var personality by remember(character) { mutableStateOf(character?.personality ?: "") }
    var speakingStyle by remember(character) { mutableStateOf(character?.speakingStyle ?: "") }
    var backstory by remember(character) { mutableStateOf(character?.backstory ?: "") }
    var greeting by remember(character) { mutableStateOf(character?.greeting ?: "") }
    var selectedRarity by remember(character) { mutableStateOf(character?.rarity ?: 0) }
    var tags by remember(character) { mutableStateOf(character?.tags ?: "") }
    // 需求 #1：照片头像要随编辑实时带出/更换
    var avatarPhotoPath by remember(character) { mutableStateOf(character?.avatarAsset) }

    // 自动稀有度建议（保存时弹出）
    var suggestedRarity by remember { mutableIntStateOf(-1) }
    var showRarityDialog by remember { mutableStateOf(false) }

    // 照片选择器（PhotoPicker 走系统面板，不需要存储权限）
    val editorContext = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val idForFile = character?.id ?: UUID.randomUUID().toString()
            val dir = File(editorContext.filesDir, "avatars").apply { mkdirs() }
            val target = File(dir, "${idForFile}_avatar.jpg")
            editorContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (target.exists()) avatarPhotoPath = target.absolutePath
        }
    }

    // 建议 #7：AI 帮我写 / 反馈 #9 #10。
    //
    // 三个坑都在这儿一起治了：
    //  1. 最早写成 LaunchedEffect(kind) 并在内部把 kind 置 null，key 一变协程当场取消
    //     → 模型刚开跑就被掐断、转圈永不结束。
    //  2. 上一版换成 MutableSharedFlow(extraBufferCapacity = 1) + tryEmit：
    //     模型一次要跑 30~90 秒，这期间再点一次「帮我写」，tryEmit 因为缓冲满直接
    //     **静默丢弃** —— 用户看到的就是"点了没反应、文本框没东西"（#9）。
    //  3. 生成空结果/报错时什么都不提示，也没法重试。
    // 现在：无界 Channel 排队（点几次就排队生成几次）+ 失败明确提示 + 有内容时可重抽。
    //
    // 4.（新增）「一键帮我写全部」不走新通道，而是往**同一条队列**里塞一个哨兵 AI_KIND_ALL：
    //     队列本身就是串行的，所以批量与单字段永远不会同时占着模型的那 1 个 slot。
    val aiRequests = remember { Channel<String>(Channel.UNLIMITED) }
    // 只让被点的那个字段显示"生成中"，而不是几个按钮一起转圈
    var aiFillingKind by remember { mutableStateOf<String?>(null) }
    // 哪个字段生成失败/返回空（点一下可重试）
    var aiErrorKind by remember { mutableStateOf<String?>(null) }
    // 批量生成进行中（4 个单字段按钮据此一并置灰，避免和批量请求抢 slot）
    val aiBatchRunning = (aiFillingKind == AI_KIND_ALL)
    // 批量生成的收尾提示（成功 / 只解析出部分 / 失败），aiAllFailed 只决定文字颜色
    var aiAllNotice by remember { mutableStateOf<String?>(null) }
    var aiAllFailed by remember { mutableStateOf(false) }
    // 批量生成的总耗时（1 秒一跳，够用又不花哨）
    var aiAllSeconds by remember { mutableIntStateOf(0) }
    // 计时只跟着"当前在生成什么"走：kind 一变（= 结束/换任务）本协程被 LaunchedEffect 取消
    LaunchedEffect(aiFillingKind) {
        aiAllSeconds = 0
        if (aiFillingKind != AI_KIND_ALL) return@LaunchedEffect
        while (true) {
            delay(1000)
            aiAllSeconds++
        }
    }
    val latestAiFiller by rememberUpdatedState(aiFiller)
    val latestAiFillerAll by rememberUpdatedState(aiFillerAll)
    LaunchedEffect(Unit) {
        for (kind in aiRequests) {
            aiFillingKind = kind
            aiErrorKind = null
            if (kind == AI_KIND_ALL) {
                // ---------- 一键写全部：一次请求，4 个字段 ----------
                aiAllNotice = null
                aiAllFailed = false
                // 4 个字段都已有内容 → 用户点的是「全部重新生成」，允许全量覆盖；
                // 否则按"只补空白"，绝不悄悄抹掉用户手填的内容
                val regenerateAll = speakingStyle.isNotBlank() && personality.isNotBlank() &&
                    backstory.isNotBlank() && greeting.isNotBlank()
                val wanted = if (regenerateAll) 4 else listOf(
                    speakingStyle, personality, backstory, greeting
                ).count { it.isBlank() }
                val filled: Map<String, String> = try {
                    withContext(Dispatchers.IO) {
                        latestAiFillerAll?.invoke(name.ifBlank { "？" }, tags, regenerateAll)
                    } ?: emptyMap()
                } catch (e: Exception) {
                    Log.w("CharacterEditor", "AI 一键生成失败: ${e.message}")
                    emptyMap()
                }
                // 回填规则：regenerateAll 时全量覆盖；否则**只填空字段**，
                // 用户手填过的内容一个都不动（宁可少写一项，也不能悄悄抹掉用户写的东西）
                var applied = 0
                val style = filled["speakingStyle"].orEmpty().trim()
                if (style.isNotBlank() && (regenerateAll || speakingStyle.isBlank())) {
                    speakingStyle = style
                    applied++
                }
                val persona = filled["personality"].orEmpty().trim()
                if (persona.isNotBlank() && (regenerateAll || personality.isBlank())) {
                    personality = persona
                    applied++
                }
                val story = filled["backstory"].orEmpty().trim()
                if (story.isNotBlank() && (regenerateAll || backstory.isBlank())) {
                    // 第 11 项：背景故事不再统一成 TA —— 生成出来什么样就什么样
                    // （人称交给模型自己判断，用户想改可以自己编辑）
                    backstory = story
                    applied++
                }
                val hello = filled["greeting"].orEmpty().trim()
                if (hello.isNotBlank() && (regenerateAll || greeting.isBlank())) {
                    greeting = hello
                    applied++
                }
                when {
                    applied == 0 -> {
                        aiAllFailed = true
                        aiAllNotice = "生成失败：模型这次没按要求写出内容，可以再点一次重试"
                    }
                    applied < wanted -> {
                        aiAllNotice = "已生成 $applied/$wanted 项，缺的字段可以单独点「✨ 帮我写」"
                    }
                    else -> aiAllNotice = "已生成 $applied 项文案"
                }
                aiFillingKind = null
                continue
            }
            // 该字段已有内容 → 这次是「重新生成」，让模型换个说法（#10）
            val regenerate = when (kind) {
                "speakingStyle" -> speakingStyle.isNotBlank()
                "personality" -> personality.isNotBlank()
                "backstory" -> backstory.isNotBlank()
                "greeting" -> greeting.isNotBlank()
                else -> false
            }
            val text = try {
                withContext(Dispatchers.IO) {
                    latestAiFiller?.invoke(kind, name.ifBlank { "？" }, tags, regenerate) ?: ""
                }.trim()
            } catch (e: Exception) {
                Log.w("CharacterEditor", "AI 写入失败: ${e.message}")
                ""
            }
            if (text.isBlank()) {
                // 失败就保留原文并显示可重试提示，不再"点了没反应"
                aiErrorKind = kind
            } else {
                when (kind) {
                    "speakingStyle" -> speakingStyle = text
                    "personality" -> personality = text
                    // 第 11 项：背景故事不再统一成 TA，保留模型原本的人称写法
                    "backstory" -> backstory = text
                    "greeting" -> greeting = text
                }
            }
            aiFillingKind = null
        }
    }
    fun requestAiFill(kind: String) { aiRequests.trySend(kind) }
    // 一键写全部：清掉上一次的收尾提示再入队（入队用的是无界 Channel，不会像 tryEmit 那样丢请求）
    fun requestAiFillAll() {
        aiAllNotice = null
        aiAllFailed = false
        aiRequests.trySend(AI_KIND_ALL)
    }

    /**
     * 组装要保存的角色。
     *
     * @param rarityOverride 本轮需求 #1：点「更新」时用建议稀有度覆盖，
     *   点「保持原样」传 null —— 稀有度保持用户当前选的那一档，其余字段照存。
     */
    fun doSave(rarityOverride: Int? = null): Character {
        val finalRarity = rarityOverride ?: selectedRarity
        // 第 3 项（本轮）：角色名上限 10 个字符。输入框已经拦住超长输入，
        // 这里再兜一刀 —— 覆盖"编辑老角色时它本来就是超长名字""从模板带入"这类旁路，
        // 保证落库的名字一定 ≤10 字（同时顺手去掉首尾空格）。
        val finalName = name.trim().take(NAME_MAX_LEN)
        // 第 11 项：保存时**不再**把背景故事里的他/她统一改成 TA ——
        // 以前这一步会把用户手写的"她"也改掉，现在人称原样保留（用户/模型写什么就是什么）。
        val finalBackstory = backstory
        val base = if (isEdit) {
            character!!.copy(
                name = finalName,
                personality = personality,
                speakingStyle = speakingStyle,
                backstory = finalBackstory,
                greeting = greeting,
                tags = tags,
                rarity = finalRarity,
                avatarAsset = avatarPhotoPath
            )
        } else {
            Character(
                id = UUID.randomUUID().toString(),
                name = finalName,
                personality = personality,
                speakingStyle = speakingStyle,
                backstory = finalBackstory,
                greeting = greeting,
                tags = tags,
                rarity = finalRarity,
                // 新建角色：没选照片 → 记「内置默认头像」标记（保存后首页/聊天页都显示图标4）；
                // 选了照片 → 记照片路径（走下面 rename 成 <id>_avatar.jpg）。
                // 存量角色（isEdit）在上面的 copy 分支保持原值不变，一行都不碰。
                avatarAsset = if (avatarPhotoPath.isNullOrBlank()) AVATAR_BUILTIN_DEFAULT else avatarPhotoPath
            )
        }

        // 新建角色时选照片用的是临时 UUID 文件名，保存时统一改成 <角色id>_avatar.jpg：
        // 一个角色只留一张头像文件，反复换图不会在 files/avatars 里堆孤儿文件。
        val path = avatarPhotoPath
        if (path != null && !path.contains(base.id)) {
            val src = File(path)
            if (src.exists()) {
                val target = File(src.parentFile, "${base.id}_avatar.jpg")
                if (src.renameTo(target)) {
                    return base.copy(avatarAsset = target.absolutePath)
                }
            }
        }
        return base
    }

    val tagList = remember(tags) { tags.split(",").map { it.trim() }.filter { it.isNotEmpty() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "编辑角色" else "新建角色", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) } },
                actions = {
                    if (isEdit) {
                        // 导出当前角色为 JSON —— 放在 App 自己的根目录下（和模型目录同级，文件管理器直接可见）
                        IconButton(onClick = {
                            val c = character ?: return@IconButton
                            try {
                                val dir = File(
                                    android.os.Environment.getExternalStorageDirectory(),
                                    "AI角色扮演/导出"
                                ).apply { mkdirs() }
                                val safeName = name.trim().ifBlank { "character" }
                                    .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                                val file = File(dir, "$safeName-${c.id}.json")
                                // 导出的是**编辑器里当前看到的内容**（可能含未保存的改动），字段齐全才是能用的 JSON
                                val json = JSONObject().apply {
                                    put("id", c.id)
                                    put("name", name.trim())
                                    put("personality", personality.trim())
                                    put("speakingStyle", speakingStyle.trim())
                                    put("backstory", backstory.trim())
                                    put("greeting", greeting.trim())
                                    put("tags", tags.trim())
                                    put("rarity", selectedRarity)
                                    put("avatarAsset", avatarPhotoPath ?: "")
                                    put("exportedAt", System.currentTimeMillis())
                                }
                                file.writeText(json.toString(2), Charsets.UTF_8)
                                Toast.makeText(editorContext, "已导出：" + file.absolutePath, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(editorContext, "导出失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Icon(Icons.Default.Share, "导出", tint = Color.White.copy(alpha = 0.7f))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF16161E))
            )
        },
        floatingActionButton = {
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    // 计算建议稀有度
                    val suggestion = suggestRarity(personality, speakingStyle, backstory, greeting, tagList)
                    if (suggestion.value != selectedRarity) {
                        suggestedRarity = suggestion.value
                        showRarityDialog = true
                    } else {
                        onSave(doSave())
                        onBack()
                    }
                },
                modifier = Modifier.height(48.dp),
                // 本轮需求 #4：按钮配色与全 App 主体风格对齐
                // （紫色主色 #9C27B0 + 白色加粗字 + 14dp 圆角，与弹窗按钮同一套视觉）
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF9C27B0),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 30.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Text(
                    text = "保存",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF12121A))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 角色头像预览：编辑器里用「图标4.jpg」作为默认头像（没选照片时显示），
            // 跟首页/聊天页的角色卡头像**无关** —— 保存后回到首页，角色卡头像走
            // 名字哈希 Q 版 / 专属立绘（AvatarGenerator 第 0/1 分支），不会变成这张图。
            // 区分靠 CharacterAvatar 的 isEditorPreview 标志位（编辑器传 true，其它页面传 false）。
            val previewCharacter = remember(name, tags, selectedRarity, avatarPhotoPath, character?.level) {
                Character(
                    id = character?.id ?: "preview",
                    // ⚠️ 这里**必须**把 name 原样透传（空就是空），绝不能再写 `ifBlank { "？" }`：
                    //    编辑器预览要显示内置默认头像（AvatarGenerator 里 -0.5 分支），
                    //    那个分支只在「isEditorPreview=true 且没选照片」时生效，
                    //    名字是否为空不影响这条分支（跟名字完全无关）。
                    name = name,
                    personality = personality,
                    speakingStyle = speakingStyle,
                    backstory = backstory,
                    greeting = greeting,
                    tags = tags,
                    rarity = selectedRarity,
                    level = character?.level ?: 1,
                    avatarAsset = avatarPhotoPath
                )
            }
            Box(modifier = Modifier.size(80.dp)) {
                CharacterAvatar(
                    character = previewCharacter,
                    modifier = Modifier.size(80.dp),
                    showLevel = isEdit,
                    level = character?.level ?: 1,
                    isEditorPreview = true   // 编辑器预览专用：没选照片就显示「图标4.jpg」
                )
                // 右下角"换照片"小按钮
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF9C27B0))
                        .clickable {
                            if (avatarPhotoPath != null) avatarPhotoPath = null
                            else photoPicker.launch(androidx.activity.result.PickVisualMediaRequest())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (avatarPhotoPath != null) Icons.Default.Close else Icons.Default.PhotoCamera,
                        contentDescription = if (avatarPhotoPath != null) "移除照片头像" else "选择照片头像",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (selectedRarity) { 0 -> "普通"; 1 -> "稀有"; 2 -> "史诗"; 3 -> "传说"; 4 -> "神话"; else -> "普通" } + (if (isEdit) " · Lv.${character!!.level}" else ""),
                fontSize = 12.sp,
                color = getRarityColor(selectedRarity)
            )
            Spacer(modifier = Modifier.height(24.dp))

            // ========== 名字（居中） ==========
            OutlinedTextField(
                value = name,
                // 第 3 项（本轮）：角色名最多 10 个字符 —— 超长的直接不接收输入
                // （不是显示报错，而是打不进去，配合下面那行实时字数提示）。
                onValueChange = { if (it.length <= NAME_MAX_LEN) name = it },
                label = { Text("角色名字 *") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                colors = editorFieldColors()
            )
            Text(
                // 第 3 项：实时字数，接近上限时换个颜色提醒一下
                "名字将在首页居中显示（最多 $NAME_MAX_LEN 个字 · 已输入 ${name.length}/$NAME_MAX_LEN）",
                fontSize = 11.sp,
                color = if (name.length >= NAME_MAX_LEN) Color(0xFFE5A33A) else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 16.dp).align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(16.dp))

            // ========== 说话风格 ==========
            // 4 个字段是否都已填写（= 一键按钮的文案在「一键帮我写全部 / 全部重新生成」之间切换）
            val allFilled = speakingStyle.isNotBlank() && personality.isNotBlank() &&
                backstory.isNotBlank() && greeting.isNotBlank()
            // 部分已填：提醒用户"只补空白"，避免以为会全部重写
            val partlyFilled = !allFilled && listOf(
                speakingStyle, personality, backstory, greeting
            ).any { it.isNotBlank() }

            // 「✨ 一键帮我写全部」的位置（用户口径："放在说话风格上边框靠右对齐"）：
            // 单独一行、右对齐到输入框右边框（[AI_ACTION_EDGE_PADDING] = 16dp = 输入框边框所在位置），
            // 并**紧贴输入框上边框** —— 注意 M3 的 OutlinedTextField 带 label 时，
            // 它的**顶部**会内嵌 8dp 空间给 label（`OutlinedTextFieldTopPadding = 8.dp`，
            // 已 javap 反编译 material3-1.1.2.aar 证实），但这一行在输入框**外面**，
            // 所以它到边框的视觉距离比输入框底部「帮我写」那一行到边框的距离**多了 8dp**
            // （A 专员像素实测：13.4px ≈ 7.9dp）。
            //
            // 修正：给这一行加 **top = 8.dp 的 padding**（占布局空间，不遮挡 label），
            // 让它到边框的视觉间距 = 「帮我写」到边框的视觉间距（= 4.5dp，见 L765 的 top=2.dp 注释推导）。
            // 为什么不用 offset：offset 不占空间，会让这一行盖到下面的 label 上。
            //
            // 为什么不用 Box 把按钮叠在输入框右上角（两种叠法都不可行）：
            //  1. 叠在输入框**内部**右上角 → 说话风格是 maxLines = 2 的多行框，用户写长了会顶到最右，
            //     按钮就会和用户自己输入的文字互相遮挡；聚焦时还会压住上边框那根描边。
            //  2. 把按钮塞进 OutlinedTextField 的 label 槽位 → label 一旦浮起来会被缩放到 0.75 并平移，
            //     按钮文字跟着变小、位置漂移；而且 label 的整条宽度会被当成"缺口"切开上边框，边就断了。
            // 放在输入框**外面**的一行则完全不碰 label / placeholder / 光标 / 用户文字；
            // 右边缘对齐由 AI_ACTION_EDGE_PADDING 保证（和输入框边框同一个 16dp）。
            // 高度恒定：这一行只在注入了 aiFillerAll 时出现（编译期注入，运行期不会来回变），
            // 文案在「✨ 一键帮我写全部 / 🔄 全部重新生成 / 生成中…」之间切换时宽度会变，
            // 但它是本行唯一的子项、由 Arrangement.End 顶住右边缘 → 右边缘不动，不会推动任何其它控件。
            if (aiFillerAll != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AI_ACTION_EDGE_PADDING)
                        // 一键在输入框**上方**、帮我写在输入框**下方**，两边到可见边框的距离要恒等。
                        // 结构差：M3 给带 label 的输入框在节点顶部内嵌 8dp（[OUTLINED_FIELD_LABEL_TOP_INSET]，
                        // 可见上边框其实在节点顶往下 8dp 处），而输入框**下方**没有这个内嵌；
                        // 同时「帮我写」行带 [AI_ROW_TOP_PADDING](2dp) top padding、一键这行没有。
                        // 所以补偿量 = 8dp − 2dp（不是 8dp）：下移 8dp 会多补 2dp，一键反而比
                        // 帮我写更贴近边框（真机像素实测 4.1dp vs 6.5dp，差 4px ≈ 2.4dp 即此 2dp）。
                        // 用 offset（渲染位移，不占布局空间）：padding 会把下面整个输入框往下推、
                        // 改变布局高度；offset 只挪这一行的渲染位置，输入框及其它控件纹丝不动。
                        .offset(y = OUTLINED_FIELD_LABEL_TOP_INSET - AI_ROW_TOP_PADDING),
                    horizontalArrangement = Arrangement.End
                ) {
                    AiFillAction(
                        label = if (allFilled) "🔄 全部重新生成" else "✨ 一键帮我写全部",
                        // 批量跑着的时候就地变成转圈 +「生成中…」（和字段自己的按钮同一套转圈/文案切换）
                        aiFilling = aiBatchRunning,
                        // 一键的失败不用红字：整批失败/部分成功都走下面的 aiAllNotice 提示，语义和以前一样
                        error = false,
                        // 只要模型在忙（单字段或批量）就不可点，和原来那个 Button 的 enabled 判定一致
                        enabled = (aiFillingKind == null),
                        fillingLabel = "生成中…",
                        onClick = { requestAiFillAll() }
                    )
                }
            }
            OutlinedTextField(
                value = speakingStyle,
                onValueChange = { speakingStyle = it },
                label = { Text("说话风格") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                maxLines = 2,
                colors = editorFieldColors()
            )
            // 字段自己的「✨ 帮我写」保持原处（输入框下面那一行、右对齐输入框右边框），位置与外观都没动
            AiFillRow(
                label = if (speakingStyle.isBlank()) "✨ 帮我写" else "🔄 重新生成",
                aiFilling = (aiFillingKind == "speakingStyle"),
                error = (aiErrorKind == "speakingStyle"),
                visible = (aiFiller != null),
                enabled = !aiBatchRunning,
                onClick = { requestAiFill("speakingStyle") }
            )
            // 批量生成的耗时 / 只补空白 / 收尾提示：文案和语义完全没变，
            // 只是从"原来那个独立一整行按钮的下方"挪到"说话风格字段（含它自己的按钮行）下方"。
            // 故意不放在一键按钮和输入框之间：那样提示一出现就会把输入框往下顶，属于布局跳动。
            // 加了和原来一样的 16dp 左右边距（原来由包裹它的 Column 提供）。
            if (aiFillerAll != null) {
                if (aiBatchRunning) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "正在生成 4 项文案，本地模型较慢，请稍候…" +
                            (if (aiAllSeconds >= 5) "已用 ${aiAllSeconds} 秒" else ""),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                } else {
                    if (partlyFilled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "已填写的字段会保留，只补齐空白项",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    aiAllNotice?.let { notice ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            notice,
                            fontSize = 11.sp,
                            color = if (aiAllFailed) Color(0xFFEF9A9A) else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ========== 性格 ==========
            OutlinedTextField(
                value = personality,
                onValueChange = { personality = it },
                label = { Text("性格描述") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                maxLines = 3,
                colors = editorFieldColors()
            )
            AiFillRow(
                label = if (personality.isBlank()) "✨ 帮我写" else "🔄 重新生成",
                aiFilling = (aiFillingKind == "personality"),
                error = (aiErrorKind == "personality"),
                visible = (aiFiller != null),
                enabled = !aiBatchRunning,
                onClick = { requestAiFill("personality") }
            )
            Spacer(modifier = Modifier.height(16.dp))

            // ========== 标签 ==========
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("标签（英文逗号分隔）") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("例如：温柔,治愈,青梅竹马") },
                singleLine = true,
                colors = editorFieldColors()
            )
            if (tagList.isNotEmpty()) {
                // 第 16 项：标签过多/过长要换行 —— 以前是一行 Row，第 5 个标签就被挤到屏幕外。
                // 改成流式布局（FlowRow）：一行放不下自动换行；单个标签过长时胶囊内最多折 2 行。
                TagChipFlow(
                    tags = tagList,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ========== 背景故事 ==========
            OutlinedTextField(
                value = backstory,
                onValueChange = { backstory = it },
                label = { Text("背景故事") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                maxLines = 5,
                colors = editorFieldColors()
            )
            AiFillRow(
                label = if (backstory.isBlank()) "✨ 帮我写" else "🔄 重新生成",
                aiFilling = (aiFillingKind == "backstory"),
                error = (aiErrorKind == "backstory"),
                visible = (aiFiller != null),
                enabled = !aiBatchRunning,
                onClick = { requestAiFill("backstory") }
            )
            Spacer(modifier = Modifier.height(16.dp))

            // ========== 开场白 ==========
            OutlinedTextField(
                value = greeting,
                onValueChange = { greeting = it },
                label = { Text("开场白") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                maxLines = 3,
                placeholder = { Text("例：(轻轻拉住你的衣袖) 你终于来了...") },
                colors = editorFieldColors()
            )
            AiFillRow(
                label = if (greeting.isBlank()) "✨ 帮我写" else "🔄 重新生成",
                aiFilling = (aiFillingKind == "greeting"),
                error = (aiErrorKind == "greeting"),
                visible = (aiFiller != null),
                enabled = !aiBatchRunning,
                onClick = { requestAiFill("greeting") }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // ========== 稀有度选择 ==========
            Text("稀有度（由完整度自动建议）", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp).align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Rarity.entries.forEach { rarity ->
                    val color = rarity.color.toColor()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedRarity == rarity.value) color.copy(alpha = 0.3f) else Color(0xFF2A2A3E),
                        modifier = Modifier.weight(1f).height(44.dp).clickable { selectedRarity = rarity.value }
                    ) {
                        Text(
                            text = rarity.label,
                            fontSize = 13.sp,
                            color = if (selectedRarity == rarity.value) color else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            // ========== 稀有度说明 ==========
            Spacer(modifier = Modifier.height(16.dp))
            SettingCard(
                title = "稀有度含义",
                subtitle = """
                    • 普通：基础人设，可正常开始对话
                    • 稀有：人设较完整，对话质量提升
                    • 史诗：背景故事丰富，沉浸感更强
                    • 传说：五要素全部充实，最佳体验
                    • 神话：手动指定的最高评级
                    系统会根据填写内容自动建议最合适的评级。
                """.trimIndent()
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 稀有度建议对话框（统一弹窗样式，需求 #5）
    // 本轮需求 #1：按钮从「应用并保存 / 保持原样」改成「更新 / 保持原样」，语义也更明确 ——
    //   更新   = 把稀有度改成建议档位后再保存
    //   保持原样 = 稀有度不动，其余字段照常保存（点一下就能存上，不再"只关弹窗"）
    if (showRarityDialog) {
        AppConfirmDialog(
            title = "建议稀有度",
            text = "根据角色卡完整度，建议把稀有度设为「${rarityLabel(suggestedRarity)}」。\n" +
                "更新：改用「${rarityLabel(suggestedRarity)}」并保存\n" +
                "保持原样：稀有度仍是「${rarityLabel(selectedRarity)}」，直接保存",
            confirmText = "更新",
            cancelText = "保持原样",
            onConfirm = {
                selectedRarity = suggestedRarity
                onSave(doSave(suggestedRarity))
                onBack()
                showRarityDialog = false
            },
            onDismiss = {
                onSave(doSave())
                onBack()
                showRarityDialog = false
            }
        )
    }
}

/** 稀有度档位 → 中文名（弹窗与稀有度选择器共用一套文案） */
private fun rarityLabel(value: Int): String = when (value) {
    0 -> "普通"
    1 -> "稀有"
    2 -> "史诗"
    3 -> "传说"
    4 -> "神话"
    else -> "普通"
}

/**
 * "✨ 帮我写 / 🔄 重新生成"按钮行（建议 #7、反馈 #5 #10）。
 *
 * - 字段为空 → 「✨ 帮我写」；已有内容 → 「🔄 重新生成」（点了重抽一版，覆盖旧内容）
 * - 生成中显示转圈 + "AI 正在生成…"；失败显示可重试提示（不再"点了没反应"）
 * - 反馈 #5：整行只留 start/end 各 [AI_ACTION_EDGE_PADDING]（16dp）内边距，
 *   与上方输入框的**右边框对齐**（输入框是 `fillMaxWidth().padding(horizontal = 16.dp)`，
 *   边框正好在 16dp 处）；这个常量与「一键帮我写全部」那一行**共用**，
 *   保证两条按钮的文字右边缘到边框逐像素一致（本轮：间距一致的需求，像素位置未变）。
 * - enabled：批量「一键帮我写全部」跑着的时候，4 个单字段入口一并置灰 ——
 *   本机模型只有 1 个 slot，让用户等批量写完再单独补字段，比挤在一起稳。
 * - 「一键帮我写全部」**不在这一行**：它在「说话风格」输入框的上边框靠右
 *   （见 CharacterEditorScreen 里说话风格那一段），两处用的是同一个 [AiFillAction]，
 *   所以样式天然一模一样，以后改样式只改 [AiFillAction] 一处。
 */
@Composable
private fun AiFillRow(
    label: String,
    aiFilling: Boolean,
    error: Boolean,
    visible: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    if (!visible) return
    Row(
        modifier = Modifier
            .padding(start = AI_ACTION_EDGE_PADDING, end = AI_ACTION_EDGE_PADDING, top = AI_ROW_TOP_PADDING)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        AiFillAction(
            label = label,
            aiFilling = aiFilling,
            error = error,
            enabled = enabled,
            onClick = onClick
        )
    }
}

/**
 * 「✨ 帮我写 / 🔄 重新生成 / ✨ 一键帮我写全部」**共用的按钮体**。
 *
 * 为什么抽出来：用户要求一键按钮"样式和帮我写一致"，两处各写一份迟早会走样；
 * 现在所有视觉（转圈大小/配色、字号、字重、内边距、错误红、禁用淡）只在这里定义一次。
 *
 * 视觉口径 = 本文件原来 AiFillRow 里那一套（**不是实心 Button**）：透明底 + 紫色文字链接样式，
 * 没有圆角/底色/固定高度，转圈时是"左侧转圈 + 右侧文案"（同 `verticalAlignment = CenterVertically`）。
 * 「样式和帮我写一致」= 和它逐项相同，所以一键按钮也不再是紫底白字实心块。
 *
 * 高度恒定（本轮补的一处）：两个分支的文字都带上下各 2dp 内边距 → 任何状态的行高都恰好是
 * "单行文字高度 + 4dp"（转圈只有 12dp，永远比文字矮，不会成为最高的那个）。
 * 原先"生成中…"分支没带这个内边距，比文案分支矮 4dp → 一切到生成中，下面的控件会往上跳 4dp；
 * 现在两个分支一样高，[AiFillAction] 单独成行时高度绝对不变。
 *
 * @param fillingLabel 生成中的文案：单字段沿用 "AI 正在生成…"，一键按钮用 "生成中…"
 */
@Composable
private fun AiFillAction(
    label: String,
    aiFilling: Boolean,
    error: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    fillingLabel: String = "AI 正在生成…"
) {
    if (aiFilling) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
            color = Color(0xFFCE93D8)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            fillingLabel,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.5f),
            // 单行 + 省略号：文案在三种状态间切换时行高恒定，窄屏也不会换行/溢出
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            // 和下面文案分支的 clickable 内边距对齐（上下各 2dp）→ 两个分支高度完全相同，
            // 切到"生成中…"时整行高度不变，周围控件不会跳。
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
        )
    } else {
        if (error) {
            Text(
                "生成失败，点右侧重试",
                fontSize = 10.sp,
                color = Color(0xFFEF9A9A),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            label,
            fontSize = 11.sp,
            color = when {
                error -> Color(0xFFEF9A9A)
                // 生成进行中（含批量）：这条入口不可点，文字压暗表示"先等着"
                !enabled -> Color.White.copy(alpha = 0.3f)
                else -> Color(0xFFCE93D8)
            },
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = 6.dp, top = 2.dp, bottom = 2.dp)
        )
    }
}

/** 编辑器输入框统一配色（反馈 #11：输入的字要纯白，占位/标签也别太暗） */
@Composable
private fun editorFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.6f),
    cursorColor = Color(0xFFCE93D8),
    focusedBorderColor = Color(0xFF9C27B0),
    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
    focusedLabelColor = Color(0xFFCE93D8),
    unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.35f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.3f)
)

private fun doSave() {
    // 保存逻辑在调用方处理
}

@Composable
private fun SettingCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

private fun String.toColor(): Color {
    return try {
        Color(android.graphics.Color.parseColor(this))
    } catch (e: Exception) {
        Color.White
    }
}

/**
 * 第 16 项：标签胶囊的流式布局（新建/编辑页预览用）。
 *
 * - 标签**多**：FlowRow 一行放不下就自动换行，不会再被挤到屏幕外；
 * - 标签**长**：胶囊内文字最多折 2 行、超出省略，单个超长标签也不会把页面撑破；
 * - 视觉与「角色详情」弹窗里的标签行完全一致（0xFF2A2A3E 容器 + 白字 12sp）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipFlow(tags: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = tag,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFF2A2A3E),
                    labelColor = Color.White
                )
            )
        }
    }
}
