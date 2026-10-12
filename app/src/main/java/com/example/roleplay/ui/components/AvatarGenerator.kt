package com.example.roleplay.ui.components

import androidx.compose.foundation.Image
// 注意：androidx.compose.foundation.Canvas（可组合函数）与本文件已导入的
// androidx.compose.ui.graphics.Canvas（位图工厂）**同名**，直接一起导入会冲突，
// 所以这里给可组合那个起个别名。
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import com.example.roleplay.R
import com.example.roleplay.data.model.Character
import android.content.Context
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Q 版（ちび / chibi）动漫头像生成器。
 *
 * 设计要点：
 *  - 大头小身的 Q 版比例：头部占满画面约 3/4，肩部只在底部露一点点。
 *  - 每个角色有独立设计（发色 / 发型 / 瞳色 / 配饰），而不是只按性别套同一个模板。
 *  - 用户自建角色没有预设：**已命名**的用「名字哈希」稳定推导一套风格，
 *    保证同一角色每次进来头像一致、且不同角色之间互相区分；
 *    **未命名**（新建角色）的用内置的固定默认头像图片（`avatar_default`，
 *    见 [CharacterAvatar] 与 [AVATAR_DEFAULT_RES]），不再走哈希。
 *  - 不依赖任何图片资源，纯 Canvas 绘制，任意分辨率都清晰。
 */

// ============================================================
//  风格定义
// ============================================================

/** 发型 */
enum class ChibiHair {
    LONG,        // 长直发（及胸）
    TWIN_TAIL,   // 双马尾
    PONYTAIL,    // 单马尾
    SHORT,       // 短发
    LONG_MENS,   // 男性长发（披肩）
    BUN          // 盘发 / 发髻
}

/** 配饰 */
enum class ChibiAccessory { NONE, HAIRPIN, RIBBON, GLASSES, SWORD, FLOWER, STAR }

/**
 * 一个角色的完整头像风格
 *
 * @param hair      发色（主色）
 * @param hairDark  发色暗部（做层次，避免一片死色）
 * @param eye       瞳色
 * @param skin      肤色
 * @param hairStyle 发型
 * @param accessory 配饰
 * @param bgTop     背景渐变起始色（一般跟稀有度走）
 * @param bgBottom  背景渐变结束色
 */
data class ChibiStyle(
    val hair: Color,
    val hairDark: Color,
    val eye: Color,
    val hairStyle: ChibiHair,
    val accessory: ChibiAccessory = ChibiAccessory.NONE,
    val skin: Color = Color(0xFFFFE6D5),
    val blush: Color = Color(0xFFFF9BA6),
    val bgTop: Color = Color(0xFF9C27B0),
    val bgBottom: Color = Color(0xFF4A148C)
)

// ============================================================
//  角色 → 风格
// ============================================================

/** 稀有度对应的背景渐变 */
private fun rarityGradient(rarity: Int): Pair<Color, Color> = when (rarity) {
    0 -> Color(0xFF90A4AE) to Color(0xFF546E7A)   // 普通 - 青灰
    1 -> Color(0xFF42A5F5) to Color(0xFF1565C0)   // 稀有 - 蓝
    2 -> Color(0xFFBA68C8) to Color(0xFF6A1B9A)   // 史诗 - 紫
    3 -> Color(0xFFFFB74D) to Color(0xFFE65100)   // 传说 - 金橙
    4 -> Color(0xFFEF5350) to Color(0xFFB71C1C)   // 神话 - 红
    else -> Color(0xFF78909C) to Color(0xFF455A64)
}

/** 稀有度描边色 */
fun getRarityColor(rarity: Int): Color = when (rarity) {
    0 -> Color(0xFF9E9E9E)
    1 -> Color(0xFF2196F3)
    // G12：史诗调亮一档（原来 #9C27B0 = App 主强调紫，文字场景下不好辨认）
    2 -> Color(0xFFCE93D8)
    3 -> Color(0xFFFF9800)
    4 -> Color(0xFFF44336)
    else -> Color.White
}

/**
 * 解析角色头像风格。
 *
 * 优先级：预设角色 > 标签推断 > 名字哈希兜底
 */
fun resolveChibiStyle(character: Character): ChibiStyle {
    val (bgTop, bgBottom) = rarityGradient(character.rarity)
    val base = { s: ChibiStyle -> s.copy(bgTop = bgTop, bgBottom = bgBottom) }

    // ---------- 1) 预设角色：手工设计，各自有辨识度 ----------
    when (character.name) {
        // 温柔青梅竹马：栗色及腰长发 + 侧分刘海 + 浅茶瞳 + 小发夹
        "林小夏" -> return base(
            ChibiStyle(
                hair = Color(0xFF8D6E63), hairDark = Color(0xFF5D4037),
                eye = Color(0xFFA1887F), hairStyle = ChibiHair.LONG,
                accessory = ChibiAccessory.HAIRPIN
            )
        )
        // 傲娇学霸：黑色短发 + 圆框眼镜 + 蓝瞳 + 微蹙眉
        "张学长" -> return base(
            ChibiStyle(
                hair = Color(0xFF37474F), hairDark = Color(0xFF21272B),
                eye = Color(0xFF3F72C4), hairStyle = ChibiHair.SHORT,
                accessory = ChibiAccessory.GLASSES
            )
        )
        // 元气妹系：粉色双马尾 + 蝴蝶结 + 大粉瞳
        "苏婉儿" -> return base(
            ChibiStyle(
                hair = Color(0xFFF48FB1), hairDark = Color(0xFFD81B60),
                eye = Color(0xFFE91E63), hairStyle = ChibiHair.TWIN_TAIL,
                accessory = ChibiAccessory.RIBBON
            )
        )
        // 高冷剑客：墨色披肩长发 + 深紫瞳 + 背后长剑
        "墨尘" -> return base(
            ChibiStyle(
                hair = Color(0xFF263238), hairDark = Color(0xFF0F1418),
                eye = Color(0xFF7E57C2), hairStyle = ChibiHair.LONG_MENS,
                accessory = ChibiAccessory.SWORD
            )
        )
        // 异世界转生：橙色短双马尾 + 呆毛 + 星星发饰
        "星野铃" -> return base(
            ChibiStyle(
                hair = Color(0xFFFFA726), hairDark = Color(0xFFEF6C00),
                eye = Color(0xFF29B6F6), hairStyle = ChibiHair.TWIN_TAIL,
                accessory = ChibiAccessory.STAR
            )
        )
        // 古风才女：黑长直 + 发髻 + 发簪 + 紫瞳
        "沈清秋" -> return base(
            ChibiStyle(
                hair = Color(0xFF3E2723), hairDark = Color(0xFF1B1108),
                eye = Color(0xFF9575CD), hairStyle = ChibiHair.BUN,
                accessory = ChibiAccessory.HAIRPIN
            )
        )
    }

    // ---------- 2) 标签推断 ----------
    val tags = character.tags
    val styleFromTags = when {
        tags.contains("可爱") || tags.contains("妹系") ->
            ChibiHair.TWIN_TAIL to ChibiAccessory.RIBBON
        tags.contains("古风") || tags.contains("优雅") ->
            ChibiHair.BUN to ChibiAccessory.FLOWER
        tags.contains("武侠") || tags.contains("高冷") ->
            ChibiHair.LONG_MENS to ChibiAccessory.SWORD
        tags.contains("傲娇") || tags.contains("学霸") ->
            ChibiHair.SHORT to ChibiAccessory.GLASSES
        tags.contains("元气") || tags.contains("欢乐") ->
            ChibiHair.PONYTAIL to ChibiAccessory.STAR
        tags.contains("温柔") || tags.contains("治愈") ->
            ChibiHair.LONG to ChibiAccessory.HAIRPIN
        else -> null
    }

    // ---------- 3) 兜底配色 / 造型 ----------
    // 历史背景（上一轮已定位的 bug）：还没起名时 name 是空串，hashCode() 恒为 0 →
    // 发色/瞳色/发型/配饰全部命中第 0 号，而 ChibiAccessory 的第 0 号正是 NONE，
    // 生成出来又秃又空。上一轮的修法是喂一个固定占位名 "新角色" 让哈希有值。
    //
    // 但"有值"不等于"好看"：实测 "新角色".hashCode() = 26143888 →
    //   发色 = hairPalette[1] 墨黑 0xFF37474F + 0xFF21272B（一片深灰，沉闷）
    //   瞳色 = eyePalette[3] 青碧 0xFF26A69A
    //   发型 = ChibiHair[0] LONG（及胸长直发，无呆毛）
    //   配饰 = ChibiAccessory[3] GLASSES（圆框眼镜）
    // 也就是「墨黑长直 + 眼镜」的书卷气造型 —— 用户反馈「默认搭配不好看，想换更讨喜的」。
    //
    // 本轮改为：**未命名 = 人工精选一套固定造型，不再由哈希决定**。
    //
    // ⚠️ 最新决定（用户）：未命名角色的默认头像改成**内置图片** `avatar_default`
    //    （MuMu 共享文件夹「图标4.jpg」），由 [CharacterAvatar] 在进入本函数之前就返回了。
    //    所以下面这套精选造型现在只作为**兜底**保留：只有默认头像图片解码失败时才会用到它。
    //    保留而不删的理由：多一层兜底不会崩、也方便将来回退；它对已命名角色完全无影响。
    //
    // 精选值（每一项都是读完整套渲染代码后挑的）：
    //   · 发色 栗色 0xFF8D6E63 / 暗部 0xFF5D4037 —— 暖调柔和，不抢眼，和默认肤色 0xFFFFE6D5 同色系
    //   · 瞳色 清透蓝 0xFF3F72C4 —— 中饱和蓝，明亮干净但**不刺眼**（不是荧光色、也不是大粉瞳）
    //   · 发型 ChibiHair.TWIN_TAIL（双马尾）—— 完整度最高的一档：
    //       后层 = 包住脸的大圆 + 左右两条外撇长马尾（drawChibiHairBack:713-738），
    //       前层走 drawChibiBangs 的 else 分支拿到「齐刘海 + 呆毛」（:869-898），
    //       三重结构叠起来最"满"。刻意避开 SHORT（后脑只有贴头小圆 + 三个短弧刘海，
    //       就是用户说的「偏秃/偏短」）与 LONG（上一轮哈希恰好命中的那一档，配墨黑发偏冷）。
    //   · 配饰 ChibiAccessory.RIBBON（左前侧粉蝴蝶结 0xFFEC407A + 浅粉结心 0xFFF8BBD0）
    //       —— 配饰是最后绘制的，压在头发最上层不会被刘海盖住；栗色发上粉结对比清楚；
    //       绝不用 ChibiAccessory[0] = NONE（那正是"又秃又空"的元凶）。
    // 整体效果 = 「齐刘海 + 呆毛 + 双马尾 + 蝴蝶结」，Q 版里最讨喜、辨识度最高的一套。
    //
    // 取舍说明：
    //  ① `ifBlank { "新角色" }` 这个"伪随机种子"写法已被彻底删除，换成下面的 isUnnamed 分支，
    //     不再有"占位名"这种东西；未命名每次都是**同一套**（常量返回，天然确定性，不会随机跳）。
    //  ② 已命名角色仍然原样走 character.name.hashCode()，且种子字符串没有任何改动
    //     → 哈希路径逐字节不变，现有角色头像 100% 不会发生视觉变动。
    //  ③ 未命名角色**保留**上面第 2 步的「标签推断」优先级：用户在编辑器里先勾了
    //     「可爱/古风/武侠」等标签但还没起名时，发型与配饰继续听标签的（同样是确定性的）；
    //     只有标签没命中时，那四个值才落到这套精选造型上。这样既不破坏"标签挑风格"的功能，
    //     又保证"全新空白角色"一定是那套讨喜的固定搭配。
    val isUnnamed = character.name.isBlank()
    val h = character.name.hashCode()
    val hairPalette = listOf(
        Color(0xFF8D6E63) to Color(0xFF5D4037),   // 栗
        Color(0xFF37474F) to Color(0xFF21272B),   // 墨黑
        Color(0xFFF48FB1) to Color(0xFFD81B60),   // 樱粉
        Color(0xFFFFA726) to Color(0xFFEF6C00),   // 蜜橙
        Color(0xFF9575CD) to Color(0xFF5E35B1),   // 薰衣草
        Color(0xFF4DB6AC) to Color(0xFF00796B),   // 青碧
        Color(0xFFFFD54F) to Color(0xFFF9A825)    // 金
    )
    val eyePalette = listOf(
        Color(0xFF3F72C4), Color(0xFFE91E63), Color(0xFF7E57C2),
        Color(0xFF26A69A), Color(0xFFEF6C00), Color(0xFFA1887F)
    )
    // 未命名 → 精选栗色；已命名 → 名字哈希（与改动前完全一致）
    val (hair, hairDark) = if (isUnnamed) {
        Color(0xFF8D6E63) to Color(0xFF5D4037)
    } else {
        hairPalette[abs(h) % hairPalette.size]
    }
    // 未命名 → 精选清透蓝瞳；已命名 → 哈希取模
    val eye = if (isUnnamed) Color(0xFF3F72C4) else eyePalette[abs(h / 7) % eyePalette.size]
    // 发型 / 配饰：标签优先（两条路径共用），标签没命中时才分叉
    val hairStyle = styleFromTags?.first
        ?: (if (isUnnamed) ChibiHair.TWIN_TAIL
            else ChibiHair.values()[abs(h / 13) % ChibiHair.values().size])
    val accessory = styleFromTags?.second
        ?: (if (isUnnamed) ChibiAccessory.RIBBON
            else ChibiAccessory.values()[abs(h / 17) % ChibiAccessory.values().size])

    return base(
        ChibiStyle(
            hair = hair, hairDark = hairDark, eye = eye,
            hairStyle = hairStyle, accessory = accessory
        )
    )
}

// ============================================================
//  对外组件
// ============================================================

/** 头像位图缓存边长（px）。最大用到 56dp，xxhdpi 下 168px，192 足够清晰。 */
private const val AVATAR_BITMAP_PX = 192

/** 头像位图 LRU 缓存：同一角色 + 同一套样式只光栅化一次。
 *  用同步包装是因为 [warmAvatarCache] 会在后台线程预热。 */
private val avatarBitmapCache: MutableMap<String, ImageBitmap> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 32
        }
    )

/** 缓存键。composable 与预热逻辑必须用同一套构造方式，否则会各存一份。 */
private fun avatarKey(style: ChibiStyle, rarity: Int, showLevel: Boolean, level: Int): String =
    "${style.hashCode()}|$rarity|$showLevel|$level"

/**
 * 立绘头像的位图缓存（drawable 资源 id -> ImageBitmap）。
 *
 * 每张立绘是 256x256 的 PNG，解码一次约占 256KB。立绘数量固定（6 张），
 * 常驻内存完全可接受，而**图像解码比绘制贵得多** —— 不缓存的话，
 * 列表滚动时每次重组都会重新解码，那才是真正的卡顿来源。
 */
private val artBitmapCache: MutableMap<Int, ImageBitmap> = java.util.concurrent.ConcurrentHashMap()

/**
 * 未命名（新建）角色默认头像的素材。
 *
 * 素材来源与入库前的处理（**原始文件没有被改过**，只是复制出来重做了一份）：
 *  - 原始文件：MuMu 模拟器共享文件夹 `/mnt/shared/MuMuShared/图标4.jpg`
 *    （客机里叫「图标4.jpg」，1024×1024 px，116110 bytes）。
 *    它是一张应用图标式的构图：**圆角方卡 + 四周留白**，右下角还有「百度 AI生成」水印。
 *  - 直接圆形裁剪会露白：实测沿 360° 射线扫描，白色留白最内侧只到半径 **430px**
 *    （178° 方向），而内切裁剪圆半径是 512px —— 原图直接 `clip(CircleShape)`
 *    会在四个斜角裁出白块（已渲染验证，不是推测）。
 *  - 所以入库前做了两步（离线处理，代码里不再做二次缩放）：
 *      ① **居中裁剪**到 819×819（= 放大 k=1.25 取中；k 的理论下限是 512/430=1.191，
 *         取 1.25 是为白卡柔边/抗锯齿留 20px 余量），去掉全部白边与水印；
 *      ② 等比缩到 **512×512**（长边 512，与本文件 [loadAvatarPhoto] 的采样上限一致），
 *         JPEG q=92 → 81005 bytes（原 116110 bytes，体积降到 70%）。
 *  - 放 `res/drawable-nodpi/`：与本项目既有立绘 avatar_1..7.png 同一目录，
 *    照片类素材走 nodpi 才不会被 Android 按设备密度放大（见 [loadDrawableAvatar]）。
 *
 * 注意这里是 `val` 而不是 `const val`：AGP 8 起资源 id 默认可能是**非 final**
 * （`android.nonFinalResIds`），对非 final 的 R 字段用 `const val` 会直接编译不过
 * （"Const 'val' initializer should be a constant value"）。用普通 val 两种配置都安全。
 */
private val AVATAR_DEFAULT_RES = R.drawable.avatar_default

/**
 * 「内置默认头像」的持久化标记。
 *
 * 产品口径（用户明确）：
 *  - **新建角色**没选照片 → 保存时把 [Character.avatarAsset] 记成这个标记，
 *    首页卡片 / 聊天页头像一律显示内置「图标4.jpg」（[AVATAR_DEFAULT_RES]）；
 *  - 新建角色**选了照片** → [Character.avatarAsset] 记照片路径，保存后显示那张照片；
 *  - **存量角色**（头像一直是 null）→ 一行不动，继续走立绘 / 名字哈希 Q 版；
 *    只有进编辑页**主动换了照片**才会把 avatarAsset 写成照片路径。
 *
 * 为什么用标记而不是改 Room 表：`avatarAsset` 本就是 `String?`，写一个约定的
 * 非路径字符串即可区分「内置默认」和「用户照片」，零迁移、零 schema 变更。
 * 这个值绝不会是真实文件路径 → [loadAvatarPhoto] 会因 `File(...).exists()==false`
 * 返回 null，天然不会误走照片分支，两条路径互不干扰。
 */
const val AVATAR_BUILTIN_DEFAULT = "builtin_default"

/**
 * 内置 drawable 头像的位图缓存（默认头像只有一个资源，与 [artBitmapCache] 同一思路：
 * 解码远比绘制贵，不缓存的话每次重组都要重新解码）。
 */
private val drawableAvatarCache: MutableMap<Int, ImageBitmap> = java.util.concurrent.ConcurrentHashMap()

/**
 * 解码内置 drawable 素材（目前只有未命名角色的默认头像 [AVATAR_DEFAULT_RES]）。
 * 任何失败都返回 null，由调用方回落到生成的 Q 版头像，绝不崩。
 *
 * 两个要点：
 *  1. `inScaled = false`：即使素材被挪到 `res/drawable/`（无密度后缀 = mdpi），
 *     默认解码也会按设备密度**放大**（xxhdpi ×3 → 1536×1536，约 9MB 常驻内存）。
 *     头像是"照片"，要按像素用，必须关掉密度缩放。当前放在 drawable-nodpi 下本就不会缩放，
 *     这里再显式关一次，避免将来有人挪目录就悄悄吃内存。
 *  2. `inSampleSize` 上限 512：与 [loadAvatarPhoto] 完全一致的口径，
 *     解码后常驻内存约 1MB（512×512×4B）。
 */
private fun loadDrawableAvatar(context: Context, resId: Int): ImageBitmap? {
    drawableAvatarCache[resId]?.let { return it }
    return try {
        // 先量尺寸（同样关掉密度缩放，否则量到的是放大后的边长，采样倍数会算错）
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        android.graphics.BitmapFactory.decodeResource(context.resources, resId, bounds)
        var sample = 1
        val target = 512
        while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
            sample *= 2
        }
        val bitmap = android.graphics.BitmapFactory.decodeResource(
            context.resources,
            resId,
            android.graphics.BitmapFactory.Options().apply {
                inScaled = false
                inSampleSize = sample
            }
        ) ?: return null
        bitmap.asImageBitmap().also { drawableAvatarCache[resId] = it }
    } catch (e: Exception) {
        android.util.Log.w("AvatarGenerator", "默认头像解码失败，回落到生成的 Q 版头像: ${e.message}")
        null
    }
}

/**
 * 预热头像位图缓存。
 *
 * 解决「滚动时头像一闪一闪」里"闪"的另一半成因：
 * 新卡片刚进视口时如果才现场光栅化，首帧可能来不及画完，
 * 视觉上就是头像空一下再出现。提前把两种尺寸都画好，滚动时只剩贴图。
 */
fun warmAvatarCache(characters: List<Character>) {
    for (character in characters) {
        // 有专属立绘的角色走贴图路径，不会用到 Q 版头像，预热纯属浪费
        if (characterAvatarRes(character.name) != 0) continue

        val style = resolveChibiStyle(character)
        // 列表/详情用带等级角标的版本，聊天页用不带的版本，两种都预热
        for (showLevel in booleanArrayOf(false, true)) {
            val key = avatarKey(style, character.rarity, showLevel, character.level)
            if (!avatarBitmapCache.containsKey(key)) {
                // 先在局部画完再入缓存，避免其他线程取到画了一半的位图
                val bitmap = renderAvatarBitmap(
                    style = style,
                    rarity = character.rarity,
                    showLevel = showLevel,
                    level = character.level,
                    px = AVATAR_BITMAP_PX
                )
                avatarBitmapCache[key] = bitmap
            }
        }
    }
}

/**
 * 把整张头像画到当前 DrawScope（原来直接写在 Canvas 里的内容）。
 *
 * 尺寸由外部给定，内部只用比例，因此可以安全地画到任意大小的离屏位图上。
 */
private fun DrawScope.drawChibiAvatar(
    style: ChibiStyle,
    rarity: Int,
    showLevel: Boolean,
    level: Int
) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)

    // ---- 背景 ----
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(style.bgTop, style.bgBottom),
            center = Offset(c.x, c.y * 0.7f),
            radius = r * 1.25f
        ),
        radius = r,
        center = c
    )
    // 背景柔光
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(c.x - r * 0.3f, c.y - r * 0.45f),
            radius = r * 0.9f
        ),
        radius = r,
        center = c
    )

    // Q 版几何：头很大，肩只露一点
    val headCenter = Offset(c.x, c.y - r * 0.06f)
    val headR = r * 0.60f

    drawChibiBody(style, c, r, headCenter, headR)
    drawChibiHairBack(style, headCenter, headR, r)
    drawChibiHead(style, headCenter, headR)
    // 本轮清理：drawChibiBangs 用不到整图半径 r（内部全部按 headR 换算），
    // 未用参数已删除，这里同步去掉实参。
    drawChibiBangs(style, headCenter, headR)
    drawChibiEyes(style, headCenter, headR)
    drawChibiBrowsAndBlush(style, headCenter, headR)
    drawChibiMouth(style, headCenter, headR)
    drawChibiAccessory(style, headCenter, headR, r)

    // ---- 稀有度描边 + 等级点（与立绘头像共用同一套覆盖层）----
    drawAvatarOverlay(rarity, showLevel, level)
}

/**
 * 头像覆盖层：稀有度描边 + 右下角等级点。
 *
 * 程序生成的 Q 版头像和真实立绘头像都走这个函数，保证两种头像观感统一。
 */
private fun DrawScope.drawAvatarOverlay(rarity: Int, showLevel: Boolean, level: Int) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)

    // ---- 稀有度描边 ----
    drawCircle(
        color = getRarityColor(rarity).copy(alpha = 0.85f),
        radius = r - 1.5f,
        center = c,
        style = Stroke(width = r * 0.075f)
    )

    // ---- 等级点 ----
    if (showLevel && level > 1) {
        val badge = Offset(size.width - r * 0.32f, size.height - r * 0.32f)
        drawCircle(Color(0xFF1DE9B6), radius = r * 0.30f, center = badge)
        drawCircle(Color(0xFF00695C), radius = r * 0.30f, center = badge, style = Stroke(width = r * 0.06f))
        drawCircle(Color.White, radius = r * 0.13f, center = badge)
    }
}

/**
 * 角色专属立绘头像映射：角色名 -> drawable 资源。
 *
 * 素材放在项目根目录 `asset/`，由 `tools/prepare_assets.py` 统一处理：
 *   抠掉白色背景（从四边泛洪填充，保住衣服/高光里的白）
 *   -> 按内容裁切并外扩成正方形 -> 圆形裁切 -> 导出 256x256 PNG
 *   -> `res/drawable-nodpi/avatar_N.png`
 *
 * 换素材只需重跑脚本再更新这张表。
 * 表里没有的角色会自动回退到程序生成的 Q 版头像，所以新增角色不必改这里。
 */
private val characterArtRes: Map<String, Int> = mapOf(
    // ── 女角 ──
    "林小夏" to R.drawable.avatar_1,   // 素材：图标.jpg
    "苏婉儿" to R.drawable.avatar_2,   // 素材：图标1.jpg
    "沈清秋" to R.drawable.avatar_3,   // 素材：图标2.jpg
    "星野铃" to R.drawable.avatar_7,   // 素材：图标7.jpg
    // ── 男角 ──
    "张学长" to R.drawable.avatar_5,   // 素材：图标5.jpg
    "墨尘" to R.drawable.avatar_6,     // 素材：图标6.jpg
    // 备用：avatar_4（素材 图标4.jpg）暂无归属，留给以后新增角色。
    // 想换哪张，只改这里一行即可，不必动其他代码。
)

/** 取角色立绘资源 id；没有则返回 0，调用方回退到 Q 版头像。 */
fun characterAvatarRes(name: String): Int = characterArtRes[name] ?: 0

/**
 * 用户（玩家）自己的聊天头像资源。
 *
 * 用的是 asset 里配完 6 个角色后多余的那张素材（图标4.jpg -> avatar_4.png），
 * 已经由 prepare_assets.py 抠掉白底并做过圆形裁切。
 */
private val userAvatarRes: Int = R.drawable.avatar_4

/**
 * 把 [image] 以**居中裁剪**（等价 ContentScale.Crop）画满当前画布：
 * 先等比缩放到铺满目标框，再取正中那一块 —— 绝不改变长宽比。
 *
 * 为什么要有这个函数：之前四处都是把整张位图直接拉伸到 dstSize，用户从相册选的
 * 竖构图/横构图照片会被压成正方形，看起来就是「头像变形」（角色自定义头像同理）。
 * 方形素材（立绘、Q 版头像）走同一函数结果与以前完全一致。
 */
private fun DrawScope.drawCenterCroppedImage(image: ImageBitmap) {
    val dstW = size.width
    val dstH = size.height
    if (dstW <= 0f || dstH <= 0f) return
    val srcW = image.width
    val srcH = image.height
    if (srcW <= 0 || srcH <= 0) return
    val scale = maxOf(dstW / srcW, dstH / srcH)
    val cropW = (dstW / scale).roundToInt().coerceIn(1, srcW)
    val cropH = (dstH / scale).roundToInt().coerceIn(1, srcH)
    drawImage(
        image = image,
        srcOffset = IntOffset((srcW - cropW) / 2, (srcH - cropH) / 2),
        srcSize = IntSize(cropW, cropH),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
        filterQuality = FilterQuality.Medium
    )
}

/**
 * 用户（玩家）在聊天页的头像。
 *
 * 原来是「蓝色圆 + 👤 emoji」的占位符，现在换成真实立绘。
 * 走的是和角色立绘同一套位图缓存（[artBitmapCache]），不会重复解码。
 *
 * 本轮需求 #5：设置页可以给自己换头像了 —— 传了 [photoPath]
 * （AppSettings.userAvatarPath，指向 filesDir/avatars/user_avatar.jpg）
 * 就优先显示照片，没设置就回落到内置立绘。
 *
 * @param modifier 尺寸，默认 36dp，与角色头像一致
 * @param photoPath 自定义头像的本地路径，null / 文件不存在时用内置立绘
 */
@Composable
fun UserAvatar(modifier: Modifier = Modifier.size(36.dp), photoPath: String? = null) {
    val context = LocalContext.current
    // 用户照片：和角色照片共用 loadAvatarPhoto（512px 采样 + 缓存）。
    // 同一路径被覆盖（重新选头像）时靠 lastModified 触发重新解码。
    val photo = remember(photoPath, photoPath?.let { java.io.File(it).lastModified() }) {
        loadAvatarPhoto(photoPath)
    }
    if (photo != null) {
        ComposeCanvas(
            modifier = modifier.clip(CircleShape).semantics { contentDescription = "我的头像" }
        ) {
            // 居中裁剪而不是整张拉伸：竖构图照片不会再被压成正方形
            drawCenterCroppedImage(photo)
            drawCircle(
                color = Color.White.copy(alpha = 0.16f),
                radius = size.minDimension / 2f - 1.5f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = size.minDimension * 0.045f)
            )
        }
        return
    }
    val art = remember(userAvatarRes) {
        artBitmapCache[userAvatarRes] ?: BitmapFactory
            .decodeResource(context.resources, userAvatarRes)
            .asImageBitmap()
            .also { artBitmapCache[userAvatarRes] = it }
    }
    // 兜底的内置立绘也要圆形裁剪：否则方形位图的四边会直接露出来
    // （反馈：设置页头像能明显看到四边形的边），描边 alpha 降一档更不刺眼
    ComposeCanvas(
        modifier = modifier.clip(CircleShape).semantics { contentDescription = "我的头像" }
    ) {
        drawCenterCroppedImage(art)
        // 一圈淡描边：让头像在深色气泡旁有清晰轮廓，
        // 视觉重量与角色头像的稀有度描边保持一致。
        drawCircle(
            color = Color.White.copy(alpha = 0.16f),
            radius = size.minDimension / 2f - 1.5f,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = size.minDimension * 0.045f)
        )
    }
}

/**
 * 角色头像。
 *
 * 有专属立绘（见 [characterArtRes]）就用立绘，否则用程序生成的 Q 版头像。
 * 两条路径共用同一套覆盖层（稀有度描边 + 等级点），保证观感统一。
 *
 * @param character 角色数据
 * @param modifier  尺寸，默认 56dp（圆形）
 * @param showLevel 是否显示右下角等级点
 * @param level     等级
 */
/**
 * 照片头像解码 + 小缓存。
 * 路径为空或文件不存在时返回 null（回落到 Q 版/立绘）。
 * 用 LruCache 防止列表滚动时同一张图反复解码。
 */
private val avatarPhotoCache = object {
    val map = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    fun get(path: String): android.graphics.Bitmap? = map[path]

    fun put(path: String, bitmap: android.graphics.Bitmap) {
        if (map.size >= 32) {
            // 简易 LRU：满了清一半
            map.keys.take(map.size / 2).forEach { map.remove(it) }
        }
        map[path] = bitmap
    }
}

/**
 * 本轮需求 #5：用户给自己换了头像后，同名文件被覆盖，但 [avatarPhotoCache]
 * 里还留着旧位图 —— 不清掉的话设置页/聊天页会一直显示旧头像。
 * 传 null 清空全部（头像整体重置时用）。
 */
fun clearAvatarPhotoCache(path: String? = null) {
    if (path == null) avatarPhotoCache.map.clear() else avatarPhotoCache.map.remove(path)
}

fun loadAvatarPhoto(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    val file = java.io.File(path)
    if (!file.exists()) return null
    val cached = avatarPhotoCache.get(path)
    if (cached != null && !cached.isRecycled) return cached.asImageBitmap()
    // 先量尺寸再按 512px 上限采样解码，头像场景省内存
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    val target = 512
    while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
        sample *= 2
    }
    val bitmap = android.graphics.BitmapFactory.decodeFile(
        file.absolutePath,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    avatarPhotoCache.put(path, bitmap)
    return bitmap.asImageBitmap()
}

@Composable
fun CharacterAvatar(
    character: Character,
    modifier: Modifier = Modifier.size(56.dp),
    showLevel: Boolean = false,
    level: Int = character.level,
    /**
     * 仅「新建角色」编辑器的预览传 true：此时角色还没保存，没选照片就显示
     * 内置默认头像「图标4.jpg」（见下方 -0.5 分支注释）。
     * 首页角色卡 / 聊天页头像传 false（默认）→ 一律走名字哈希 Q 版 / 专属立绘，
     * 与编辑器预览的默认头像**完全无关**（用户明确要求的口径）。
     */
    isEditorPreview: Boolean = false
) {
    // ---- -1) 用户自己选的照片头像最优先（需求 #1：编辑页/首页/聊天页头像一致）----
    // avatarAsset 存的是文件绝对路径（编辑器照片选择器写入 filesDir/avatars/）。
    // 本轮需求 #5 的真机测试抓到的 bug：重新选图时路径不变（还是 <id>_avatar.jpg），
    // 只 remember(path) 的话文件换了也不会重新解码，界面一直是旧照片 ——
    // key 里带上文件的 lastModified，文件一变立刻重读。
    val photo = remember(character.avatarAsset, character.avatarAsset?.let { java.io.File(it).lastModified() }) {
        loadAvatarPhoto(character.avatarAsset)
    }
    if (photo != null) {
        ComposeCanvas(
            modifier = modifier.clip(CircleShape).semantics { contentDescription = character.name }.testTag("character_avatar")
        ) {
            drawCenterCroppedImage(photo)
            drawAvatarOverlay(character.rarity, showLevel, level)
        }
        return
    }

    // ---- -0.5) 内置默认头像（「图标4.jpg」）----
    // 用户产品口径（最终）：
    //   · 新建角色、没选照片 → 保存时 avatarAsset 写成 [AVATAR_BUILTIN_DEFAULT]，
    //     首页卡片 / 聊天页头像都显示这张内置图；选了照片 → 以照片为准。
    //   · 存量角色（avatarAsset 为 null）一行都不动 → 继续走下面第 0/1 分支（立绘 / Q 版）。
    //   · 编辑器里「还没保存的新建角色」（isEditorPreview=true 且 avatarAsset 还是空）
    //     也要先显示内置图，跟保存后的观感一致（用户「新建时看到的就是图标4」）。
    //
    // 命中条件两种：
    //   ① 已保存角色 avatarAsset == [AVATAR_BUILTIN_DEFAULT]（持久化标记，首页/聊天页都认）；
    //   ② 编辑器预览态 isEditorPreview=true 且还没选照片（avatarAsset 空）。
    // 渲染方式与"用户自选照片头像"（上面 -1 分支）**完全同一条路径**：
    //   clip(CircleShape) 圆形成像（圆外一律裁掉，绝不超出圆形外框）
    //   + drawCenterCroppedImage 居中裁剪（方形素材等价铺满，不改长宽比 → 不变形）
    //   + drawAvatarOverlay 复用同一套稀有度描边 / 等级角标
    // 因此 56dp 首页卡片、80dp 编辑页预览、聊天页 3 处尺寸都自动一致，也没有白边。
    val useBuiltinDefault =
        character.avatarAsset == AVATAR_BUILTIN_DEFAULT ||
            (isEditorPreview && character.avatarAsset.isNullOrBlank())
    if (useBuiltinDefault) {
        val context = LocalContext.current
        val defaultAvatar = remember { loadDrawableAvatar(context, AVATAR_DEFAULT_RES) }
        if (defaultAvatar != null) {
            ComposeCanvas(
                modifier = modifier.clip(CircleShape)
                    // 名字为空时（新建角色预览）语义描述不能是空字符串（否则读屏只报"按钮"）。
                    // 兜底文案与 AppDataLerts 里"未命名角色"的口径一致。
                    .semantics { contentDescription = character.name.ifBlank { "未命名角色" } }
                    .testTag("character_avatar")
            ) {
                drawCenterCroppedImage(defaultAvatar)
                drawAvatarOverlay(character.rarity, showLevel, level)
            }
            return
        }
        // 解码失败（资源缺失等极端情况）→ 不崩，继续往下走立绘 / 生成 Q 版头像兜底
    }

    // ---- 0) 有专属立绘就优先用立绘 ----
    // avatar_N.png 已由脚本完成透明背景与圆形裁切，这里只需贴图 + 画覆盖层。
    val artRes = remember(character.name) { characterAvatarRes(character.name) }
    if (artRes != 0) {
        // 用 BitmapFactory 手动解码而不是 imageResource()：
        // 后者在当前 Compose 版本里不可用；手动解码还能顺手接上自己的缓存表。
        val context = LocalContext.current
        val art = remember(artRes) {
            artBitmapCache[artRes] ?: BitmapFactory
                .decodeResource(context.resources, artRes)
                .asImageBitmap()
                .also { artBitmapCache[artRes] = it }
        }
        ComposeCanvas(
            // Q 版路径用的是 Image(contentDescription = ...)，本身带无障碍语义；
            // 立绘路径换成 Canvas 后语义会丢，这里补回来，保证读屏体验一致、
            // 自动化测试也能用 content-desc 精确定位头像。
            modifier = modifier.semantics { contentDescription = character.name }.testTag("character_avatar")
        ) {
            drawCenterCroppedImage(art)
            drawAvatarOverlay(character.rarity, showLevel, level)
        }
        return
    }

    // ---- 1) 风格解析只做一次 ----
    // 原来每次重组都会重新跑一遍 resolveChibiStyle（含多次字符串比较与哈希取模），
    // 而列表滚动和流式输出时重组极其频繁。
    val style = remember(character.name, character.tags, character.rarity) {
        resolveChibiStyle(character)
    }

    // ---- 2) 光栅化成位图后缓存 ----
    // 头像原本是一棵纯 Canvas 绘制：一次要执行几十条 draw 指令，并分配若干
    // Brush/ColorFilter 对象。列表滚动、流式输出期间重组极其频繁，
    // 缓存成 ImageBitmap 后，重组只剩一次贴图；
    // 配合 [warmAvatarCache] 预热，新卡片进视口时也不必现场绘制。
    val cacheKey = remember(style, character.rarity, showLevel, level) {
        avatarKey(style, character.rarity, showLevel, level)
    }
    val bitmap = remember(cacheKey) {
        // 先画到局部变量再入缓存，避免并发预热时读到画了一半的位图
        avatarBitmapCache[cacheKey] ?: renderAvatarBitmap(
            style = style,
            rarity = character.rarity,
            showLevel = showLevel,
            level = level,
            px = AVATAR_BITMAP_PX
        ).also { avatarBitmapCache[cacheKey] = it }
    }

    Image(
        bitmap = bitmap,
        contentDescription = character.name,
        modifier = modifier.testTag("character_avatar")
    )
}

/** 离屏光栅化一张头像位图。内部不使用 dp，所以用 Density(1f) 即可。 */
private fun renderAvatarBitmap(
    style: ChibiStyle,
    rarity: Int,
    showLevel: Boolean,
    level: Int,
    px: Int
): ImageBitmap {
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(px.toFloat(), px.toFloat())
    ) {
        drawChibiAvatar(style, rarity, showLevel, level)
    }
    return bitmap
}

// ============================================================
//  绘制细节
// ============================================================

/** 肩 / 身体：只在底部露出一小块，形成 Q 版大头比例 */
private fun DrawScope.drawChibiBody(
    style: ChibiStyle, c: Offset, r: Float, headCenter: Offset, headR: Float
) {
    val bodyTop = headCenter.y + headR * 0.62f
    val bodyW = r * 1.42f
    val bodyH = (c.y + r) - bodyTop + r * 0.2f
    if (bodyH <= 0f) return
    drawRoundRect(
        color = style.bgBottom.copy(alpha = 0.55f),
        topLeft = Offset(c.x - bodyW / 2f, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(bodyW * 0.42f, bodyW * 0.42f)
    )
    // 衣领高光
    drawRoundRect(
        color = Color.White.copy(alpha = 0.16f),
        topLeft = Offset(c.x - bodyW * 0.34f, bodyTop + bodyH * 0.05f),
        size = Size(bodyW * 0.68f, bodyH * 0.35f),
        cornerRadius = CornerRadius(bodyW * 0.3f, bodyW * 0.3f)
    )
}

/** 后层头发（在脸后面） */
private fun DrawScope.drawChibiHairBack(
    style: ChibiStyle, headCenter: Offset, headR: Float, r: Float
) {
    val hairR = headR * 1.14f

    when (style.hairStyle) {
        ChibiHair.LONG, ChibiHair.BUN, ChibiHair.LONG_MENS -> {
            // 及胸长发：一个包住脸的大圆 + 两侧下垂的发帘
            drawCircle(style.hairDark, radius = hairR, center = Offset(headCenter.x, headCenter.y - headR * 0.04f))
            val fallTop = headCenter.y + headR * 0.1f
            val fallH = r * 1.05f
            val w = headR * 0.62f
            drawRoundRect(
                color = style.hairDark,
                topLeft = Offset(headCenter.x - hairR * 0.92f, fallTop),
                size = Size(w, fallH),
                cornerRadius = CornerRadius(w * 0.5f, w * 0.5f)
            )
            drawRoundRect(
                color = style.hairDark,
                topLeft = Offset(headCenter.x + hairR * 0.92f - w, fallTop),
                size = Size(w, fallH),
                cornerRadius = CornerRadius(w * 0.5f, w * 0.5f)
            )
        }
        ChibiHair.TWIN_TAIL -> {
            drawCircle(style.hairDark, radius = hairR, center = Offset(headCenter.x, headCenter.y - headR * 0.04f))
            // 左右两条马尾，向外撇开
            val tailW = headR * 0.52f
            val tailH = headR * 1.5f
            listOf(-1f, 1f).forEach { dir ->
                drawRoundRect(
                    color = style.hair,
                    topLeft = Offset(
                        headCenter.x + dir * (headR * 0.86f) - tailW / 2f,
                        headCenter.y - headR * 0.12f
                    ),
                    size = Size(tailW, tailH),
                    cornerRadius = CornerRadius(tailW * 0.5f, tailW * 0.5f)
                )
                drawRoundRect(
                    color = style.hairDark.copy(alpha = 0.45f),
                    topLeft = Offset(
                        headCenter.x + dir * (headR * 0.86f) - tailW / 2f,
                        headCenter.y + tailH * 0.52f
                    ),
                    size = Size(tailW, tailH * 0.45f),
                    cornerRadius = CornerRadius(tailW * 0.5f, tailW * 0.5f)
                )
            }
        }
        ChibiHair.PONYTAIL -> {
            drawCircle(style.hairDark, radius = hairR, center = Offset(headCenter.x, headCenter.y - headR * 0.04f))
            val tailW = headR * 0.48f
            val tailH = headR * 1.35f
            drawRoundRect(
                color = style.hair,
                topLeft = Offset(headCenter.x + headR * 0.52f, headCenter.y - headR * 0.3f),
                size = Size(tailW, tailH),
                cornerRadius = CornerRadius(tailW * 0.5f, tailW * 0.5f)
            )
        }
        ChibiHair.SHORT -> {
            // 短发只留一个贴头的后脑轮廓
            drawCircle(style.hairDark, radius = hairR * 0.99f, center = Offset(headCenter.x, headCenter.y - headR * 0.06f))
        }
    }
}

/** 脸部（含耳朵） */
private fun DrawScope.drawChibiHead(style: ChibiStyle, headCenter: Offset, headR: Float) {
    // 耳朵
    listOf(-1f, 1f).forEach { dir ->
        drawCircle(
            color = style.skin,
            radius = headR * 0.17f,
            center = Offset(headCenter.x + dir * headR * 0.95f, headCenter.y + headR * 0.12f)
        )
    }
    // 脸：略扁的椭圆，Q 版脸型
    val faceW = headR * 2f
    val faceH = headR * 1.92f
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(style.skin, style.skin.copy(alpha = 0.92f)),
            startY = headCenter.y - faceH / 2f,
            endY = headCenter.y + faceH / 2f
        ),
        topLeft = Offset(headCenter.x - faceW / 2f, headCenter.y - faceH / 2f),
        size = Size(faceW, faceH)
    )
    // 下巴柔光，增加立体感
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(headCenter.x, headCenter.y + headR * 0.35f),
            radius = headR * 0.8f
        ),
        topLeft = Offset(headCenter.x - faceW / 2f, headCenter.y - faceH / 2f),
        size = Size(faceW, faceH)
    )
}

/**
 * 前层头发 / 刘海。
 *
 * 本轮清理：原先第 4 个参数 `r`（整图半径）从未在函数体里被引用
 * —— 刘海的所有尺寸都以 headR 为基准换算，编译器会报
 * "Parameter 'r' is never used"。它也不参与任何间接逻辑（不是被 style
 * 之类需要），故直接删除该形参，并同步改掉唯一调用点（drawChibiAvatar）。
 */
private fun DrawScope.drawChibiBangs(
    style: ChibiStyle, headCenter: Offset, headR: Float
) {
    val topY = headCenter.y - headR * 1.02f

    // 头顶发盖
    drawArc(
        color = style.hair,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(headCenter.x - headR * 1.06f, headCenter.y - headR * 1.12f),
        size = Size(headR * 2.12f, headR * 2.0f)
    )
    // 发盖暗部
    drawArc(
        color = style.hairDark.copy(alpha = 0.5f),
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = true,
        topLeft = Offset(headCenter.x - headR * 1.06f, headCenter.y - headR * 1.24f),
        size = Size(headR * 2.12f, headR * 2.0f)
    )

    when (style.hairStyle) {
        ChibiHair.SHORT -> {
            // 短碎刘海：三个交错的圆弧
            for (i in -1..1) {
                val x = headCenter.x + i * headR * 0.46f
                drawArc(
                    color = style.hair,
                    startAngle = 190f,
                    sweepAngle = 160f,
                    useCenter = true,
                    topLeft = Offset(x - headR * 0.38f, topY + headR * 0.10f),
                    size = Size(headR * 0.76f, headR * 0.72f)
                )
            }
        }
        ChibiHair.LONG_MENS -> {
            // 中分长发，露出额头，显得冷峻
            val p = Path().apply {
                moveTo(headCenter.x - headR * 1.02f, headCenter.y - headR * 0.30f)
                cubicTo(
                    headCenter.x - headR * 0.85f, topY,
                    headCenter.x - headR * 0.15f, topY + headR * 0.02f,
                    headCenter.x, headCenter.y - headR * 0.52f
                )
                cubicTo(
                    headCenter.x + headR * 0.15f, topY + headR * 0.02f,
                    headCenter.x + headR * 0.85f, topY,
                    headCenter.x + headR * 1.02f, headCenter.y - headR * 0.30f
                )
                lineTo(headCenter.x + headR * 1.06f, headCenter.y - headR * 1.05f)
                lineTo(headCenter.x - headR * 1.06f, headCenter.y - headR * 1.05f)
                close()
            }
            drawPath(p, style.hair)
        }
        ChibiHair.BUN -> {
            // 中分 + 两侧贴脸发，端庄
            val p = Path().apply {
                moveTo(headCenter.x - headR * 1.0f, headCenter.y - headR * 0.18f)
                cubicTo(
                    headCenter.x - headR * 0.8f, topY + headR * 0.08f,
                    headCenter.x - headR * 0.2f, topY + headR * 0.18f,
                    headCenter.x, headCenter.y - headR * 0.44f
                )
                cubicTo(
                    headCenter.x + headR * 0.2f, topY + headR * 0.18f,
                    headCenter.x + headR * 0.8f, topY + headR * 0.08f,
                    headCenter.x + headR * 1.0f, headCenter.y - headR * 0.18f
                )
                lineTo(headCenter.x + headR * 1.06f, headCenter.y - headR * 1.05f)
                lineTo(headCenter.x - headR * 1.06f, headCenter.y - headR * 1.05f)
                close()
            }
            drawPath(p, style.hair)
        }
        else -> {
            // 齐刘海 + 中间一小撮呆毛，通用可爱款
            val p = Path().apply {
                moveTo(headCenter.x - headR * 1.02f, headCenter.y - headR * 0.26f)
                cubicTo(
                    headCenter.x - headR * 0.9f, topY + headR * 0.05f,
                    headCenter.x - headR * 0.3f, topY + headR * 0.28f,
                    headCenter.x, topY + headR * 0.30f
                )
                cubicTo(
                    headCenter.x + headR * 0.3f, topY + headR * 0.28f,
                    headCenter.x + headR * 0.9f, topY + headR * 0.05f,
                    headCenter.x + headR * 1.02f, headCenter.y - headR * 0.26f
                )
                lineTo(headCenter.x + headR * 1.06f, headCenter.y - headR * 1.05f)
                lineTo(headCenter.x - headR * 1.06f, headCenter.y - headR * 1.05f)
                close()
            }
            drawPath(p, style.hair)
            // 呆毛
            val ahoge = Path().apply {
                moveTo(headCenter.x + headR * 0.10f, headCenter.y - headR * 1.0f)
                cubicTo(
                    headCenter.x + headR * 0.30f, headCenter.y - headR * 1.45f,
                    headCenter.x - headR * 0.25f, headCenter.y - headR * 1.55f,
                    headCenter.x - headR * 0.12f, headCenter.y - headR * 1.85f
                )
            }
            drawPath(ahoge, style.hairDark, style = Stroke(width = headR * 0.10f))
        }
    }
}

/** Q 版大眼睛 */
private fun DrawScope.drawChibiEyes(style: ChibiStyle, headCenter: Offset, headR: Float) {
    val eyeDX = headR * 0.42f
    val eyeCY = headCenter.y + headR * 0.16f
    val eyeW = headR * 0.46f
    val eyeH = headR * 0.60f

    listOf(-1f, 1f).forEach { dir ->
        val ex = headCenter.x + dir * eyeDX
        val topLeft = Offset(ex - eyeW / 2f, eyeCY - eyeH / 2f)
        val sz = Size(eyeW, eyeH)

        // 眼白
        drawOval(color = Color(0xFFFFFDFB), topLeft = topLeft, size = sz)
        // 虹膜（上深下浅，动漫常见打光）
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(style.eye.copy(alpha = 0.98f), style.eye.copy(alpha = 0.62f)),
                startY = eyeCY - eyeH / 2f,
                endY = eyeCY + eyeH / 2f
            ),
            topLeft = Offset(ex - eyeW * 0.40f, eyeCY - eyeH * 0.38f),
            size = Size(eyeW * 0.80f, eyeH * 0.80f)
        )
        // 瞳孔
        drawOval(
            color = Color(0xFF241C2B),
            topLeft = Offset(ex - eyeW * 0.21f, eyeCY - eyeH * 0.20f),
            size = Size(eyeW * 0.42f, eyeH * 0.44f)
        )
        // 主高光（左上大光斑）
        drawCircle(
            color = Color.White,
            radius = eyeW * 0.24f,
            center = Offset(ex - eyeW * 0.16f, eyeCY - eyeH * 0.20f)
        )
        // 次高光（右下小光斑）
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = eyeW * 0.11f,
            center = Offset(ex + eyeW * 0.15f, eyeCY + eyeH * 0.22f)
        )
        // 上睫毛：压在眼睛上沿
        drawArc(
            color = Color(0xFF2B2233),
            startAngle = 185f,
            sweepAngle = 170f,
            useCenter = false,
            topLeft = Offset(ex - eyeW * 0.54f, eyeCY - eyeH * 0.60f),
            size = Size(eyeW * 1.08f, eyeH * 1.02f),
            style = Stroke(width = headR * 0.075f)
        )
        // 外眼角睫毛小翘起
        drawLine(
            color = Color(0xFF2B2233),
            start = Offset(ex + dir * eyeW * 0.52f, eyeCY - eyeH * 0.30f),
            end = Offset(ex + dir * eyeW * 0.72f, eyeCY - eyeH * 0.52f),
            strokeWidth = headR * 0.06f
        )
    }
}

/** 眉毛与腮红 */
private fun DrawScope.drawChibiBrowsAndBlush(style: ChibiStyle, headCenter: Offset, headR: Float) {
    val eyeDX = headR * 0.42f
    val browY = headCenter.y - headR * 0.28f
    val isCool = style.hairStyle == ChibiHair.LONG_MENS || style.accessory == ChibiAccessory.SWORD

    listOf(-1f, 1f).forEach { dir ->
        val bx = headCenter.x + dir * eyeDX
        val innerY = if (isCool) browY + headR * 0.05f else browY + headR * 0.03f
        val outerY = if (isCool) browY - headR * 0.04f else browY - headR * 0.01f
        drawLine(
            color = style.hairDark.copy(alpha = 0.85f),
            start = Offset(bx - dir * headR * 0.20f, innerY),
            end = Offset(bx + dir * headR * 0.24f, outerY),
            strokeWidth = headR * 0.075f
        )
    }

    // 腮红：斜椭圆，靠近脸颊外侧
    listOf(-1f, 1f).forEach { dir ->
        val cx = headCenter.x + dir * headR * 0.62f
        val cy = headCenter.y + headR * 0.40f
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(style.blush.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(cx, cy),
                radius = headR * 0.32f
            ),
            topLeft = Offset(cx - headR * 0.28f, cy - headR * 0.17f),
            size = Size(headR * 0.56f, headR * 0.34f)
        )
    }
}

/** 嘴：可爱系给张口笑，冷静系给一条小线 */
private fun DrawScope.drawChibiMouth(style: ChibiStyle, headCenter: Offset, headR: Float) {
    val my = headCenter.y + headR * 0.58f
    val isCheerful = style.hairStyle == ChibiHair.TWIN_TAIL || style.hairStyle == ChibiHair.PONYTAIL

    if (isCheerful) {
        // 张口笑（小"ω"感）
        val p = Path().apply {
            moveTo(headCenter.x - headR * 0.16f, my)
            quadraticBezierTo(headCenter.x, my + headR * 0.04f, headCenter.x, my + headR * 0.13f)
            quadraticBezierTo(headCenter.x, my + headR * 0.04f, headCenter.x + headR * 0.16f, my)
            close()
        }
        drawPath(p, Color(0xFFD4636E))
    } else {
        // 微笑弧
        drawArc(
            color = Color(0xFFD4636E),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(headCenter.x - headR * 0.15f, my - headR * 0.06f),
            size = Size(headR * 0.30f, headR * 0.18f),
            style = Stroke(width = headR * 0.065f)
        )
    }
}

/** 配饰 */
private fun DrawScope.drawChibiAccessory(
    style: ChibiStyle, headCenter: Offset, headR: Float, r: Float
) {
    when (style.accessory) {
        ChibiAccessory.HAIRPIN -> {
            // 右侧小发夹：两个小菱形
            listOf(0f, 1f).forEach { i ->
                val cx = headCenter.x + headR * (0.62f + i * 0.20f)
                val cy = headCenter.y - headR * 0.62f + i * headR * 0.06f
                val p = Path().apply {
                    moveTo(cx, cy - headR * 0.09f)
                    lineTo(cx + headR * 0.07f, cy)
                    lineTo(cx, cy + headR * 0.09f)
                    lineTo(cx - headR * 0.07f, cy)
                    close()
                }
                drawPath(p, if (i == 0f) Color(0xFFFFF176) else Color(0xFFFFD54F))
            }
        }
        ChibiAccessory.RIBBON -> {
            // 左侧蝴蝶结
            val cx = headCenter.x - headR * 0.78f
            val cy = headCenter.y - headR * 0.58f
            listOf(-1f, 1f).forEach { dir ->
                val p = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx + dir * headR * 0.30f, cy - headR * 0.20f)
                    lineTo(cx + dir * headR * 0.30f, cy + headR * 0.20f)
                    close()
                }
                drawPath(p, Color(0xFFEC407A))
            }
            drawCircle(Color(0xFFF8BBD0), radius = headR * 0.09f, center = Offset(cx, cy))
        }
        ChibiAccessory.GLASSES -> {
            // 圆框眼镜
            listOf(-1f, 1f).forEach { dir ->
                drawCircle(
                    color = Color(0xFF90A4AE),
                    radius = headR * 0.40f,
                    center = Offset(headCenter.x + dir * headR * 0.42f, headCenter.y + headR * 0.16f),
                    style = Stroke(width = headR * 0.055f)
                )
            }
            drawLine(
                color = Color(0xFF90A4AE),
                start = Offset(headCenter.x - headR * 0.05f, headCenter.y + headR * 0.14f),
                end = Offset(headCenter.x + headR * 0.05f, headCenter.y + headR * 0.14f),
                strokeWidth = headR * 0.05f
            )
            // 镜片反光
            listOf(-1f, 1f).forEach { dir ->
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(headCenter.x + dir * headR * 0.28f, headCenter.y + headR * 0.44f),
                    end = Offset(headCenter.x + dir * headR * 0.52f, headCenter.y - headR * 0.02f),
                    strokeWidth = headR * 0.07f
                )
            }
        }
        ChibiAccessory.SWORD -> {
            // 背后斜插一把剑
            val bx = headCenter.x + r * 0.72f
            val by = headCenter.y + r * 0.42f
            val tx = headCenter.x - r * 0.10f
            val ty = headCenter.y - r * 0.86f
            drawLine(Color(0xFFB0BEC5), Offset(tx, ty), Offset(bx, by), strokeWidth = r * 0.11f)
            drawLine(Color(0xFFECEFF1), Offset(tx, ty), Offset(bx * 0.55f + tx * 0.45f, by * 0.55f + ty * 0.45f), strokeWidth = r * 0.05f)
            // 护手
            val gx = bx * 0.32f + tx * 0.68f
            val gy = by * 0.32f + ty * 0.68f
            drawLine(Color(0xFF5D4037), Offset(gx - r * 0.11f, gy + r * 0.11f), Offset(gx + r * 0.11f, gy - r * 0.11f), strokeWidth = r * 0.075f)
            // 剑柄
            drawLine(Color(0xFF3E2723), Offset(gx, gy), Offset(bx, by), strokeWidth = r * 0.085f)
        }
        ChibiAccessory.FLOWER -> {
            // 发髻上的花
            val cx = headCenter.x + headR * 0.72f
            val cy = headCenter.y - headR * 0.72f
            repeat(5) { i ->
                val a = Math.toRadians((i * 72 - 90).toDouble())
                drawCircle(
                    color = Color(0xFFF8BBD0),
                    radius = headR * 0.12f,
                    center = Offset(
                        cx + (Math.cos(a) * headR * 0.15f).toFloat(),
                        cy + (Math.sin(a) * headR * 0.15f).toFloat()
                    )
                )
            }
            drawCircle(Color(0xFFFFF176), radius = headR * 0.08f, center = Offset(cx, cy))
        }
        ChibiAccessory.STAR -> {
            // 星星发饰
            val cx = headCenter.x - headR * 0.74f
            val cy = headCenter.y - headR * 0.66f
            val p = Path().apply {
                repeat(10) { i ->
                    val rr = if (i % 2 == 0) headR * 0.20f else headR * 0.085f
                    val a = Math.toRadians((i * 36 - 90).toDouble())
                    val x = cx + (Math.cos(a) * rr).toFloat()
                    val y = cy + (Math.sin(a) * rr).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(p, Color(0xFFFFF176))
            drawPath(p, Color(0xFFFFB300), style = Stroke(width = headR * 0.03f))
        }
        ChibiAccessory.NONE -> Unit
    }
}

// ============================================================
//  兼容旧接口
// ============================================================

/**
 * 旧的 emoji 头像接口，保留用于编辑器里的 emoji 选择器。
 * 聊天/列表请改用 [CharacterAvatar]，视觉更统一。
 */
fun getAvatarEmoji(character: Character): String {
    val tags = character.tags
    return when {
        tags.contains("可爱") || tags.contains("妹系") -> "👼"
        tags.contains("古风") || tags.contains("优雅") -> "👸"
        tags.contains("武侠") || tags.contains("高冷") -> "⚔️"
        tags.contains("傲娇") || tags.contains("学霸") -> "🧑‍🎓"
        tags.contains("元气") -> "🌟"
        tags.contains("温柔") || tags.contains("治愈") -> "🌷"
        else -> "👤"
    }
}

/** 头像底色（给列表项等需要同色系点缀的地方用） */
fun getAvatarAccent(character: Character): Color = resolveChibiStyle(character).bgTop
