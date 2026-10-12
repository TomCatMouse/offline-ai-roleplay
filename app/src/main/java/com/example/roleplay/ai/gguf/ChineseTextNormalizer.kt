package com.example.roleplay.ai.gguf

/**
 * 中文文本归一化
 *
 * 小模型（1.7B 级）在角色扮演时会出现两类"脏输出"：
 *   1. 繁体字漂移：會/為/這/們/後 ...
 *   2. 外文标点混入：阿拉伯逗号 "،"、缅甸文 "။" 等
 *
 * 这类问题靠提示词只能降低概率，不能根除，所以在输出侧做一次确定性的字符级归一化。
 * 只做**单字符映射**，不依赖上下文，因此对流式逐 token 输出同样安全。
 */
object ChineseTextNormalizer {

    /**
     * 繁体 → 简体 映射表，按「繁简」成对平铺。
     * 只收录"繁体专用形"，避免误伤简繁同形或一字多义的字（例如"乾""繫"）。
     */
    private const val T2S_PAIRS =
        "會会為为這这們们後后時时樣样說说還还來来個个對对從从開开關关發发现现實实點点與与體体過过頭头見见麼么嗎吗讓让覺觉聽听话话誰谁幾几" +
        "東东車车馬马風风雲云電电長长門门問问間间陽阳陰阴華华歲岁萬万學学國国语语讀读书书写写兒儿愛爱憐怜憶忆懷怀擔担應应该该無无处处別别" +
        "剛剛张张強强聲声響响靜静淚泪濕湿溫温热热燈灯煙烟塵尘劍剑鐵铁銀银鐘钟斷断续续結结終终緣缘織织纖纤飄飘飛飞隨随離离難难雖虽願愿夢梦" +
        "樹树叶叶謝謝药药隻只雙双條条絲丝網网線线將将于于並并裡里面面妳你錯错錢钱鏡镜驚惊顧顾顏颜頻频題题頭头" +
        "機机屬属業业隊队動动勢势進进遠远邊边勝胜樂乐觀观規规計计記记認认識识談谈請请講讲論论議议試试課课調调變变" +
        "專专傳传僅仅師师帶带幫帮歸归島岛傷伤慘惨態态戀恋懼惧圖图圍围園园圓圆場场塊块壞坏壓压鋼钢豐丰龍龙鳳凤鶴鹤鷹鹰" +
        "適适遲迟選选遺遗遞递" +
        "著着丟丢趕赶嘆叹喚唤囑嘱讚赞譽誉於于"

    private val T2S: Map<Char, Char> = buildMap {
        var i = 0
        while (i + 1 < T2S_PAIRS.length) {
            val trad = T2S_PAIRS[i]
            val simp = T2S_PAIRS[i + 1]
            if (trad != simp) put(trad, simp)
            i += 2
        }
    }

    /** 混入的外文标点 → 中文标点 */
    private val PUNCT_FIX: Map<Char, Char> = mapOf(
        '،' to '，',   // 阿拉伯逗号
        '٬' to '，',
        '؛' to '；',
        '؟' to '？',
        '။' to '。',   // 缅甸文句号
        '၊' to '，',   // 缅甸文逗号
    )

    /** 归一化单个字符；不需要改动时原样返回 */
    fun normalizeChar(c: Char): Char = PUNCT_FIX[c] ?: T2S[c] ?: c

    /** 归一化整段文本 */
    fun normalize(text: String): String {
        var needFix = false
        for (c in text) {
            if (T2S.containsKey(c) || PUNCT_FIX.containsKey(c)) {
                needFix = true
                break
            }
        }
        if (!needFix) return text

        val sb = StringBuilder(text.length)
        for (c in text) sb.append(normalizeChar(c))
        return sb.toString()
    }
}
