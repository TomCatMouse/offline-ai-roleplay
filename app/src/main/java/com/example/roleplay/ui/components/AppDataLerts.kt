package com.example.roleplay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.roleplay.R
import com.example.roleplay.data.model.getTagList

/**
 * 全 App 统一弹窗样式。
 *
 * 设计基调（与深色主题一致）：
 * - 圆角 16dp、深紫蓝底 0xFF232338、宽度固定 232dp（不随屏幕拉伸）
 * - 标题 14sp 加粗白色，正文 12sp 灰白
 * - 按钮：确认紫 #9C27B0 / 取消灰白，字号 13sp
 *
 * 历史调整：
 * - 反馈 #1（上一轮）：字号 -2sp、宽 280→232dp、圆角与内边距收紧
 * - 反馈 #10（本轮）：高度再压一截 + 美化。
 *   原来用 M3 的 [androidx.compose.material3.AlertDialog]，它自带
 *   24dp 上下留白 + 标题/正文各自的内边距，弹窗再小也"很胖"、压不下去。
 *   现在改成 [Dialog] + 自绘 Surface：内边距、按钮高度全部自己说了算，
 *   高度比原来矮约 1/3；另加了左侧紫色小竖条、细描边和更紧凑的操作区。
 *   按钮不再用 TextButton（它写死 40dp 最小高度），改成自绘点击区。
 */
private val DIALOG_SHAPE: Shape = RoundedCornerShape(16.dp)
private val DIALOG_BG = Color(0xFF232338)
/**
 * 弹窗尺寸token（本轮：真机上整体放大约 20%，并保持全 App 一套）。
 *
 * 真机（1368×3192 / 560dpi）上 232dp 宽 + 12sp 正文显得偏小，所有弹窗一起长大：
 * 宽度 232→276dp、标题 14→17sp、正文 12→14sp、按钮 13→15sp，
 * 内边距/按钮高度同比例放大 —— 只改这一处，全 App 弹窗一起变，不会出现大小不一。
 */
private val DIALOG_WIDTH = 300.dp
private const val DIALOG_TITLE_SIZE = 17
private const val DIALOG_BODY_SIZE = 14
private const val DIALOG_BUTTON_SIZE = 15

/**
 * 弹窗内两处纵向间距（本轮第 1 项：「删除弹窗里按钮和提示词中间间距异常」）。
 *
 * 真机实测（1368×3192 / 3.5px per dp，删除角色弹窗）：
 *   标题→正文 28px = **8dp**，正文→按钮 77px = **22dp** —— 下面比上面宽了近 3 倍，
 *   提示词像被"粘"在标题上、按钮却飘在很远的地方，同一排的三个角色卡片都是这个观感。
 *
 * 根因有两层：
 *   1. 正文与按钮之间原来写死 12dp；
 *   2. 按钮胶囊 [DialogAction] 自带 10dp 纵向内边距，这 10dp 会**额外**加进视觉间距。
 * 所以视觉差是 8dp : 22dp。
 *
 * 第二轮微调（用户：「再调大一点」）：8dp 还是不够松，现在给到 Material 规范里
 * 对话框正文与操作按钮之间的标准间距 —— 视觉 24dp：
 *   标题→正文 10dp（视觉 ≈11dp），正文→按钮 14dp + 胶囊内边距 10dp（视觉 ≈24dp）。
 * 真机实测对比：原始 8 : 22（下面太挤上面太空）→ 中间版 10 : 14 → 现在 10 : 24。
 * 全 App 弹窗都走同一个 [DialogShell]，改这两个 token 就是所有弹窗一起统一。
 */
private val DIALOG_TITLE_GAP = 10.dp
private val DIALOG_ACTION_GAP = 14.dp
private val CONFIRM_COLOR = Color(0xFF9C27B0)
/** 次操作按钮：深色胶囊底 + 淡紫字（本轮需求 #4 之前是纯文字，跟弹窗底糊在一起） */
private val CANCEL_TEXT_COLOR = Color(0xFFCE93D8)
private val CANCEL_FILL = Color(0xFF2E2E45)
private val ACCENT_COLOR = Color(0xFFCE93D8)
private val DIALOG_BORDER = Color.White.copy(alpha = 0.08f)

/** 通用确认弹窗（删除/清空/重置/提示等）。cancelText 传 null 可只留一个按钮。 */
@Composable
fun AppConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确定",
    cancelText: String? = "取消",
    confirmColor: Color = CONFIRM_COLOR,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DialogShell(title = title) {
            Text(
                text = text,
                fontSize = DIALOG_BODY_SIZE.sp,
                lineHeight = 21.sp,
                color = Color.White.copy(alpha = 0.75f),
                // 第 2 项（本轮 · 用户第二次指出）：正文区**固定占 2 行**。
                //
                // 之前弹窗高度随正文行数变化，而正文行数取决于角色名的**显示宽度**（不是中英文）：
                // 3 个汉字的名字（来来来/林小夏/苏婉儿…）正文折 2 行，只有 2 个汉字的「墨尘」
                // 和半角的「555」正文 1 行就放下 —— 于是"短名字的弹窗"看起来是另一个版式，
                // 用户把这类（含中文名「墨尘」）都归成"和数字名字一样"的那一组。
                // 现在统一按 2 行排版：一行正文的角色也占满 2 行高度，
                // 所有确认弹窗的标题/正文/按钮位置**完全一致**（真机实测每张卡都是
                // 标题 y=1344、正文 y=1459..1600、按钮 y=1684），
                // 超过 2 行的长文案（如 TTS 不可用提示）照旧自然撑高。
                minLines = 2,
                modifier = Modifier.testTag("confirm_dialog_text")
            )
            Spacer(modifier = Modifier.height(DIALOG_ACTION_GAP))
            DialogActionRow(
                cancelText = cancelText,
                confirmText = confirmText,
                confirmColor = confirmColor,
                onCancel = onDismiss,
                onConfirm = onConfirm
            )
        }
    }
}

/**
 * 文本编辑弹窗（改消息内容 / 改剧情线标题等）。
 *
 * [placeholder] / [hint] / [maxLength] 都是可选参数：不传时行为和以前完全一样，
 * 传了就能替代页面里那些"长得不一样"的 M3 AlertDialog（剧情线改名那个就是），
 * 让全 App 的弹窗共用同一套放大后的样式。
 */
@Composable
fun AppTextDialog(
    title: String,
    initial: String,
    confirmText: String = "保存",
    placeholder: String? = null,
    hint: String? = null,
    maxLength: Int? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DialogShell(title = title) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (maxLength == null || it.length <= maxLength) text = it },
                placeholder = placeholder?.let { p ->
                    { Text(p, fontSize = DIALOG_BODY_SIZE.sp, color = Color.White.copy(alpha = 0.3f)) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                textStyle = TextStyle(fontSize = DIALOG_BODY_SIZE.sp, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF9C27B0),
                    focusedBorderColor = Color(0xFF9C27B0),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    focusedLabelColor = Color(0xFFCE93D8),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                ),
                singleLine = false
            )
            if (hint != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(hint, fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f))
            }
            Spacer(modifier = Modifier.height(DIALOG_ACTION_GAP))
            DialogActionRow(
                cancelText = "取消",
                confirmText = confirmText,
                confirmColor = if (text.isNotBlank()) CONFIRM_COLOR else CONFIRM_COLOR.copy(alpha = 0.4f),
                onCancel = onDismiss,
                onConfirm = { if (text.isNotBlank()) onConfirm(text.trim()) }
            )
        }
    }
}

/**
 * 需求 #11：设置页「打赏」入口的弹窗（第二轮：视觉升级）。
 *
 * 复用全 App 统一的 [DialogShell] 外壳（同款圆角 16dp / 细描边 / 紫色标题条 /
 * 同一套字号与按钮胶囊），所以它和删除、编辑、角色详情等弹窗是同一个版式，
 * 不会出现"另一个 App"的观感。
 *
 * 内容 = 暖色题头（🧋 + 一句感谢语）+ 微信 / 支付宝两张收款码（各自顶着
 * 品牌色标签：微信绿 #07C160、支付宝蓝 #1677FF，一眼认得出）
 *      + 一个「谢谢！❤」收尾按钮。**完全离线**：不联网，
 * 图片就是 `drawable/reward_qr_wechat` / `reward_qr_alipay` 这两张
 * （仓库里只存这一份，README 的打赏章节也引用同一对文件）。
 *
 * 版式：两张码**竖排、各自撑满弹窗宽度**（300dp 外壳减内边距 ≈264dp），
 * 而不是并排 —— 并排每张只剩 ~135dp，真机上二维码识别率明显变差。
 * 竖排整体高度超过 430dp 时那块区域可以滚动，不会顶出屏幕。
 * 换收款码只需替换两个 drawable，代码不用动。
 */
@Composable
fun AppRewardDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DialogShell(title = "打赏作者") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 暖色题头：金色主句 + 灰白辅句，比原来「两行冷白小字」有温度
                // 🧋 emoji 在 MuMu 模拟器的系统字库里缺字形（显示成空框），换成随包矢量图标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_milk_tea),
                        contentDescription = "奶茶",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "请作者喝杯奶茶",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFC266)
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "微信 / 支付宝，扫一下就行 · 你的心意就是继续更新的动力",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.55f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                RewardQr(
                    label = "微信",
                    resId = R.drawable.reward_qr_wechat,
                    tag = "reward_qr_wechat",
                    chipColor = Color(0xFF07C160) // 微信绿
                )
                Spacer(modifier = Modifier.height(14.dp))
                RewardQr(
                    label = "支付宝",
                    resId = R.drawable.reward_qr_alipay,
                    tag = "reward_qr_alipay",
                    chipColor = Color(0xFF1677FF) // 支付宝蓝
                )
            }
            Spacer(modifier = Modifier.height(DIALOG_ACTION_GAP))
            DialogActionRow(
                // 单按钮：点「谢谢」= 关闭弹窗，文案带一颗小心心
                cancelText = null,
                confirmText = "谢谢！❤",
                confirmColor = CONFIRM_COLOR,
                onCancel = onDismiss,
                onConfirm = onDismiss
            )
        }
    }
}

/**
 * 打赏弹窗里的单张收款码：品牌色标签 + 白底圆角图，深色弹窗里边界清晰。
 *
 * 标签用品牌色（微信绿 / 支付宝蓝）做成小胶囊，比原来的灰白文字醒目；
 * 不写死图片高度，让 [Image] 按图片自身比例铺满宽度（两张码长宽比接近，
 * 竖排看不会高低不一）。
 */
@Composable
private fun RewardQr(label: String, resId: Int, tag: String, chipColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(chipColor)
                .padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Image(
            painter = painterResource(id = resId),
            contentDescription = "$label 收款码",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(6.dp)
                .testTag(tag)
        )
    }
}

/**
 * 反馈 #11：角色卡片「查看详情」弹窗。
 *
 * 沿用统一弹窗外壳（同款圆角 / 细描边 / 紫色标题条），
 * 但内容更多，所以单独放宽到 292dp 并让正文可滚动 ——
 * 卡片里的长段落（性格 / 背景）不滚动会把弹窗撑到屏幕外。
 */
@Composable
fun CharacterDetailDialog(
    character: com.example.roleplay.data.model.Character,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = DIALOG_SHAPE,
            color = DIALOG_BG,
            border = BorderStroke(1.dp, DIALOG_BORDER),
            modifier = Modifier.width(330.dp).testTag("character_detail_dialog")
        ) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 17.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 4.dp, height = 16.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(ACCENT_COLOR)
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = character.name.ifBlank { "未命名角色" },
                        fontSize = DIALOG_TITLE_SIZE.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val rarity = com.example.roleplay.data.model.Rarity.entries
                        .firstOrNull { it.value == character.rarity }
                    if (rarity != null) {
                        Text(
                            text = rarity.label,
                            fontSize = 12.sp,
                            color = getRarityColor(character.rarity)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    DetailRow("等级", "Lv.${character.level}")
                    // 用户要求：详情页的标签展示对齐「新建/编辑」页的预览样式 ——
                    // 同款 AssistChip 胶囊（深色底 + 白字 12sp），不再直接甩原始逗号串
                    DetailTagRow(character.getTagList())
                    DetailRow("说话风格", character.speakingStyle.ifBlank { "—" })
                    DetailRow("性格描述", character.personality.ifBlank { "—" })
                    DetailRow("背景故事", character.backstory.ifBlank { "—" })
                    DetailRow("开场白", character.greeting.ifBlank { "—" })
                }

                Spacer(modifier = Modifier.height(12.dp))
                DialogActionRow(
                    cancelText = null,
                    confirmText = "关闭",
                    confirmColor = CONFIRM_COLOR,
                    onCancel = onDismiss,
                    onConfirm = onDismiss
                )
            }
        }
    }
}

/** 详情弹窗里的一行：左侧小标签 + 右侧（可换行）内容 */
@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 9.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = ACCENT_COLOR.copy(alpha = 0.9f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = DIALOG_BODY_SIZE.sp,
            lineHeight = 21.sp,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

/** 详情弹窗里的「标签」行：与 CharacterEditorScreen 的标签预览**同款** AssistChip 胶囊
 *  （容器 0xFF2A2A3E + 白字 12sp，间距 8dp）；标签多时自动换行，
 *  单个标签过长时胶囊内最多折 2 行，都不撑破弹窗。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailTagRow(tags: List<String>) {
    Column(modifier = Modifier.padding(bottom = 9.dp)) {
        Text(
            text = "标签",
            fontSize = 12.sp,
            color = ACCENT_COLOR.copy(alpha = 0.9f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        if (tags.isEmpty()) {
            Text(
                text = "—",
                fontSize = DIALOG_BODY_SIZE.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
        } else {
            FlowRow(
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
    }
}

/**
 * 弹窗外壳：固定宽度、深色表面 + 细描边，标题前一条紫色小竖条。
 * 反馈「所有删除弹窗宽高再大一点」：宽度 276 → 300dp，内边距与「角色详情」对齐
 * （18/18/17/14dp），并加 heightIn(min = 190dp) 让很短的确认框也有足够高度；
 * 仍是全局唯一外壳，所有弹窗继续统一。
 */
@Composable
private fun DialogShell(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = DIALOG_SHAPE,
        color = DIALOG_BG,
        border = BorderStroke(1.dp, DIALOG_BORDER),
        modifier = Modifier
            .width(DIALOG_WIDTH)
            .testTag("app_dialog")
    ) {
        // 最小高度从 Surface 挪到内层 Box，并让内容**垂直居中**：
        // 短弹窗（删除/清空这类一行提示 + 两个按钮）原来内容顶在 190dp 外壳的最上面，
        // 下面空出一大块，看着偏「上飘」；现在内容短于 190dp 时在壳里上下居中、
        // 自动「往下挪一点」，内容超过 190dp 的高弹窗（文本编辑/角色详情）则纹丝不动。
        // 外壳是全 App 唯一弹窗壳（[AppConfirmDialog] / [AppTextDialog] / [CharacterDetailDialog]
        // 都走它），改这一处所有弹窗一起变，统一性由结构保证，不会出现大小不一。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 190.dp),
            // 第 2 项（本轮）：这里原来是 Alignment.Center —— 弹窗外壳有一个 190dp 的最小高度，
            // 内容却只有 115~140dp，于是**内容整块被垂直居中**：正文只有 1 行（名字短/纯数字）时
            // 标题离顶边 99dp、按钮离底边 39dp；正文 2 行（中文名）时又变成 86dp / 27dp。
            // 每个角色的名字长度不同 → 整个内容块上下漂移 → 用户看到"某些角色卡片弹窗样式不一样、
            // 按钮与提示语之间的间距不一致"（实测两组内部间距其实都是 35px/84px，差的是整体位置）。
            // 改成顶部对齐后：标题永远距顶 17dp、正文永远距标题 10dp、按钮永远距正文 14dp，
            // 只有"正文占 1 行还是 2 行"这一处自然差异，其余全部一致。
            contentAlignment = Alignment.TopStart
        ) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 17.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 点缀：紫色小竖条，让标题区不再是一块糊在一起的深色
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ACCENT_COLOR)
                )
                Spacer(modifier = Modifier.width(9.dp))
                Text(
                    text = title,
                    fontSize = DIALOG_TITLE_SIZE.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.testTag("confirm_dialog_title")
                )
            }
            Spacer(modifier = Modifier.height(DIALOG_TITLE_GAP))
            content()
            }
        }
    }
}

/** 弹窗底部操作区：自绘紧凑胶囊按钮（约 25dp 高，比 TextButton 矮一截）。
 *  双按钮时「确认/删除」钉住右端不动，「取消」在左侧；两按钮高度一致。 */
@Composable
private fun DialogActionRow(
    cancelText: String?,
    confirmText: String,
    confirmColor: Color,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val hasCancel = cancelText != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(if (hasCancel) 3f else 1f))
        if (hasCancel) {
            DialogAction(
                text = cancelText!!,
                color = CANCEL_TEXT_COLOR,
                container = CANCEL_FILL,
                testTag = "confirm_dialog_cancel",
                onClick = onCancel
            )
            Spacer(Modifier.weight(1f))
        }
        DialogAction(
            text = confirmText,
            color = Color.White,
            container = confirmColor,
            testTag = "confirm_dialog_confirm",
            onClick = onConfirm
        )
    }
}

/**
 * 本轮需求 #4：弹窗按钮从"纯文字"改成有底色的胶囊。
 *
 * 之前确认键只是一行紫色文字、取消键是一行半透明灰字，跟深色弹窗底糊在一起，
 * 分不出哪个是主操作。现在：
 * - 主操作（确定/更新/保存/删除）：实心主色胶囊 + 白字加粗 + 淡光晕投影
 * - 次操作（取消/保持原样）：深色胶囊 + 淡紫字，弱一档但不隐形
 *
 * 两按钮固定 44dp 高度，保证取消和删除视觉等高。
 */
@Composable
private fun DialogAction(
    text: String,
    color: Color,
    container: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = DIALOG_BUTTON_SIZE.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
