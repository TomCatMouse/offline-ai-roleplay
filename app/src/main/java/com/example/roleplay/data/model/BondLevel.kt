package com.example.roleplay.data.model

/**
 * 羁绊等级 —— 让 `Character.level` 从装饰变成真实机制。
 *
 * 改造前：`level` 是 DemoCharacters 里写死的数字（Lv.5 / Lv.8 …），
 * 永远不变，和任何行为都没有关系。
 *
 * 现在：**聊得越多，羁绊越深**。每 [MESSAGES_PER_LEVEL] 条消息升一级，
 * 等级落库到 `Character.level`，首页卡片和聊天页都会显示。
 *
 * 为什么用消息条数而不是"对话轮数"：一轮 = 用户+AI 两条，
 * 用消息条数做分母能更平滑地推进进度条，用户更容易看到"就快升级了"。
 */
object BondLevel {

    /** 每升一级需要的消息条数（= 6 轮对话） */
    const val MESSAGES_PER_LEVEL = 12

    /** 满级 100 级 */
    const val MAX_LEVEL = 100

    /**
     * 练到满级需要的累计消息数 = 1188 条。
     *
     * 推导：等级从 Lv.1 起步，每 12 条升一级，升到 Lv.100 需要再升 99 级，
     * 即 99 × 12 = 1188 条（跨角色、跨剧情线累计，见 MessageDao.getTotalMessageCount）。
     */
    const val MESSAGES_TO_MAX = (MAX_LEVEL - 1) * MESSAGES_PER_LEVEL

    /** 由累计消息数推算等级 */
    fun levelFor(messageCount: Int): Int {
        if (messageCount <= 0) return 1
        return (1 + messageCount / MESSAGES_PER_LEVEL).coerceAtMost(MAX_LEVEL)
    }

    /** 当前等级内的进度 0f~1f（满级固定满格，否则 1188 条那种整数会显示成空条） */
    fun progress(messageCount: Int): Float {
        if (messageCount <= 0) return 0f
        if (levelFor(messageCount) >= MAX_LEVEL) return 1f
        return (messageCount % MESSAGES_PER_LEVEL) / MESSAGES_PER_LEVEL.toFloat()
    }

    /** 还差多少条消息升级 */
    fun messagesToNext(messageCount: Int): Int {
        if (messageCount <= 0) return MESSAGES_PER_LEVEL
        val rem = messageCount % MESSAGES_PER_LEVEL
        return if (rem == 0) MESSAGES_PER_LEVEL else MESSAGES_PER_LEVEL - rem
    }

    /** 羁绊称号，纯展示用，让等级有情感含义 */
    fun titleFor(level: Int): String = when {
        level >= 40 -> "灵魂羁绊"
        level >= 25 -> "生死之交"
        level >= 15 -> "知己"
        level >= 10 -> "挚友"
        level >= 6 -> "熟人"
        level >= 3 -> "点头之交"
        else -> "初识"
    }
}

/**
 * 稀有度 —— 让 `rarity` 从纯装饰变成**角色卡完整度评级**。
 *
 * 改造前：稀有度是创建者随手选的，和卡片内容毫无关系 ——
 * 一个只有名字的角色也能标"神话"。
 *
 * 现在：提供 [suggestRarity]，按角色卡的信息量给出建议稀有度，
 * 编辑器会在你保存时提示"根据完整度建议调整为 X"。
 * 想手动指定也仍然可以，只是会明确告诉你它的含义。
 *
 * 注意这里写成顶层函数而不是 `Rarity.Companion.suggestFor` ——
 * Kotlin 的 enum **没有隐式 Companion 对象**，那种写法编译不过。
 */
fun suggestRarity(
    personality: String,
    speakingStyle: String,
    backstory: String,
    greeting: String,
    tags: List<String>
): Rarity {
    // 五个维度各占 1 分，信息填得越满评级越高
    var score = 0
    if (personality.trim().length >= 10) score++
    if (speakingStyle.trim().length >= 8) score++
    if (backstory.trim().length >= 40) score++
    if (greeting.trim().length >= 15) score++
    if (tags.size >= 2) score++

    return when (score) {
        5 -> Rarity.LEGENDARY
        4 -> Rarity.EPIC
        3 -> Rarity.RARE
        else -> Rarity.COMMON
    }
}

/** 稀有度的可读说明，设置页/编辑器里给用户看 */
fun Rarity.explain(): String = when (this) {
    Rarity.COMMON -> "信息较简略：性格/风格/背景/开场白只填了一部分"
    Rarity.RARE -> "信息较完整：基础人设齐全"
    Rarity.EPIC -> "信息丰富：人设、背景、开场白都写得比较细"
    Rarity.LEGENDARY -> "信息非常完整：五要素全部充实，可直接开聊"
    Rarity.MYTHIC -> "手动指定的最高评级，不代表自动评定结果"
}
