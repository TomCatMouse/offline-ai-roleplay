package com.example.roleplay.util

/**
 * 文本工具。
 *
 * [truncateMiddle] 用于把过长的模型名压成一行：
 * "Some-Model-7B-Instruct-Q4_K_M.gguf"
 * → "Some-Model-7B-...Q4_K_M.gguf"
 *
 * 中间省略、首尾保留：头能看出模型家族，尾能看出量化档位（.Q4_K_M.gguf 等），
 * 比直接掐掉尾巴的尾省略号信息量大得多。
 */
fun truncateMiddle(s: String, maxLen: Int = 30): String {
    if (s.length <= maxLen) return s
    val ellipsis = "..."
    // maxLen 太小时连省略号都放不下，退化为原样返回交给调用方的 maxLines=1 兜底
    if (maxLen < ellipsis.length + 4) return s
    val headLen = (maxLen - ellipsis.length) / 2 + 1
    val tailLen = maxLen - headLen - ellipsis.length
    return s.take(headLen) + ellipsis + s.substring(s.length - tailLen)
}

/**
 * 本轮需求 #2：「背景故事」里的第三人称代词统一成 **TA**。
 *
 * 角色卡里混用「他 / 她」时读者根本分不清指代谁（模型自己也会跟着乱），
 * 统一写成 TA 最稳。只动人称代词，下面这些"看着像代词其实不是"的词原样保留：
 * 其他 / 吉他 / 他人 / 他乡 / 他日 / 他方 / 他杀 / 利他 / 无他 / 维他。
 *
 * 做法：先把这些词换成占位符藏起来 → 再替换代词 → 最后把占位符放回去。
 * （先替换再还原的顺序很重要：直接替换会把「其他」写成「其TA」。）
 *
 * 例：「她把他的剑递给他，说：其他事以后再说」
 *  → 「TA把TA的剑递给TA，说：其他事以后再说」
 */
fun unifyThirdPerson(raw: String): String {
    if (raw.isBlank()) return raw
    val keep = listOf("其他", "吉他", "他人", "他乡", "他日", "他方", "他杀", "利他", "无他", "维他")
    var t = raw
    val stash = ArrayList<String>()
    keep.forEach { word ->
        while (t.contains(word)) {
            val token = "\u0001${stash.size}\u0002"
            t = t.replaceFirst(word, token)
            stash.add(word)
        }
    }
    t = t.replace("他们", "TA们")
        .replace("她们", "TA们")
        .replace("他/她", "TA")
        .replace("她/他", "TA")
        .replace("他或她", "TA")
        .replace("她或他", "TA")
        .replace("祂", "TA")
        .replace("他", "TA")
        .replace("她", "TA")
    stash.forEachIndexed { index, word -> t = t.replace("\u0001$index\u0002", word) }
    return t
}
