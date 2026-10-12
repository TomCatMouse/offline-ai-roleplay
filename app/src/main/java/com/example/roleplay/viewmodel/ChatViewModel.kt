package com.example.roleplay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.roleplay.ai.AIService
import com.example.roleplay.data.AppSettings
import com.example.roleplay.data.model.BondLevel
import com.example.roleplay.data.model.Character
import com.example.roleplay.data.model.ChatMessage
import com.example.roleplay.data.model.Conversation
import com.example.roleplay.data.model.DemoCharacters
import com.example.roleplay.data.model.Memory
import com.example.roleplay.data.repository.CharacterRepository
import com.example.roleplay.data.repository.ConversationRepository
import com.example.roleplay.data.repository.MemoryRepository
import com.example.roleplay.data.repository.MessageRepository
import com.example.roleplay.memory.MemoryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val memoryRepo: MemoryRepository,
    private val characterRepo: CharacterRepository,
    private val aiService: AIService,
    private val settings: AppSettings
) : ViewModel() {

    /** 会话列表项（会话表没有 title 字段，标题从首条用户消息现推） */
    data class ConversationSummary(
        val id: String,
        val characterId: String,
        val title: String,
        val updatedAt: Long
    )

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId

    /**
     * 需求 #3：一次推理的状态 —— **绑定在发起它的那条会话（角色）上**。
     *
     * @param characterId    发起推理的角色（首页角色卡靠它亮「思考中」）
     * @param conversationId 发起推理的会话（聊天页靠它决定要不要显示思考/流式气泡）
     * @param sinceMs        本轮推理开始的时刻（思考气泡「已等待 Xs」的起点）
     */
    data class InferenceState(
        val characterId: String,
        val conversationId: String,
        val sinceMs: Long
    )

    /**
     * 需求 #3：**按会话 id 键控**的推理状态表（key = conversationId）。
     *
     * 旧实现是三个全局字段（`_isGenerating` / `_generatingCharacterId` /
     * `_generatingSinceMs`）—— 等于"整个 App 只有一个推理状态"：
     * 甲正在输出时切到乙的聊天界面，乙读到的是甲推进去的那个全局布尔，
     * 于是乙界面也亮「正在思考中」；甲的后台推理结束时又把全局布尔清成 false，
     * 顺手把乙的界面一起改掉。这就是多角色推理状态串扰的根源。
     *
     * 现在状态只挂在**发起推理的那条会话**的键上：
     *   · 聊天页只读自己这条会话的键（见 [isGenerating] / [generatingSinceMs]）；
     *   · 协程的 finally 只摘掉**自己**的键，绝不碰别的会话；
     *   · 写入消息 / 记忆 / 羁绊也一直带着发起时的 conversationId，不会写到别人头上。
     *
     * 底层推理服务只有一个 slot（同一时刻只跑一路），所以这张表**实际最多一项**；
     * 用 Map 是为了让"状态属于谁"在结构上就写清楚，而不是靠一个全局布尔约定。
     */
    private val _inferenceByConversation = MutableStateFlow<Map<String, InferenceState>>(emptyMap())

    /** 全量推理状态（key = conversationId），供诊断 / 后续多路复用使用 */
    val inferenceByConversation: StateFlow<Map<String, InferenceState>> =
        _inferenceByConversation.asStateFlow()

    /**
     * **当前会话**是否正在推理。
     *
     * ⚠️ 名字与类型刻意保持不变：MainActivity 把这条流原样转给 ChatScreen
     * （`isGenerating = isGenerating`）。调用契约一个字没动，所以 MainActivity
     * **已确认无需改动**，串扰就在这一层被收窄掉了。
     * 语义从"全 App 有人在推理"收窄成"当前会话在推理"——切到乙的瞬间它自己变 false，
     * 乙界面就不再显示甲的「正在思考中」了，不用改调用方一行。
     *
     * 用 [SharingStarted.Eagerly] 而不是 WhileSubscribed：ViewModel 内部判断
     * （能否发送 / 能否重新生成）也要读 `.value`，不能依赖 UI 有没有在订阅。
     */
    val isGenerating: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_inferenceByConversation, _currentConversationId) { states, convId ->
            convId != null && states.containsKey(convId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * P8：正在推理的角色 id（null = 没人）。
     *
     * 首页角色卡只给这一个角色亮「思考中」。同 P8 的原意：不是"所有人一起亮"。
     * 单 slot 保证同时最多一条会话在跑，取那一条即可。
     */
    val generatingCharacterId: StateFlow<String?> = _inferenceByConversation
        .map { states -> states.values.firstOrNull()?.characterId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * **当前会话**本轮推理的起点（epoch ms；0 = 当前会话没在推理）。
     *
     * 聊天页「正在思考中 / 已等待 Xs」的计时以它为起点：用户中途退出聊天页再进来，
     * Compose 本地 remember 的秒数会归零重算（旧 bug），改成跟推理周期一起存在
     * ViewModel 里，跨页面进出都接着原来的起点跑。
     * 需求 #3 起它同样按会话收窄：翻着甲的计时切到乙，乙只会拿到 0（不显示计时）。
     */
    val generatingSinceMs: StateFlow<Long> =
        kotlinx.coroutines.flow.combine(_inferenceByConversation, _currentConversationId) { states, convId ->
            convId?.let { states[it]?.sinceMs } ?: 0L
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** 这条会话**自己**是否在推理（内部判断用：直接读源状态，不依赖 UI 订阅） */
    private fun isConversationGenerating(conversationId: String?): Boolean =
        conversationId != null && _inferenceByConversation.value.containsKey(conversationId)

    /**
     * 是否有**别的**会话在推理（需求 #10：决定这一按要不要开第二路推理）。
     *
     * 特意审的是"除了我这条以外的键"，而不是那个收窄后的 [isGenerating]：
     * 在乙界面看，[isGenerating] 是 false（乙自己没在跑），但甲确实在跑，
     * 这时候既不能再开一路（底层单 slot，且正是串扰的来源），也不能把消息丢了。
     */
    private fun isAnotherConversationGenerating(conversationId: String?): Boolean =
        _inferenceByConversation.value.keys.any { it != conversationId }

    /**
     * 第 23 项：聊天输入框里"还没发出去"的草稿。
     *
     * 以前草稿是 ChatScreen 里的 `remember { mutableStateOf("") }` —— 页面一销毁
     * （返回首页、切剧情线、旋屏）就没了，用户写了半天的字全丢。
     * 现在放进 ViewModel（Activity 级存活），返回再进来照样还在。
     */
    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput

    fun setChatInput(text: String) {
        _chatInput.value = text
    }

    /** 发送成功后清空草稿（以及切角色/清空对话时一并清） */
    fun clearChatInput() {
        _chatInput.value = ""
    }

    private val _character = MutableStateFlow<Character?>(null)
    val character: StateFlow<Character?> = _character

    /**
     * 全部会话（跨角色），供对话历史页使用。
     *
     * 直接由 Room 的 Flow 驱动，**不需要手动 refresh** ——
     * 会话表任何变动都会重新发射，标题也会跟着重算。
     * （上一版是手动维护一个 MutableStateFlow，结果忘了一处调用就不同步。）
     */
    val allConversations: StateFlow<List<ConversationSummary>> = conversationRepo.allConversations
        .map { list -> list.toSummaries() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * item1：只含「有聊天记录」的会话，供对话历史页 + 首页「对话」统计。
     *
     * 和 [allConversations] 的区别只有一个：**空剧情线不算一段对话**。
     * 新建剧情线后（还没说话）历史页里不会多出一条"新的对话"，
     * 首页的对话数也不会 +1；发出第一条消息后两边同时出现。
     *
     * 为什么不直接过滤 [allConversations]：它同时喂给 [characterConversations]
     * （聊天页的剧情线切换菜单），空剧情线必须留在菜单里 —— 否则刚点完
     * 「新建剧情线」，菜单里却找不到这一条，用户会以为没建成功。
     */
    val allConversationsWithMessages: StateFlow<List<ConversationSummary>> =
        conversationRepo.allConversationsWithMessages
            .map { list -> list.toSummaries() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 会话实体 → 列表项（补上标题）。
     *
     * 两条 Flow（全部 / 只含有消息的）共用这一份映射，标题规则永远一致。
     */
    private suspend fun List<com.example.roleplay.data.model.Conversation>.toSummaries():
        List<ConversationSummary> = map { conv ->
        ConversationSummary(
            id = conv.id,
            characterId = conv.characterId,
            // M8：用户起过名的剧情线优先用自定义名；没起名才按首条消息推导
            title = conv.title?.takeIf { it.isNotBlank() }
                ?: messageRepo.getConversationTitle(conv.id)
                ?: "新的对话",
            updatedAt = conv.updatedAt
        )
    }

    /**
     * item6：全部消息总数 —— 设置页「我的资料」用它算用户等级与经验条。
     *
     * 等级 = [BondLevel.levelFor](总数)、经验 = [BondLevel.progress](总数)，
     * 和角色羁绊**同一套规则**（每 12 条一级），不另造一套数值体系。
     */
    val totalMessageCount: StateFlow<Int> = messageRepo.totalMessageCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * 当前角色名下的剧情线（会话）列表，供聊天页"剧情线"切换菜单用。
     * 复用 [allConversations] 已经算好的标题，再按当前角色过滤即可，
     * 会话增删改都会自动重发。
     */
    val characterConversations: StateFlow<List<ConversationSummary>> =
        kotlinx.coroutines.flow.combine(_character, allConversations) { c, all ->
            if (c == null) emptyList() else all.filter { it.characterId == c.id }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 反馈 #5（上一轮）起没有"自动朗读"了：朗读只由气泡上的「朗读」图标手动触发，
    // 文本直接从消息里取，所以这里不再需要 speakRequest 这类转发通道。

    /** 一次性提示消息（切换模型、清空历史等） */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /**
     * 当前剧情线的消息列表。
     *
     * 代码审查（反馈 #14）：flatMapLatest 目前仍标着 @ExperimentalCoroutinesApi，
     * 之前一直靠"警告不影响编译"混过去，这里补上显式 opt-in，构建日志保持干净。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessage>> = _currentConversationId
        .flatMapLatest { convId ->
            if (convId != null) messageRepo.getMessages(convId)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 流式内容：key = conversationId。
     * 用 Map 而不是单个 String，是为了在"切走再切回"时不会串台。
     */
    private val _streamingContent = MutableStateFlow<Map<String, String>>(emptyMap())
    val streamingContent: StateFlow<Map<String, String>> = _streamingContent.asStateFlow()

    /** 正在跑的生成任务；**不使用 cancel**，见 [stopGeneration] */
    private var generationJob: Job? = null

    /**
     * 停止信号。
     *
     * 刻意不用 `generationJob.cancel()`：底层是阻塞的 HttpURLConnection，
     * 协程取消中断不了它；而且我们**想保留已经生成的部分**。
     * 所以改成置位这个标志，让 SSE 读循环自己断开并把半成品交回来。
     */
    @Volatile private var stopRequested = false

    // ==================== 会话管理 ====================

    /**
     * 打开某角色的会话：优先恢复"上次查看的剧情线"（需求 #8），
     * 没有记录、或那条已被删除时，回落到最近一条；再没有就新建。
     */
    fun startChat(characterId: String) {
        viewModelScope.launch {
            val char = characterRepo.getCharacter(characterId) ?: return@launch
            // 第 23 项：草稿只在**换角色**时清空；同一角色"返回首页再进来"要保留用户没发出去的字
            if (_character.value?.id != characterId) _chatInput.value = ""
            _character.value = char
            val lastViewed = settings.lastViewedConversation(characterId)
            val existing = lastViewed
                ?.let { conversationRepo.getConversation(it) }
                ?: conversationRepo.getLatestByCharacter(characterId)
            if (existing != null) {
                _currentConversationId.value = existing.id
                settings.rememberConversation(characterId, existing.id)
            } else {
                createConversation(characterId)
            }
        }
    }

    /** 显式开一条新剧情线（不影响旧的那条） */
    fun newConversation() {
        val characterId = _character.value?.id ?: return
        viewModelScope.launch {
            createConversation(characterId)
            _toast.emit("已开启新剧情线")
        }
    }

    private suspend fun createConversation(characterId: String) {
        val conversation = Conversation(
            id = java.util.UUID.randomUUID().toString(),
            characterId = characterId
        )
        conversationRepo.insertConversation(conversation)
        _currentConversationId.value = conversation.id
        // 新建的剧情线立即成为"最后查看"，下次进入角色时恢复它（需求 #8）
        settings.rememberConversation(characterId, conversation.id)
    }

    fun openConversation(conversationId: String) {
        _currentConversationId.value = conversationId
        // 切换剧情线 → 记住，下次再进这个角色时优先恢复这条（需求 #8）
        _character.value?.let { c -> settings.rememberConversation(c.id, conversationId) }
    }

    /**
     * 从"对话历史"页点进某条剧情线（需求 #8）。
     *
     * 不用 [startChat]：它按"最后查看"重新解析会话，会和用户明确点选的
     * 这条会话**打架**（先 set 旧的、startChat 的协程后跑又把当前切走）。
     * 这里直接钉死角色 + 会话，并把它记为该角色的"最后查看"。
     */
    fun openStoryline(characterId: String, conversationId: String) {
        viewModelScope.launch {
            val char = characterRepo.getCharacter(characterId) ?: return@launch
            _character.value = char
            settings.rememberConversation(characterId, conversationId)
            _currentConversationId.value = conversationId
        }
    }

    /**
     * 删除一条剧情线（会话）。
     *
     * G1 修复：旧版依赖 [_character]（当前聊天的角色）取 characterId —— 从「对话历史」页
     * 进来删剧情线时 _character 可能是 null，整句被 `?: return` 吞掉，
     * 删除静默失败、剧情线列表里那条还在。现在从会话记录本身取角色 id，谁删都能删。
     */
    /**
     * @param showToast 删完是否弹底部 Snackbar。对话历史页自己用顶部药丸提示
     *                  （M3：和复制弹窗同一套样式），调它时传 false 避免双重提示。
     */
    fun deleteConversation(conversationId: String, showToast: Boolean = true) {
        viewModelScope.launch {
            val conv = conversationRepo.getConversation(conversationId) ?: return@launch
            val characterId = conv.characterId
            messageRepo.deleteMessages(conversationId)
            conversationRepo.deleteConversation(conversationId)
            if (_currentConversationId.value == conversationId) {
                // 当前会话没了 → 切到该角色最近一条。
                // N1 修复：一条都没有就置 null，**不再自动补一条新线**——
                // 旧版删完立刻冒出一条「新的对话」，用户看着就像"删不掉"。
                // 聊天页遇到 null 会显示空对话；用户下次发消息时由
                // [sendMessage] 现场补一条（那时新线是用户自己"聊"出来的，合理）。
                val next = conversationRepo.getLatestByCharacter(characterId)
                if (next != null) {
                    _currentConversationId.value = next.id
                    settings.rememberConversation(characterId, next.id)
                } else {
                    _currentConversationId.value = null
                }
            }
            if (showToast) _toast.emit("已删除该剧情线")
        }
    }

    /** M8：给用户起的剧情线改名；空字符串 = 清掉自定义名（回到按首条消息推导） */
    fun renameConversation(conversationId: String, title: String) {
        viewModelScope.launch {
            conversationRepo.renameConversation(conversationId, title.ifBlank { null })
            _toast.emit(if (title.isBlank()) "已恢复默认名称" else "剧情线已重命名")
        }
    }

    // ==================== 长期记忆（建议 #3：按角色查看/编辑/删除）====================

    /** 某角色的全部记忆（新 → 旧），管理页直接观察 */
    fun memoriesFor(characterId: String): Flow<List<Memory>> =
        memoryRepo.getMemories(characterId)

    /** 修改一条记忆的内容 */
    fun updateMemory(memory: Memory, newContent: String) {
        viewModelScope.launch {
            memoryRepo.updateMemory(memory.copy(content = newContent))
            _toast.emit("记忆已更新")
        }
    }

    /** 删除一条记忆 */
    fun deleteMemory(memory: Memory) {
        viewModelScope.launch {
            memoryRepo.deleteMemory(memory.id)
            _toast.emit("记忆已删除")
        }
    }

    // 代码审查（反馈 #14）：renameConversation 是个永远只弹"暂未支持"的空壳，
    // 全工程没有任何调用点，已删除。真要做重命名得给 Conversation 加 title 字段并升级 schema。

    // ==================== 发送 / 生成 ====================

    /**
     * 发送一条用户消息。
     *
     * 需求 #3 / #10：判断"能不能发"只看**当前这条会话**，而不是全局是否有人在推理 ——
     * 甲在后台输出时，乙界面照样能输入、能发送；而"要不要真的开一路推理"才看全局：
     * 已经有别的会话在跑时**只入库、不启动第二路**（底层单 slot，硬开就是再次串扰）。
     *
     * @return true  = 已按常规流程处理（消息入库；当前会话空着就立刻启动推理）
     *         false = 因为**另一条会话正在推理**，消息已入库但本轮不再启动推理。
     *                 ChatScreen 据此在输入框上方弹一颗「暂不支持同步会话」药丸。
     *                 其它情况（空输入、没有当前角色、当前会话自己在跑）都返回 true ——
     *                 那些不是"跨会话互斥"，不该弹那句提示。
     */
    fun sendMessage(content: String, action: String? = null): Boolean {
        val character = _character.value ?: return true
        if (content.isBlank()) return true
        // 当前会话自己在推理：输入框此时本来就是禁用的，这里只是兜底。
        // 注意命名避开同名的属性 currentConversationId（那是给 UI 的 StateFlow）。
        val convIdAtSend = _currentConversationId.value
        if (isConversationGenerating(convIdAtSend)) return true

        // 需求 #10：这一按是不是"跨会话互斥"要在**发起之前同步定下来**，
        // 否则协程挂起期间甲刚好跑完，判断结果就飘了。
        val blockedByOtherChat = isAnotherConversationGenerating(convIdAtSend)

        // 需求 #10（真机反馈优化）：被跨会话互斥挡下时**整件事都不做** ——
        // 消息不入库、不建新会话、不启动推理，草稿由 UI 原样留在输入框里，
        // 只在页面**上方**弹一颗药丸说明原因，等对方跑完再点一次发送即可。
        // （旧行为是"消息已入库但不生成回复"，用户明确要求改成"根本不要发出去"。）
        if (blockedByOtherChat) {
            android.util.Log.i(
                "ChatViewModel",
                "另一条会话正在推理，本次发送不处理（消息不入库、草稿保留）：$convIdAtSend"
            )
            return false
        }

        viewModelScope.launch {
            // N1：删光剧情线后 _currentConversationId 会是 null，
            // 直接 return 就变成"发不出消息"了 —— 这里现场补一条新线再继续。
            val conversationId = _currentConversationId.value
                ?: run {
                    createConversation(character.id)
                    _currentConversationId.value!!
                }

            // 先取历史（此刻还不含当前这句），再把用户消息入库。
            // 若顺序反过来，当前输入会在"历史"和"当前消息"里各出现一次，
            // 模型会以为用户在重复提问。
            val history = messageRepo.getAllMessages(conversationId)

            // 需求 #10：消息**一句都不丢**，照常入库（下面这段不管有没有被挡住都会执行）
            messageRepo.insertMessage(
                ChatMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "user",
                    content = content,
                    action = action
                )
            )

            if (blockedByOtherChat) {
                // 真机反馈优化后这里已经是死分支（blockedByOtherChat 在进入协程前就返回了），
                // 保留只为防御将来有人在协程里重新计算这个值。
                return@launch
            }

            runGeneration(conversationId, character, history, content)
        }
        return true
    }

    // 代码审查（反馈 #14）：原来的 regenerateLast（删掉最后一条回复再重跑）
    // 唯一的调用点是长按菜单，菜单删掉后就没人用了；现在"重新生成"统一走
    // regenerateReply(助手消息 id)，那条路径已经覆盖了"最后一条"的情况。

    /**
     * 停止生成：保留已生成的部分（用户按停止通常是"够了"，不是"丢弃"）。
     *
     * 需求 #3：只在**当前会话**真的在推理时才按下去 —— 切到乙之后乙界面没有停止按钮，
     * 这里多一道判断，杜绝"在乙界面误触却掐掉甲的后台推理"这类跨角色误伤。
     */
    fun stopGeneration() {
        if (!isConversationGenerating(_currentConversationId.value)) return
        stopRequested = true
        // 反馈 #8：光设标志位要等 SSE 循环转到下一次迭代才生效，
        // prefill 阶段可能几十秒都不返回。这里直接掐断底层连接 —— 按下即停。
        runCatching { aiService.cancelGeneration() }
    }

    /**
     * 真正跑一次生成。
     *
     * 需求 #3：状态**同步**登记在 [conversationId] 的键上（对外立刻可见），
     * 协程结束时也**只摘掉自己这个键** —— 甲的后台推理完成/报错/中断，
     * 都只允许动甲自己的消息与状态，一个字都不会写到乙头上。
     *
     * @param history  历史消息（**不含**本轮用户消息）
     * @param userText 本轮用户输入
     */
    private fun runGeneration(
        conversationId: String,
        character: Character,
        history: List<ChatMessage>,
        userText: String
    ) {
        stopRequested = false
        // 先登记（同步），再启动协程：UI 的「正在思考中」和"这条会话占着推理位"
        // 从这一刻起就成立，不给"连点两次发送开两路"留时间窗。
        val state = InferenceState(
            characterId = character.id,
            conversationId = conversationId,
            sinceMs = System.currentTimeMillis()
        )
        _inferenceByConversation.value = _inferenceByConversation.value + (conversationId to state)
        _streamingContent.value = _streamingContent.value + (conversationId to "")

        generationJob = viewModelScope.launch {
            try {
                // 长期记忆：取该角色的记忆，按与当前输入的相关度挑几条注入角色卡
                val allMemories = memoryRepo.getRecentMemories(character.id, 60)
                val relevant = MemoryManager().getRelevantMemories(allMemories, userText, 6)

                val sb = StringBuilder()
                val reply = aiService.generateResponse(
                    character = character,
                    userMessage = userText,
                    history = history,
                    memories = relevant,
                    onToken = { token ->
                        sb.append(token)
                        _streamingContent.value =
                            _streamingContent.value + (conversationId to sb.toString())
                    },
                    shouldStop = { stopRequested },
                    clientTag = conversationId   // 同一会话固定 client_id，钉住 slot 复用 KV 缓存
                )

                val text = reply.trim()
                _streamingContent.value = _streamingContent.value - conversationId

                if (text.isNotEmpty() && settings.current.autoSaveHistory) {
                    messageRepo.insertMessage(
                        ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "assistant",
                            content = text,
                            action = null
                        )
                    )
                    conversationRepo.updateConversation(
                        Conversation(
                            id = conversationId,
                            characterId = character.id,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    maybeExtractMemories(conversationId, character.id)
                    updateBondLevel(character)
                }
                // 反馈 #3：手动停止后不再弹任何提示——用户自己按的停止，
                // 不需要再被一条"已停止"打扰（未产生内容就安静地结束）。
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "推理失败: ${e.message}", e)
                _streamingContent.value = _streamingContent.value - conversationId
                messageRepo.insertMessage(
                    ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = "⚠️ 推理失败：${e.message}\n请检查推理服务是否可用。",
                        action = null
                    )
                )
            } finally {
                // 需求 #3：只摘掉**自己这条会话**的键。旧版这里是
                // `_isGenerating.value = false` 这样的全局清零 —— 甲跑完会把
                // 乙界面一起改掉（串扰）；现在按会话摘键，谁的推理谁收尾。
                _inferenceByConversation.value = _inferenceByConversation.value - conversationId
                _streamingContent.value = _streamingContent.value - conversationId
            }
        }
    }

    /**
     * 每 6 条消息做一次记忆抽取。
     *
     * 不是每轮都抽：抽取本身很便宜，但**写入频率低一些能避免记忆库被
     * 同一件事的多种说法塞满**，反正长期记忆本来就是粗粒度的。
     */
    private suspend fun maybeExtractMemories(conversationId: String, characterId: String) {
        try {
            val all = messageRepo.getAllMessages(conversationId)
            if (all.size < 4 || all.size % 6 != 0) return

            val extracted = MemoryManager().extractMemories(all.takeLast(12), characterId)
            if (extracted.isEmpty()) return

            val existing = memoryRepo.getRecentMemories(characterId, 80)
            val known = existing.map { it.content }.toSet()
            val merged = MemoryManager().mergeMemories(existing, extracted)

            merged.filter { it.content !in known }.forEach { memoryRepo.insertMemory(it) }
            android.util.Log.i("ChatViewModel", "记忆抽取：新增 ${merged.count { it.content !in known }} 条")
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "记忆抽取失败: ${e.message}")
        }
    }

    /**
     * 按累计消息数刷新羁绊等级。
     *
     * 等级是**算出来的**（每 12 条消息一级），不是独立累加的状态 ——
     * 这样即使在别处删了消息，等级也会自动回落，不会出现"删光了还是 Lv.20"。
     */
    private suspend fun updateBondLevel(character: Character) {
        try {
            val count = messageRepo.countByCharacter(character.id)
            val newLevel = BondLevel.levelFor(count)
            if (newLevel == character.level) return

            characterRepo.updateLevel(character.id, newLevel)
            // 同步内存里的角色对象：否则下一轮比较用的还是旧等级，
            // 会导致升级提示反复弹、或者升级了却不提示。
            // 需求 #3：**只在这条角色仍然是当前聊天角色时**才写 _character ——
            // 旧版无条件写，甲的后台推理完成时若用户已经切到乙，就会把整个聊天页
            // 硬拽回甲（跨角色串扰的另一种表现：改的是"当前角色"这个全局指针）。
            if (_character.value?.id == character.id) {
                _character.value = character.copy(level = newLevel)
            }

            if (newLevel > character.level) {
                _toast.emit(
                    "🎉 与「${character.name}」的羁绊升到 Lv.$newLevel · ${BondLevel.titleFor(newLevel)}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "羁绊等级更新失败: ${e.message}")
        }
    }

    // ==================== 消息级操作 ====================

    fun deleteMessage(messageId: String) {
        viewModelScope.launch { messageRepo.deleteMessage(messageId) }
    }

    // 反馈 #1：长按菜单（编辑 / 删除这条及之后）已整体删除，
    // 于是 updateMessage / deleteMessageAndAfter 两个入口也一并移除，避免留死代码。
    // 数据层（MessageRepository / MessageDao）的对应方法保留，后续要用随时能接。

    /**
     * 「重新生成」某条助手回复（反馈 #3）。
     *
     * 语义 = 复制这条回复对应的那句用户输入 → 模拟重新发送 → 重答：
     * 把"那句用户输入及其之后"的消息全部删掉，再用同样的内容重跑一次生成。
     * 这样历史里不会留下"两个版本的回答"。
     *
     * 若这条回复前面没有用户消息（角色开场白），就按空输入重跑一次，
     * 让开场白也能换一版。
     *
     * 注意入参是**助手消息**的 id：早先版本让调用方传用户消息 id，
     * 与内部"往前找上一条用户消息"的逻辑正好错位，导致点了没反应。
     *
     * 需求 #3：这里的"在跑就不许重入"只看**当前会话自己** —— 甲在后台输出时，
     * 乙界面该能点的照样能点。
     *
     * @return false = 另一条会话正在推理，这一按什么都不会做（UI 侧弹「暂不支持同步会话」）；
     *         其余情况返回 true。
     */
    fun regenerateReply(assistantMessageId: String): Boolean {
        val conversationId = _currentConversationId.value ?: return true
        val character = _character.value ?: return true
        if (isConversationGenerating(conversationId)) return true
        // 需求 #10：另一条会话正在推理 → 不启动第二路
        if (isAnotherConversationGenerating(conversationId)) return false

        viewModelScope.launch {
            val all = messageRepo.getAllMessages(conversationId)
            val idx = all.indexOfFirst { it.id == assistantMessageId }
            if (idx < 0) {
                _toast.emit("这条消息已经不在对话里了")
                return@launch
            }
            // 这条回复前面最近的一条用户消息 = "我最新输入的内容"
            val userIdx = (0 until idx).lastOrNull { !all[it].isAssistant }
            val history = all.take(userIdx ?: idx)
            val userMsg = userIdx?.let { all[it] }
            val text = userMsg?.getFullText() ?: ""

            all.drop(userIdx ?: idx).forEach { messageRepo.deleteMessage(it.id) }
            if (userMsg != null) {
                messageRepo.insertMessage(
                    ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "user",
                        content = text,
                        action = userMsg.action
                    )
                )
            }
            runGeneration(conversationId, character, history, text)
        }
        return true
    }

    /**
     * 「编辑后重答」（反馈 #4）：把这条用户消息改成新内容、删掉它之后的回复，再重新生成。
     * 与"只改文本不重跑"不同，这里会真的重跑模型。
     *
     * 需求 #3：同上，重入判断只看当前会话自己。
     *
     * @return false = 另一条会话正在推理，这一按**什么都不会做**（消息不会被改、
     *         后面的回复也不会被删）——UI 侧弹「暂不支持同步会话」，草稿与编辑来源都留着，
     *         等对方跑完再点一次即可。其余情况返回 true。
     */
    fun editAndResend(messageId: String, newContent: String): Boolean {
        val conversationId = _currentConversationId.value ?: return true
        val character = _character.value ?: return true
        if (isConversationGenerating(conversationId)) return true
        if (newContent.isBlank()) return true
        // 需求 #10：另一条会话正在推理 → 不启动第二路，也**不留下半改的状态**
        if (isAnotherConversationGenerating(conversationId)) return false

        viewModelScope.launch {
            val all = messageRepo.getAllMessages(conversationId)
            val idx = all.indexOfFirst { it.id == messageId }
            if (idx < 0) {
                _toast.emit("这条消息已经不在对话里了")
                return@launch
            }
            val history = all.take(idx)
            messageRepo.updateMessage(all[idx].copy(content = newContent))
            all.drop(idx + 1).forEach { messageRepo.deleteMessage(it.id) }
            runGeneration(conversationId, character, history, newContent)
        }
        return true
    }

    /**
     * 清空当前对话（「更多」菜单里的「清空当前对话」）。
     *
     * G1：旧版只删消息、留着会话记录 —— 消息清完，剧情线菜单里那条线还挂着
     * （变成空壳「新的对话」）。现在连会话记录一起删，并立即开一条新线，
     * 聊天页直接可用，剧情线列表也不会留下删掉的条目。
     */
    fun clearHistory() {
        val conversationId = _currentConversationId.value
        val character = _character.value
        if (conversationId == null) {
            viewModelScope.launch { _toast.emit("当前没有可清空的对话") }
            return
        }
        viewModelScope.launch {
            messageRepo.deleteMessages(conversationId)
            conversationRepo.deleteConversation(conversationId)
            character?.let { createConversation(it.id) }
            _toast.emit("已清空当前对话")
        }
    }

    /** 清空该角色的长期记忆 */
    fun clearMemories() {
        val characterId = _character.value?.id
        if (characterId == null) {
            viewModelScope.launch { _toast.emit("当前没有角色，无法清空记忆") }
            return
        }
        viewModelScope.launch {
            memoryRepo.clearMemories(characterId)
            _toast.emit("已清空该角色的长期记忆")
        }
    }

    /**
     * 清除**所有**角色的全部对话（全部会话 + 全部消息）。
     * 设置页全局入口用；比 [clearHistory] 的范围大一圈，UI 侧必须带确认弹框。
     */
    fun showToast(msg: String) {
        viewModelScope.launch { _toast.emit(msg) }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            messageRepo.deleteAllMessages()
            conversationRepo.deleteAllConversations()
            // N1：全部清完就真的全空 —— 当前指针置 null 即可，
            // 不再自动补一条（同 [deleteConversation]，避免"清不掉"的观感）。
            // 之后在聊天页发消息，[sendMessage] 会现场补线。
            _currentConversationId.value = null
            _toast.emit("已清除所有对话")
        }
    }

    /** 清空**所有**角色的长期记忆。设置页全局入口用。 */
    fun clearAllMemories() {
        viewModelScope.launch {
            memoryRepo.clearAllMemories()
            _toast.emit("已清空所有长期记忆")
        }
    }

    fun getMemoryCount(characterId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            onResult(memoryRepo.getRecentMemories(characterId, 200).size)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRequested = true
        // 需求 #3：ViewModel 没了，按会话登记的推理状态一起清掉，
        // 不给下一个实例/下一个界面留一条"幽灵思考中"。
        _inferenceByConversation.value = emptyMap()
    }
}

class CharacterViewModel(
    private val repository: CharacterRepository,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val memoryRepo: MemoryRepository
) : ViewModel() {

    /** 建议 #5：JSON 备份 / 恢复 */
    private val backupManager = com.example.roleplay.data.backup.BackupManager(
        repository, conversationRepo, messageRepo, memoryRepo
    )

    suspend fun exportBackup(): String = backupManager.exportJson()

    suspend fun importBackup(json: String): com.example.roleplay.data.backup.BackupManager.RestoreResult =
        backupManager.importJson(json)

    val allCharacters: StateFlow<List<Character>> = repository.allCharacters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<Character>> = repository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 角色 id → 累计消息数。首页据此画羁绊等级与进度条。
     *
     * 用一条聚合查询算出来，而不是把等级当成独立可变状态去维护 ——
     * 派生数据永远不会和事实（消息表）脱节。
     */
    val bondCounts: StateFlow<Map<String, Int>> = messageRepo.getMessageCountsByCharacter()
        .map { list -> list.associate { it.characterId to it.messageCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun getCharacter(id: String): StateFlow<Character?> = repository.allCharacters
        .map { chars -> chars.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleFavorite(character: Character) {
        viewModelScope.launch {
            repository.updateFavorite(character.id, !character.favorite)
        }
    }

    fun seedDemoCharacters() {
        viewModelScope.launch {
            // 只在库为空时（首次安装）种 demo 角色。
            // 旧版每次启动都 REPLACE 重插，createdAt 被反复刷新，
            // 导致用户自建角色的创建时间戳比 demo 新，首页排序被顶到底部。
            val existing = repository.allCharacters.first()
            if (existing.isEmpty()) {
                repository.insertCharacters(DemoCharacters.characters)
            }
        }
    }

    fun saveCharacter(character: Character) {
        viewModelScope.launch {
            repository.insertCharacter(character)
        }
    }

    /**
     * 删除角色并级联清理它名下的数据：
     * 消息 → 会话 → 长期记忆 → 角色本身。
     * 顺序不能乱：先清掉引用方，最后删被引用方，避免孤儿数据。
     */
    fun deleteCharacterCascade(character: Character) {
        viewModelScope.launch {
            messageRepo.deleteByCharacter(character.id)
            conversationRepo.deleteByCharacter(character.id)
            memoryRepo.clearMemories(character.id)
            repository.deleteCharacter(character)
        }
    }
}
